package com.maxrave.domain.data.model.spotifyimport

/**
 * One playlist the user can import: one of their own Spotify playlists, their Liked Songs, or a
 * public playlist resolved from a pasted link.
 *
 * @param uri the Spotify uri (`spotify:playlist:<id>`, or `spotify:collection:tracks` for Liked
 * Songs). It is also the key that remembers which local playlist an earlier import created.
 * @param trackCount null when the library listing does not say (Spotify's library page leaves
 * the count out for playlists; it is known after the first page of tracks).
 */
data class SpotifyPlaylistInfo(
    val uri: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
    val trackCount: Int?,
    val isLikedSongs: Boolean = false,
)

/** A Spotify track as read from a playlist: metadata only, never audio. */
data class SourceTrack(
    val spotifyUri: String?,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long,
    /** Not returned by the web player's playlist query today; kept for when a source has it. */
    val isrc: String? = null,
) {
    val primaryArtist: String get() = artists.firstOrNull().orEmpty()

    /** "Title — Artist, Artist", for lists and the clipboard. */
    val display: String get() = if (artists.isEmpty()) title else "$title — ${artists.joinToString(", ")}"
}

/**
 * A YouTube Music result the matcher can score, carrying enough to become a song row.
 *
 * @param isOfficialSong true for results of the "songs" filter (album tracks, `MUSIC_VIDEO_TYPE_ATV`),
 * false for videos, which are only consulted when no song is good enough.
 */
data class MatchCandidate(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val artistIds: List<String?> = emptyList(),
    val album: String? = null,
    val albumId: String? = null,
    val durationSeconds: Int? = null,
    val thumbnailUrl: String? = null,
    val isExplicit: Boolean = false,
    val videoType: String? = null,
    val isOfficialSong: Boolean = true,
) {
    val display: String get() = if (artists.isEmpty()) title else "$title — ${artists.joinToString(", ")}"
}

/** A track that was not imported because no result scored high enough. */
data class UnmatchedTrack(
    val track: SourceTrack,
    /** The local playlist it belongs in, so a manual pick can be appended there. */
    val localPlaylistId: Long?,
    val playlistName: String,
    /** The best result that still fell short of the threshold, if any came close. */
    val bestGuess: MatchCandidate?,
    val bestScore: Double,
)

data class PlaylistImportResult(
    val spotifyUri: String,
    val name: String,
    val localPlaylistId: Long?,
    /** Tracks read from Spotify, including the skipped ones. */
    val totalTracks: Int,
    val matched: Int,
    /** Songs actually written to the local playlist (fewer than [matched] on a re-import). */
    val added: Int,
    /** Local files, podcast episodes and tracks Spotify no longer has. */
    val skipped: Int,
    val unmatched: List<UnmatchedTrack>,
    /** True when an earlier import's playlist was updated instead of a new one created. */
    val updatedExisting: Boolean,
    val error: String? = null,
)

enum class SpotifyImportPhase { FETCHING, MATCHING, SAVING }

data class SpotifyImportProgress(
    /** 1-based. */
    val playlistIndex: Int,
    val playlistCount: Int,
    val playlistName: String,
    val phase: SpotifyImportPhase,
    /** Tracks of the current playlist that are fetched (FETCHING) or matched (MATCHING). */
    val processed: Int,
    val total: Int,
    /** Running totals across all playlists. */
    val matched: Int,
    val unmatched: Int,
    val skipped: Int,
)

sealed interface SpotifyImportState {
    data object Idle : SpotifyImportState

    data class Running(
        val progress: SpotifyImportProgress,
    ) : SpotifyImportState

    data class Finished(
        val results: List<PlaylistImportResult>,
        val cancelled: Boolean,
    ) : SpotifyImportState {
        val matched: Int get() = results.sumOf { it.matched }
        val unmatched: Int get() = results.sumOf { it.unmatched.size }
        val skipped: Int get() = results.sumOf { it.skipped }
    }
}
