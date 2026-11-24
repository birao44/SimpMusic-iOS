package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.parser.formatMinutesSeconds
import com.maxrave.data.parser.search.parseSearchSong
import com.maxrave.data.parser.search.parseSearchVideo
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.spotifyimport.MatchCandidate
import com.maxrave.domain.data.model.spotifyimport.PlaylistImportResult
import com.maxrave.domain.data.model.spotifyimport.SourceTrack
import com.maxrave.domain.data.model.spotifyimport.SpotifyImportPhase
import com.maxrave.domain.data.model.spotifyimport.SpotifyImportProgress
import com.maxrave.domain.data.model.spotifyimport.SpotifyImportState
import com.maxrave.domain.data.model.spotifyimport.SpotifyPlaylistInfo
import com.maxrave.domain.data.model.spotifyimport.UnmatchedTrack
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SpotifyImportRepository
import com.maxrave.domain.spotifyimport.TrackMatcher
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import com.maxrave.spotify.library.SpotifyPathfinder
import com.maxrave.spotify.library.SpotifyPlaylistEntry
import com.maxrave.spotify.library.SpotifyPlaylistRef
import com.maxrave.spotify.library.SpotifyRateLimitedException
import com.maxrave.spotify.library.SpotifyUnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock

private const val TAG = "SpotifyImport"

/** Parallel YouTube Music searches. Enough to be quick, few enough not to look like a scraper. */
private const val MATCH_CONCURRENCY = 5
private const val SONG_BATCH_SIZE = 500

/** DataStore key: JSON map of Spotify playlist uri → local playlist id, so a re-import updates. */
private const val PLAYLIST_MAP_KEY = "spotify_import_playlist_map"

