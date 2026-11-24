package com.maxrave.spotify.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The web player's own GraphQL queries ("pathfinder") for the user's library and playlist contents.
 *
 * Why not the public Web API (`api.spotify.com/v1/...`): with the web player's token it answers
 * every request with 429 QUOTA_EXCEEDED and `retry-after: 86400` (verified 2026-11-17). The web
 * player itself reads playlists through these persisted queries, which accept the same token.
 *
 * The hashes are the persisted-query ids from the web player bundle (`web-player.<hash>.js`, look for
 * `new aQ.l("fetchPlaylist","query","<sha256>")`). Spotify rotates them now and then; a stale one is
 * answered with HTTP 412 "Invalid query hash", which [SpotifyPathfinderParser] turns into a clear
 * error. Verified 2026-11-17: fetchPlaylist works anonymously for public playlists; libraryV3 and
 * fetchLibraryTracks resolve (they answer "User is not authorized" without a login, not 412).
 *
 * Everything here builds or reads JSON only, so it is unit-testable without a network.
 */
object SpotifyPathfinder {
    const val FETCH_PLAYLIST = "fetchPlaylist"
    const val FETCH_PLAYLIST_HASH = "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf"
    const val LIBRARY_V3 = "libraryV3"
    const val LIBRARY_V3_HASH = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
    const val FETCH_LIBRARY_TRACKS = "fetchLibraryTracks"
    const val FETCH_LIBRARY_TRACKS_HASH = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"

    const val LIKED_SONGS_URI = "spotify:collection:tracks"

    /** The web player pages playlists by 25–100; 100 is accepted and halves the request count. */
    const val PAGE_SIZE = 100
    const val LIBRARY_PAGE_SIZE = 50

    private fun body(
        operation: String,
        hash: String,
        variables: JsonObject,
    ): String =
        buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            put(
                "extensions",
                buildJsonObject {
                    put(
                        "persistedQuery",
                        buildJsonObject {
                            put("version", 1)
                            put("sha256Hash", hash)
                        },
                    )
                },
            )
        }.toString()

    fun playlistBody(
        uri: String,
        offset: Int,
        limit: Int,
    ): String =
        body(
            FETCH_PLAYLIST,
            FETCH_PLAYLIST_HASH,
            buildJsonObject {
                put("uri", uri)
                put("offset", offset)
                put("limit", limit)
                put("enableWatchFeedEntrypoint", false)
            },
        )

    /** Flattened, so playlists inside folders are listed too; Liked Songs is asked for explicitly. */
    fun libraryBody(
        offset: Int,
        limit: Int,
    ): String =
        body(
            LIBRARY_V3,
            LIBRARY_V3_HASH,
            buildJsonObject {
                put("filters", buildJsonArray { add(JsonPrimitive("Playlists")) })
                put("order", JsonNull)
                put("textFilter", JsonNull)
                put("features", buildJsonArray { add(JsonPrimitive("LIKED_SONGS")) })
                put("limit", limit)
                put("offset", offset)
                put("flatten", true)
                put("folderUri", JsonNull)
                put("includeFoldersWhenFlattening", true)
            },
        )

    fun likedTracksBody(
        offset: Int,
        limit: Int,
    ): String =
        body(
            FETCH_LIBRARY_TRACKS,
            FETCH_LIBRARY_TRACKS_HASH,
            buildJsonObject {
                put("offset", offset)
                put("limit", limit)
            },
        )

    private val idRegex = Regex("""^[A-Za-z0-9]{22}$""")
    private val linkRegex = Regex("""(?:open|play)\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?(?:user/[^/]+/)?playlist/([A-Za-z0-9]{22})""")
    private val uriRegex = Regex("""spotify:(?:user:[^:]+:)?playlist:([A-Za-z0-9]{22})""")

    /**
     * The playlist uri in a pasted link, uri or bare id, or null. Accepts
     * `https://open.spotify.com/playlist/<id>?si=…`, `/intl-de/playlist/<id>`, `/embed/playlist/<id>`,
     * `spotify:playlist:<id>` and a bare 22-character id. Short `spotify.link` links are not resolved.
     */
    fun playlistUriFromLink(text: String): String? {
        val trimmed = text.trim()
        val id =
            linkRegex.find(trimmed)?.groupValues?.get(1)
                ?: uriRegex.find(trimmed)?.groupValues?.get(1)
                ?: trimmed.takeIf { idRegex.matches(it) }
        return id?.let { "spotify:playlist:$it" }
    }
}

