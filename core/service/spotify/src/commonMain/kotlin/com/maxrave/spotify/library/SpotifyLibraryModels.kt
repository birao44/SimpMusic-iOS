package com.maxrave.spotify.library

/** A playlist row from the user's library, or a playlist resolved from a link. */
data class SpotifyPlaylistRef(
    val uri: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
    val trackCount: Int?,
    val isLikedSongs: Boolean = false,
)

data class SpotifyTrackRef(
    val uri: String?,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long,
    val isPlayable: Boolean,
)

enum class SpotifySkipReason { LOCAL_FILE, EPISODE, UNAVAILABLE, UNKNOWN }

sealed interface SpotifyPlaylistEntry {
    data class Track(
        val track: SpotifyTrackRef,
    ) : SpotifyPlaylistEntry

    data class Skipped(
        val reason: SpotifySkipReason,
        val title: String?,
    ) : SpotifyPlaylistEntry
}

data class SpotifyPage<T>(
    val items: List<T>,
    val offset: Int,
    val totalCount: Int,
)

/** Spotify asked us to slow down for longer than is worth waiting for. */
class SpotifyRateLimitedException(
    val retryAfterSeconds: Long,
) : Exception("Spotify rate limit, retry after ${retryAfterSeconds}s")

/** The token was refused (expired, revoked, or an anonymous token asking for the user's library). */
class SpotifyUnauthorizedException(
    message: String,
) : Exception(message)

/** The playlist does not exist or is private to someone else. */
class SpotifyNotFoundException(
    message: String,
) : Exception(message)