internal class SpotifyImportRepositoryImpl(
    private val spotify: Spotify,
    private val youTube: YouTube,
    private val localDataSource: LocalDataSource,
    private val dataStoreManager: DataStoreManager,
) : SpotifyImportRepository {
    /**
     * Owned here rather than borrowed: the import must outlive the screen that started it, and the
     * player's service scope is cancelled with the player on Android.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null

    private val _state = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    override val state: StateFlow<SpotifyImportState> = _state.asStateFlow()

    override val isLoggedIn: Flow<Boolean> = dataStoreManager.spdc.map { it.isNotEmpty() }

    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private var anonymousToken: Pair<String, Long>? = null

    // region Tokens

    private fun nowMs() = Clock.System.now().toEpochMilliseconds()

    private suspend fun clientToken(): String =
        tokenMutex.withLock {
            val cached = dataStoreManager.spotifyClientToken.first()
            if (cached.isNotEmpty() && dataStoreManager.spotifyClientTokenExpires.first() > nowMs() + 60_000) return@withLock cached
            val granted = spotify.getClientToken().getOrThrow().grantedToken
            dataStoreManager.setSpotifyClientToken(granted.token)
            dataStoreManager.setSpotifyClientTokenExpires(nowMs() + granted.expiresAfterSeconds * 1000L)
            granted.token
        }

    /**
     * The logged-in token (shared with lyrics/canvas, same DataStore keys), or null without a login.
     * A stored sp_dc that Spotify no longer accepts yields an anonymous token; that is reported as an
     * expired session instead of failing later with a confusing "not authorized".
     */
    private suspend fun userToken(forceRefresh: Boolean = false): String? =
        tokenMutex.withLock {
            val spdc = dataStoreManager.spdc.first()
            if (spdc.isEmpty()) return@withLock null
            val cached = dataStoreManager.spotifyPersonalToken.first()
            if (!forceRefresh && cached.isNotEmpty() && dataStoreManager.spotifyPersonalTokenExpires.first() > nowMs() + 60_000) {
                return@withLock cached
            }
            val token = spotify.getPersonalTokenWithTotp(spdc).getOrThrow()
            if (token.isAnonymous) throw SpotifyUnauthorizedException("Your Spotify session expired. Log in to Spotify again.")
            dataStoreManager.setSpotifyPersonalToken(token.accessToken)
            dataStoreManager.setSpotifyPersonalTokenExpires(token.accessTokenExpirationTimestampMs)
            token.accessToken
        }

    private suspend fun anonToken(forceRefresh: Boolean = false): String =
        tokenMutex.withLock {
            val cached = anonymousToken
            if (!forceRefresh && cached != null && cached.second > nowMs() + 60_000) return@withLock cached.first
            val token = spotify.getAnonymousToken().getOrThrow()
            anonymousToken = token.accessToken to token.accessTokenExpirationTimestampMs
            token.accessToken
        }

    /** The user's token when logged in (sees their private playlists), else an anonymous one. */
    private suspend fun readToken(forceRefresh: Boolean = false): String = userToken(forceRefresh) ?: anonToken(forceRefresh)

    /** Runs [call] once, and once more with fresh tokens if Spotify refused the first. */
    private suspend fun <T> withTokens(call: suspend (auth: String, client: String) -> Result<T>): T {
        val first = call(readToken(), clientToken())
        val error = first.exceptionOrNull() ?: return first.getOrThrow()
        currentCoroutineContext().ensureActive()
        if (error !is SpotifyUnauthorizedException) throw error
        return call(readToken(forceRefresh = true), clientToken()).getOrThrow()
    }

    // endregion

    override suspend fun getLibraryPlaylists(): Result<List<SpotifyPlaylistInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (userToken() == null) throw SpotifyUnauthorizedException("Log in to Spotify to see your playlists")
                withTokens { auth, client ->
                    spotify.getLibraryPlaylists(auth, client).also { result ->
                        // A refused library with a cached token: refresh once (withTokens), then explain.
                        result.exceptionOrNull()?.let { Logger.w(TAG, "library: ${it.message}") }
                    }
                }.map { it.toInfo() }
                    // Liked Songs first, as in Spotify's own library.
                    .sortedByDescending { it.isLikedSongs }
            }.recoverCatching { error ->
                if (error is CancellationException) throw error
                if (error is SpotifyUnauthorizedException && userToken() != null) {
                    throw SpotifyUnauthorizedException("Your Spotify session expired. Log in to Spotify again.")
                }
                throw error
            }
        }

    override suspend fun resolvePlaylistLink(link: String): Result<SpotifyPlaylistInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = SpotifyPathfinder.playlistUriFromLink(link) ?: throw IllegalArgumentException("Not a Spotify playlist link")
                withTokens { auth, client -> spotify.getPlaylistPage(uri, 0, auth, client, limit = 1) }.first.toInfo()
            }
        }

    // region Import

    override fun startImport(playlists: List<SpotifyPlaylistInfo>): Boolean {
        if (playlists.isEmpty() || importJob?.isActive == true) return false
        _state.value = SpotifyImportState.Running(progress(playlists, 0, SpotifyImportPhase.FETCHING, 0, 0, Totals()))
        importJob = scope.launch { runImport(playlists) }
        return true
    }

    override fun cancel() {
        importJob?.cancel()
    }

    override fun dismissResult() {
        if (importJob?.isActive == true) return
        _state.value = SpotifyImportState.Idle
    }

    private class Totals {
        var matched = 0
        var unmatched = 0
        var skipped = 0
    }

    private fun progress(
        playlists: List<SpotifyPlaylistInfo>,
        index: Int,
        phase: SpotifyImportPhase,
        processed: Int,
        total: Int,
        totals: Totals,
    ) = SpotifyImportProgress(
        playlistIndex = index + 1,
        playlistCount = playlists.size,
        playlistName = playlists.getOrNull(index)?.name.orEmpty(),
        phase = phase,
        processed = processed,
        total = total,
        matched = totals.matched,
        unmatched = totals.unmatched,
        skipped = totals.skipped,
    )

    private suspend fun runImport(playlists: List<SpotifyPlaylistInfo>) {
        val results = mutableListOf<PlaylistImportResult>()
        val totals = Totals()
        // Same song in several playlists (or twice in one) is searched once per run.
        val matchCache = mutableMapOf<String, TrackMatcher.Scored?>()
        val cacheMutex = Mutex()
        var cancelled = false
        for ((index, info) in playlists.withIndex()) {
            val outcome =
                runCatching {
                    importPlaylist(playlists, index, info, totals, matchCache, cacheMutex) { partial -> results += partial }
                }
            val error = outcome.exceptionOrNull()
            if (error is CancellationException) {
                cancelled = true
                break
            }
            if (error != null) {
                Logger.e(TAG, "import ${info.uri}: ${error.message}")
                results +=
                    PlaylistImportResult(
                        spotifyUri = info.uri,
                        name = info.name,
                        localPlaylistId = null,
                        totalTracks = 0,
                        matched = 0,
                        added = 0,
                        skipped = 0,
                        unmatched = emptyList(),
                        updatedExisting = false,
                        error = error.message ?: error::class.simpleName,
                    )
                // A day-long rate limit will hit every following playlist too.
                if (error is SpotifyRateLimitedException) break
            } else {
                results += outcome.getOrThrow()
            }
        }
        Logger.i(TAG, "import finished: ${results.size} playlists, matched ${totals.matched}, unmatched ${totals.unmatched}, cancelled=$cancelled")
        _state.value = SpotifyImportState.Finished(results.toList(), cancelled)
    }

    /**
     * Fetch → match → save one playlist. On cancellation during matching, what is matched so far is
     * saved (handed to [onCancelledPartial]) before the cancellation propagates.
     */
    private suspend fun importPlaylist(
        playlists: List<SpotifyPlaylistInfo>,
        index: Int,
        info: SpotifyPlaylistInfo,
        totals: Totals,
        matchCache: MutableMap<String, TrackMatcher.Scored?>,
        cacheMutex: Mutex,
        onCancelledPartial: (PlaylistImportResult) -> Unit,
    ): PlaylistImportResult {
        // 1. Fetch every page.
        val entries = mutableListOf<SpotifyPlaylistEntry>()
        var ref: SpotifyPlaylistRef? = null
        var offset = 0
        var total = info.trackCount ?: 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val (pageRef, page) =
                if (info.isLikedSongs) {
                    null to withTokens { auth, client -> spotify.getLikedTracksPage(offset, auth, client) }
                } else {
                    withTokens { auth, client -> spotify.getPlaylistPage(info.uri, offset, auth, client) }
                }
            if (pageRef != null && ref == null) ref = pageRef
            total = page.totalCount
            entries += page.items
            offset += page.items.size
            _state.value = SpotifyImportState.Running(progress(playlists, index, SpotifyImportPhase.FETCHING, entries.size, total, totals))
            if (page.items.isEmpty() || offset >= total) break
        }

        val tracks = entries.filterIsInstance<SpotifyPlaylistEntry.Track>().map { it.track.toSource() }
        val skipped = entries.size - tracks.size
        totals.skipped += skipped

        // 2. Match, a few at a time, keeping the Spotify order.
        val outcomes = arrayOfNulls<TrackMatcher.Scored?>(tracks.size)
        val done = BooleanArray(tracks.size)
        val progressMutex = Mutex()
        var processed = 0
        val semaphore = Semaphore(MATCH_CONCURRENCY)
        _state.value = SpotifyImportState.Running(progress(playlists, index, SpotifyImportPhase.MATCHING, 0, tracks.size, totals))

        val playlistName = if (info.isLikedSongs) "${info.name} (Spotify)" else ref?.name?.ifBlank { null } ?: info.name
        val cover = ref?.imageUrl ?: info.imageUrl

        suspend fun save(cancelled: Boolean): PlaylistImportResult {
            val matchedPairs = tracks.indices.mapNotNull { i -> outcomes[i]?.takeIf { done[i] && it.accepted }?.let { i to it } }
            val unmatchedTracks = tracks.indices.filter { done[it] && outcomes[it]?.accepted != true }
            _state.value = SpotifyImportState.Running(progress(playlists, index, SpotifyImportPhase.SAVING, processed, tracks.size, totals))
            val songs = matchedPairs.map { it.second.candidate.toSongEntity() }.distinctBy { it.videoId }
            val saved = saveLocalPlaylist(info.uri, playlistName, cover ?: songs.firstOrNull()?.thumbnails, songs)
            return PlaylistImportResult(
                spotifyUri = info.uri,
                name = playlistName,
                localPlaylistId = saved.id,
                totalTracks = entries.size,
                matched = matchedPairs.size,
                added = saved.added,
                skipped = skipped,
                unmatched =
                    unmatchedTracks.map { i ->
                        val best = outcomes[i]
                        UnmatchedTrack(
                            track = tracks[i],
                            localPlaylistId = saved.id,
                            playlistName = playlistName,
                            bestGuess = best?.takeIf { it.score >= TrackMatcher.GUESS_SCORE }?.candidate,
                            bestScore = best?.score ?: 0.0,
                        )
                    },
                updatedExisting = saved.updated,
                error = if (cancelled) "cancelled" else null,
            )
        }

        try {
            coroutineScope {
                tracks.forEachIndexed { i, track ->
                    launch {
                        semaphore.withPermit {
                            val key = track.spotifyUri ?: "${track.title}|${track.primaryArtist}|${track.durationMs}"
                            val cached = cacheMutex.withLock { if (matchCache.containsKey(key)) Result.success(matchCache[key]) else null }
                            val best =
                                if (cached != null) {
                                    cached.getOrNull()
                                } else {
                                    matchTrack(track).also { r -> cacheMutex.withLock { matchCache[key] = r } }
                                }
                            progressMutex.withLock {
                                outcomes[i] = best
                                done[i] = true
                                processed++
                                if (best?.accepted == true) totals.matched++ else totals.unmatched++
                                _state.value =
                                    SpotifyImportState.Running(
                                        progress(playlists, index, SpotifyImportPhase.MATCHING, processed, tracks.size, totals),
                                    )
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (done.any { it }) runCatching { onCancelledPartial(save(cancelled = true)) }
            }
            throw e
        }

        // 3. Save.
        return withContext(NonCancellable) { save(cancelled = false) }
    }

    /** The best-scoring result, accepted or not; null when the search returned nothing. */
    private suspend fun matchTrack(track: SourceTrack): TrackMatcher.Scored? {
        val query = TrackMatcher.query(track)
        if (query.isBlank()) return null
        val songs = search(query, videos = false)
        val rankedSongs = TrackMatcher.rank(track, songs)
        if (rankedSongs.firstOrNull()?.accepted == true) return rankedSongs.first()
        // No good song: a video (official video, lyric video, upload) may still be the track.
        val videos = search(query, videos = true)
        return (rankedSongs + TrackMatcher.rank(track, videos)).maxByOrNull { it.score }
    }

    /** One YouTube Music search, retried twice on failure; an empty list when it keeps failing. */
    private suspend fun search(
        query: String,
        videos: Boolean,
    ): List<MatchCandidate> {
        repeat(3) { attempt ->
            val result =
                runCatching {
                    if (videos) {
                        parseSearchVideo(youTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrThrow()).take(5).map { it.toCandidate() }
                    } else {
                        parseSearchSong(youTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow()).take(10).map { it.toCandidate() }
                    }
                }
            currentCoroutineContext().ensureActive()
            result.onSuccess { return it }
            Logger.w(TAG, "search failed (attempt ${attempt + 1}): ${result.exceptionOrNull()?.message}")
            delay(1000L * (attempt + 1) * (attempt + 1))
        }
        return emptyList()
    }

    // endregion

    // region Saving

    private data class SavedPlaylist(
        val id: Long?,
        val added: Int,
        val updated: Boolean,
    )

    private suspend fun loadPlaylistMap(): MutableMap<String, Long> =
        dataStoreManager
            .getString(PLAYLIST_MAP_KEY)
            .first()
            ?.let { runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }
            ?.toMutableMap()
            ?: mutableMapOf()

    private suspend fun savePlaylistMap(map: Map<String, Long>) {
        dataStoreManager.putString(PLAYLIST_MAP_KEY, json.encodeToString(map))
    }

    /**
     * Writes the songs, then creates the local playlist — or, when an earlier import created one
     * that still exists, appends the songs it does not have yet, in Spotify order.
     */
    private suspend fun saveLocalPlaylist(
        spotifyUri: String,
        name: String,
        cover: String?,
        songs: List<SongEntity>,
    ): SavedPlaylist {
        songs.chunked(SONG_BATCH_SIZE).forEach { localDataSource.insertSongs(it) }
        val ids = songs.map { it.videoId }
        val map = loadPlaylistMap()
        val existing = map[spotifyUri]?.let { localDataSource.getLocalPlaylist(it) }
        if (existing != null) {
            val added = appendToLocalPlaylist(existing, ids)
            if (existing.thumbnail.isNullOrEmpty() && cover != null) localDataSource.updateLocalPlaylistThumbnail(cover, existing.id)
            return SavedPlaylist(existing.id, added, updated = true)
        }
        val id =
            localDataSource.insertLocalPlaylistWithTracks(
                LocalPlaylistEntity(title = name, thumbnail = cover, tracks = ids),
                ids,
            )
        if (id == -1L) return SavedPlaylist(null, 0, false)
        map[spotifyUri] = id
        savePlaylistMap(map)
        return SavedPlaylist(id, ids.size, updated = false)
    }

    /** Appends the ids [playlist] does not contain yet; returns how many were added. */
    private suspend fun appendToLocalPlaylist(
        playlist: LocalPlaylistEntity,
        videoIds: List<String>,
    ): Int {
        val current = playlist.tracks.orEmpty()
        val have = current.toHashSet()
        val newIds = videoIds.filter { have.add(it) }
        if (newIds.isEmpty()) return 0
        val addedAt = now()
        newIds.forEachIndexed { i, videoId ->
            localDataSource.insertPairSongLocalPlaylist(
                PairSongLocalPlaylist(playlistId = playlist.id, songId = videoId, position = current.size + i, inPlaylist = addedAt),
            )
        }
        localDataSource.updateLocalPlaylistTracks(current + newIds, playlist.id)
        return newIds.size
    }

    override suspend fun searchCandidates(
        query: String,
        forTrack: UnmatchedTrack?,
    ): Result<List<MatchCandidate>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val songs = parseSearchSong(youTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow()).take(15).map { it.toCandidate() }
                val videos =
                    runCatching {
                        parseSearchVideo(youTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrThrow()).take(5).map { it.toCandidate() }
                    }.getOrDefault(emptyList())
                val all = (songs + videos).distinctBy { it.videoId }
                if (forTrack == null) all else TrackMatcher.rank(forTrack.track, all).map { it.candidate }
            }
        }

    override suspend fun resolveUnmatched(
        unmatched: UnmatchedTrack,
        candidate: MatchCandidate,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val playlistId = unmatched.localPlaylistId ?: throw IllegalStateException("The playlist was not saved")
                val song = candidate.toSongEntity()
                localDataSource.insertSong(song)
                val playlist = localDataSource.getLocalPlaylist(playlistId) ?: throw IllegalStateException("The playlist no longer exists")
                appendToLocalPlaylist(playlist, listOf(song.videoId))
                _state.update { state ->
                    if (state !is SpotifyImportState.Finished) return@update state
                    state.copy(
                        results =
                            state.results.map { r ->
                                if (r.unmatched.none { it === unmatched }) {
                                    r
                                } else {
                                    r.copy(unmatched = r.unmatched.filterNot { it === unmatched }, matched = r.matched + 1, added = r.added + 1)
                                }
                            },
                    )
                }
                Unit
            }
        }

    // endregion
}