object SpotifyPathfinderParser {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.int(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject?.path(vararg keys: String): JsonElement? {
        var cur: JsonElement? = this
        for (k in keys) cur = cur.obj()?.get(k) ?: return null
        return cur
    }

    /** Parses [body] and throws the matching exception when the response is an error. */
    internal fun root(body: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body).obj() }.getOrNull()
        if (root == null) {
            if (body.contains("Invalid query hash")) {
                throw IllegalStateException("Spotify changed its web player queries (invalid query hash); the app needs an update")
            }
            throw IllegalStateException("Unexpected answer from Spotify")
        }
        val errors = root["errors"].arr()
        if (errors != null && errors.isNotEmpty()) {
            val message = errors.mapNotNull { it.obj().str("message") }.joinToString("; ")
            val dataIsEmpty = root["data"].obj()?.values?.all { it is JsonNull } ?: true
            if (dataIsEmpty) {
                if (message.contains("not authorized", ignoreCase = true)) throw SpotifyUnauthorizedException(message)
                throw IllegalStateException(message.ifBlank { "Spotify returned an error" })
            }
        }
        return root
    }

    private fun firstImage(sources: JsonElement?): String? {
        val list = sources.arr() ?: return null
        // The web player lists 300/64/640 px; take the largest so the local cover stays sharp.
        return list
            .mapNotNull { it.obj() }
            .maxByOrNull { it.int("width") ?: it.int("height") ?: 0 }
            ?.str("url")
            ?: list.firstOrNull().obj().str("url")
    }

    fun parsePlaylist(
        body: String,
        uri: String,
    ): Pair<SpotifyPlaylistRef, SpotifyPage<SpotifyPlaylistEntry>> {
        val root = root(body)
        val playlist = root.path("data", "playlistV2").obj() ?: throw SpotifyNotFoundException("Playlist not found")
        when (playlist.str("__typename")) {
            "Playlist" -> Unit
            "NotFound", "GenericError" -> throw SpotifyNotFoundException(playlist.str("message") ?: "Playlist not found")
            else -> throw SpotifyNotFoundException("Playlist not available")
        }
        val content = playlist["content"].obj()
        val total = content.int("totalCount") ?: 0
        val offset = content.path("pagingInfo").obj().int("offset") ?: 0
        val image =
            playlist.path("images", "items").arr()?.firstOrNull()?.obj()?.get("sources")
                .let { firstImage(it) }
        val owner = playlist.path("ownerV2", "data").obj().str("name")
        val ref =
            SpotifyPlaylistRef(
                uri = playlist.str("uri") ?: uri,
                name = playlist.str("name").orEmpty(),
                owner = owner,
                imageUrl = image,
                trackCount = total,
            )
        val entries = content?.get("items").arr().orEmpty().map { parsePlaylistItem(it.obj()) }
        return ref to SpotifyPage(entries, offset, total)
    }

    internal fun parsePlaylistItem(item: JsonObject?): SpotifyPlaylistEntry {
        val wrapper = item?.get("itemV2").obj() ?: return SpotifyPlaylistEntry.Skipped(SpotifySkipReason.UNKNOWN, null)
        val data = wrapper["data"].obj()
        val wrapperType = wrapper.str("__typename").orEmpty()
        val dataType = data.str("__typename").orEmpty()
        val uri = data.str("uri") ?: wrapper.str("_uri")
        val name = data.str("name")
        return when {
            uri?.startsWith("spotify:local:") == true || wrapperType.contains("Local") || dataType.contains("Local") ->
                SpotifyPlaylistEntry.Skipped(SpotifySkipReason.LOCAL_FILE, name)
            wrapperType.contains("Episode") || dataType == "Episode" || dataType == "Chapter" || uri?.startsWith("spotify:episode:") == true ->
                SpotifyPlaylistEntry.Skipped(SpotifySkipReason.EPISODE, name)
            dataType == "Track" -> parseTrack(data!!, uri)?.let { SpotifyPlaylistEntry.Track(it) }
                ?: SpotifyPlaylistEntry.Skipped(SpotifySkipReason.UNAVAILABLE, name)
            dataType == "NotFound" || dataType == "RestrictedContent" ->
                SpotifyPlaylistEntry.Skipped(SpotifySkipReason.UNAVAILABLE, name)
            else -> SpotifyPlaylistEntry.Skipped(SpotifySkipReason.UNKNOWN, name)
        }
    }

    /** Playlist items carry `trackDuration`, liked-song items `duration`; both are read. */
    internal fun parseTrack(
        data: JsonObject,
        uri: String?,
    ): SpotifyTrackRef? {
        val title = data.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val artists =
            data.path("artists", "items").arr().orEmpty().mapNotNull {
                it.obj().path("profile").obj().str("name")
            }
        val duration =
            data.path("trackDuration").obj().long("totalMilliseconds")
                ?: data.path("duration").obj().long("totalMilliseconds")
                ?: 0L
        return SpotifyTrackRef(
            uri = uri,
            title = title,
            artists = artists,
            album = data.path("albumOfTrack").obj().str("name"),
            durationMs = duration,
            isPlayable = data.path("playability").obj()?.get("playable")?.let { (it as? JsonPrimitive)?.contentOrNull != "false" } ?: true,
        )
    }

    fun parseLikedTracks(body: String): SpotifyPage<SpotifyPlaylistEntry> {
        val root = root(body)
        val tracks =
            root.path("data", "me", "library", "tracks").obj()
                ?: throw SpotifyUnauthorizedException("Liked Songs are not available for this session")
        val total = tracks.int("totalCount") ?: 0
        val offset = tracks.path("pagingInfo").obj().int("offset") ?: 0
        val entries =
            tracks["items"].arr().orEmpty().map { element ->
                val wrapper = element.obj()?.get("track").obj()
                val data = wrapper?.get("data").obj()
                val uri = wrapper.str("_uri") ?: data.str("uri")
                if (data.str("__typename") == "Track") {
                    parseTrack(data!!, uri)?.let { SpotifyPlaylistEntry.Track(it) }
                        ?: SpotifyPlaylistEntry.Skipped(SpotifySkipReason.UNAVAILABLE, null)
                } else {
                    SpotifyPlaylistEntry.Skipped(SpotifySkipReason.UNAVAILABLE, data.str("name"))
                }
            }
        return SpotifyPage(entries, offset, total)
    }

    /**
     * One page of the user's library. Rows that are not playlists (folders, albums the filter let
     * through, the Your Episodes pseudo-playlist) are dropped; Liked Songs is kept, marked.
     */
    fun parseLibrary(body: String): SpotifyPage<SpotifyPlaylistRef> {
        val root = root(body)
        val me = root.path("data", "me").obj() ?: throw SpotifyUnauthorizedException("Log in to Spotify to list your playlists")
        // The field is `libraryV3` today; take whichever child is the library page so a rename of
        // the alias alone does not break the import.
        val page =
            (me["libraryV3"].obj() ?: me.values.mapNotNull { it.obj() }.firstOrNull { it.containsKey("items") })
                ?: throw IllegalStateException("Unexpected library answer from Spotify")
        val total = page.int("totalCount") ?: 0
        val offset = page.path("pagingInfo").obj().int("offset") ?: 0
        val refs =
            page["items"].arr().orEmpty().mapNotNull { row ->
                val wrapper = row.obj()?.get("item").obj() ?: return@mapNotNull null
                val data = wrapper["data"].obj()
                when (data.str("__typename")) {
                    "Playlist" -> {
                        val uri = data.str("uri") ?: wrapper.str("_uri") ?: return@mapNotNull null
                        // "Your Episodes" is a Playlist too, under spotify:collection:…; podcasts only.
                        if (!uri.startsWith("spotify:playlist:")) return@mapNotNull null
                        SpotifyPlaylistRef(
                            uri = uri,
                            name = data.str("name").orEmpty(),
                            owner = data.path("ownerV2", "data").obj().str("name"),
                            imageUrl = data.path("images", "items").arr()?.firstOrNull()?.obj()?.get("sources").let { firstImage(it) },
                            trackCount = data.path("content").obj().int("totalCount"),
                        )
                    }
                    "PseudoPlaylist" -> {
                        val uri = data.str("uri") ?: wrapper.str("_uri") ?: return@mapNotNull null
                        if (uri != SpotifyPathfinder.LIKED_SONGS_URI) return@mapNotNull null
                        SpotifyPlaylistRef(
                            uri = uri,
                            name = data.str("name") ?: "Liked Songs",
                            owner = null,
                            imageUrl = data.path("image").obj()?.get("sources").let { firstImage(it) },
                            trackCount = data.int("count"),
                            isLikedSongs = true,
                        )
                    }
                    else -> null
                }
            }
        return SpotifyPage(refs, offset, total)
    }
}
