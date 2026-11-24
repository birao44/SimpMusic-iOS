package com.maxrave.spotify

import com.maxrave.spotify.auth.SpotifyAuth
import com.maxrave.spotify.model.response.spotify.CanvasResponse
import com.maxrave.spotify.model.response.spotify.ClientTokenResponse
import com.maxrave.spotify.model.response.spotify.PersonalTokenResponse
import com.maxrave.spotify.model.response.spotify.SpotifyLyricsResponse
import com.maxrave.spotify.model.response.spotify.search.SpotifySearchResponse
import com.maxrave.logger.Logger
import com.maxrave.spotify.library.SpotifyPage
import com.maxrave.spotify.library.SpotifyPathfinder
import com.maxrave.spotify.library.SpotifyPathfinderParser
import com.maxrave.spotify.library.SpotifyPlaylistEntry
import com.maxrave.spotify.library.SpotifyPlaylistRef
import com.maxrave.spotify.library.SpotifyRateLimitedException
import com.maxrave.spotify.library.SpotifyUnauthorizedException
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http

class Spotify {
    private val spotifyClient = SpotifyClient()
    private val spotifyAuth = SpotifyAuth(spotifyClient)

    /**
     * Remove proxy for client
     */
    fun removeProxy() {
        spotifyClient.proxy = null
    }

    /**
     * Set the proxy for client
     */
    fun setProxy(
        isHttp: Boolean,
        host: String,
        port: Int,
    ) {
        val verifiedHost =
            if (!host.contains("http")) {
                "http://$host"
            } else {
                host
            }
        runCatching {
            if (isHttp) ProxyBuilder.http("$verifiedHost:$port") else ProxyBuilder.socks(verifiedHost, port)
        }.onSuccess {
            spotifyClient.proxy = it
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * Get personal token using the standard method
     */
    suspend fun getPersonalToken(spdc: String) =
        runCatching {
            spotifyClient.getSpotifyLyricsToken(spdc).body<PersonalTokenResponse>()
        }

    /**
     * Get personal token using the more reliable TOTP-based method
     * This should be used when the standard method fails
     */
    suspend fun getPersonalTokenWithTotp(spdc: String) = spotifyAuth.refreshToken(spdc)

    suspend fun getClientToken() =
        runCatching {
            spotifyClient
                .getSpotifyClientToken()
                .body<ClientTokenResponse>()
        }

    suspend fun searchSpotifyTrack(
        query: String,
        authToken: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .searchSpotifyTrack(query, authToken, clientToken)
            .body<SpotifySearchResponse>()
    }

    suspend fun getSpotifyLyrics(
        trackId: String,
        token: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .getSpotifyLyrics(
                token = token,
                clientToken = clientToken,
                trackId,
            ).body<SpotifyLyricsResponse>()
    }

    /** A token without an account, enough to read public playlists. */
    suspend fun getAnonymousToken() = spotifyAuth.refreshToken("")

    /**
     * Every playlist in the user's library (own and followed, folders flattened) plus Liked Songs.
     * Needs a logged-in token.
     */
    suspend fun getLibraryPlaylists(
        authToken: String,
        clientToken: String,
    ): Result<List<SpotifyPlaylistRef>> =
        runCatching {
            val all = mutableListOf<SpotifyPlaylistRef>()
            var offset = 0
            while (true) {
                val page =
                    SpotifyPathfinderParser.parseLibrary(
                        pathfinder(SpotifyPathfinder.libraryBody(offset, SpotifyPathfinder.LIBRARY_PAGE_SIZE), authToken, clientToken),
                    )
                all += page.items
                // The count includes the rows dropped as non-playlists, so page by the request size.
                offset += SpotifyPathfinder.LIBRARY_PAGE_SIZE
                if (offset >= page.totalCount || page.totalCount == 0) break
            }
            all.distinctBy { it.uri }
        }

    /**
     * One page of a playlist, with its metadata. [uri] is `spotify:playlist:<id>`; public playlists
     * work with an anonymous token, private ones need the owner's.
     */
    suspend fun getPlaylistPage(
        uri: String,
        offset: Int,
        authToken: String,
        clientToken: String,
        limit: Int = SpotifyPathfinder.PAGE_SIZE,
    ): Result<Pair<SpotifyPlaylistRef, SpotifyPage<SpotifyPlaylistEntry>>> =
        runCatching {
            SpotifyPathfinderParser.parsePlaylist(
                pathfinder(SpotifyPathfinder.playlistBody(uri, offset, limit), authToken, clientToken),
                uri,
            )
        }

    /** One page of Liked Songs, newest first, as Spotify shows them. Needs a logged-in token. */
    suspend fun getLikedTracksPage(
        offset: Int,
        authToken: String,
        clientToken: String,
        limit: Int = SpotifyPathfinder.PAGE_SIZE,
    ): Result<SpotifyPage<SpotifyPlaylistEntry>> =
        runCatching {
            SpotifyPathfinderParser.parseLikedTracks(
                pathfinder(SpotifyPathfinder.likedTracksBody(offset, limit), authToken, clientToken),
            )
        }

    /**
     * Sends one query, waiting out 429s and transient 5xx with backoff. Retry-After is honoured up
     * to [MAX_WAIT_SECONDS]; a longer one fails fast with [SpotifyRateLimitedException] rather than
     * leaving the import hanging for hours.
     */
    private suspend fun pathfinder(
        body: String,
        authToken: String,
        clientToken: String,
    ): String {
        var attempt = 0
        while (true) {
            val response = spotifyClient.pathfinderQuery(body, authToken, clientToken)
            val status = response.status.value
            when {
                status == 401 || status == 403 -> throw SpotifyUnauthorizedException("Spotify refused the session ($status)")
                status == 429 || status >= 500 -> {
                    val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                    if (retryAfter != null && retryAfter > MAX_WAIT_SECONDS) throw SpotifyRateLimitedException(retryAfter)
                    if (attempt >= MAX_RETRIES) {
                        if (status == 429) throw SpotifyRateLimitedException(retryAfter ?: 0)
                        throw IllegalStateException("Spotify is not answering ($status)")
                    }
                    val waitSeconds = retryAfter ?: (2L shl attempt).coerceAtMost(MAX_WAIT_SECONDS)
                    Logger.w("SpotifyImport", "pathfinder $status, retry ${attempt + 1} in ${waitSeconds}s")
                    delay(waitSeconds * 1000)
                    attempt++
                }
                else -> return response.bodyAsText()
            }
        }
    }

    private companion object {
        const val MAX_RETRIES = 5
        const val MAX_WAIT_SECONDS = 60L
    }

    suspend fun getSpotifyCanvas(
        trackId: String,
        token: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient.getSpotifyCanvas(trackId, token, clientToken).body<CanvasResponse>()
    }
}