private fun SpotifyPlaylistRef.toInfo() =
    SpotifyPlaylistInfo(
        uri = uri,
        name = name,
        owner = owner,
        imageUrl = imageUrl,
        trackCount = trackCount,
        isLikedSongs = isLikedSongs,
    )

private fun com.maxrave.spotify.library.SpotifyTrackRef.toSource() =
    SourceTrack(
        spotifyUri = uri,
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
    )

private fun com.maxrave.domain.data.model.searchResult.songs.SongsResult.toCandidate() =
    MatchCandidate(
        videoId = videoId,
        title = title.orEmpty(),
        artists = artists.orEmpty().map { it.name },
        artistIds = artists.orEmpty().map { it.id },
        album = album?.name,
        albumId = album?.id,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnails?.lastOrNull()?.url,
        isExplicit = isExplicit ?: false,
        videoType = videoType,
        isOfficialSong = true,
    )

private fun com.maxrave.domain.data.model.searchResult.videos.VideosResult.toCandidate() =
    MatchCandidate(
        videoId = videoId,
        title = title,
        artists = artists.orEmpty().map { it.name },
        artistIds = artists.orEmpty().map { it.id },
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnails?.lastOrNull()?.url,
        videoType = videoType,
        isOfficialSong = false,
    )

/**
 * The same row adding a search result to a playlist writes (`Track.toSongEntity()`), built straight
 * from the candidate. Artist ids are kept only when every artist has one, because
 * `SongEntity.toTrack()` indexes the id list by the name list.
 */
private fun MatchCandidate.toSongEntity(): SongEntity {
    val ids = artistIds.takeIf { it.size == artists.size && it.all { id -> id != null } }?.map { it!! }
    return SongEntity(
        videoId = videoId,
        albumId = albumId,
        albumName = album,
        artistId = ids,
        artistName = artists,
        duration = durationSeconds?.takeIf { it > 0 }?.let { formatMinutesSeconds(it) } ?: "",
        durationSeconds = durationSeconds ?: 0,
        isAvailable = true,
        isExplicit = isExplicit,
        likeStatus = "",
        thumbnails = thumbnailUrl,
        title = title,
        videoType = MusicVideoType.normalize(videoType) ?: "",
        category = null,
        resultType = null,
        liked = false,
        totalPlayTime = 0,
        downloadState = 0,
    )
}
