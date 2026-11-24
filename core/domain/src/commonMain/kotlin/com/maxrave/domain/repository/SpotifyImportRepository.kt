package com.maxrave.domain.repository

import com.maxrave.domain.data.model.spotifyimport.MatchCandidate
import com.maxrave.domain.data.model.spotifyimport.SpotifyImportState
import com.maxrave.domain.data.model.spotifyimport.SpotifyPlaylistInfo
import com.maxrave.domain.data.model.spotifyimport.UnmatchedTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Imports Spotify playlists into local playlists by finding each track on YouTube Music.
 *
 * Only metadata is read from Spotify (titles, artists, album, length) with the user's own web
 * session, or anonymously for public playlists; audio always comes from YouTube Music.
 *
 * The import runs in the application scope, not the caller's: leaving the screen does not stop it,
 * and [state] is what any screen shows when it comes back.
 */
interface SpotifyImportRepository {
    val state: StateFlow<SpotifyImportState>

    /** True while an `sp_dc` session is stored. */
    val isLoggedIn: Flow<Boolean>

    /** The user's playlists and Liked Songs. Fails when not logged in or the session expired. */
    suspend fun getLibraryPlaylists(): Result<List<SpotifyPlaylistInfo>>

    /** Resolves a pasted playlist link (or uri, or id). Works without a login for public playlists. */
    suspend fun resolvePlaylistLink(link: String): Result<SpotifyPlaylistInfo>

    /** Starts importing [playlists] in order. Returns false if an import is already running. */
    fun startImport(playlists: List<SpotifyPlaylistInfo>): Boolean

    /** Stops the running import. What was matched so far is still saved. */
    fun cancel()

    /** Back to [SpotifyImportState.Idle] after the summary was seen. */
    fun dismissResult()

    /** Song (then video) results for a manual search from the summary, best fit first if [forTrack] is given. */
    suspend fun searchCandidates(
        query: String,
        forTrack: UnmatchedTrack? = null,
    ): Result<List<MatchCandidate>>

    /**
     * Appends [candidate] to [unmatched]'s local playlist and drops it from the summary. Appended at
     * the end: the playlist's order is otherwise the Spotify order.
     */
    suspend fun resolveUnmatched(
        unmatched: UnmatchedTrack,
        candidate: MatchCandidate,
    ): Result<Unit>
}
