package com.maxrave.spotify

import com.maxrave.ktorext.curl.CurlLogger
import com.maxrave.ktorext.encoding.brotli
import com.maxrave.ktorext.getEngine
import com.maxrave.spotify.model.body.CanvasBody
import com.maxrave.spotify.model.body.SpotifyClientBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.userAgent
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.random.Random

class SpotifyClient {
    private var spotifyClient = createSpotifyClient()
    private var jsonClient =
        createSpotifyClient(
            onlyJson = true,
        )

    // Playlist import pages through thousands of tracks; logging every response body would flood the
    // log, so this client has no Logging/CurlLogger. Created on first use.
    private var pathfinderClient: HttpClient? = null

    var proxy: ProxyConfig? = null
        set(value) {
            if (field == value) return
            field = value
            spotifyClient.close()
            spotifyClient = createSpotifyClient()
            pathfinderClient?.close()
            pathfinderClient = null
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createSpotifyClient(
        onlyJson: Boolean = false,
        quiet: Boolean = false,
    ) =
        HttpClient(getEngine()) {
            followRedirects = true
            expectSuccess = false
            // Every rejected Spotify token lands in the app log in one place. Host and path only: the
            // headers carry the tokens. Ktor's own Logger is imported here, hence the full name.
            HttpResponseValidator {
                validateResponse { response ->
                    if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                        val url = response.request.url
                        com.maxrave.logger.Logger.w("Auth", "Spotify: ${url.host}${url.encodedPath} answered ${response.status.value}")
                    }
                }
            }
            if (!quiet) {
                install(HttpCache)
                install(Logging) {
                    logger = Logger.DEFAULT
                    level = LogLevel.ALL
                }
                install(CurlLogger) {
                    logger = { Logger.DEFAULT.log(it) }
                }
            }
            install(HttpSend) {
                maxSendCount = 100
            }
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
            install(ContentNegotiation) {
                if (!onlyJson) {
                    register(
                        ContentType.Text.Plain,
                        KotlinxSerializationConverter(
                            Json {
                                prettyPrint = true
                                isLenient = true
                                ignoreUnknownKeys = true
                                explicitNulls = false
                                encodeDefaults = true
                            },
                        ),
                    )
                    protobuf(
                        ProtoBuf {
                            encodeDefaults = true
                        },
                    )
                }
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(ContentEncoding) {
                brotli(1.0F)
                gzip(0.9F)
                deflate(0.8F)
            }
            defaultRequest {
                url("https://api.spotify.com")
            }
            if (proxy != null) {
                engine {
                    proxy = this@SpotifyClient.proxy
                }
            }
        }

    /**
     * Thanks to @Thereallo1026 and @misiektoja for providing the latest TOTP secret
     */
    suspend fun getSpotifyLastestTotpSecret() =
        spotifyClient.get("https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json") {
            userAgent(USER_AGENT)
            contentType(ContentType.Application.Json)
        }

    suspend fun getSpotifyServerTime(spdc: String) =
        spotifyClient.get("https://open.spotify.com/api/server-time") {
            userAgent(USER_AGENT)
            // A blank sp_dc asks for an anonymous token (public playlists only).
            if (spdc.isNotBlank()) {
                header(
                    "Cookie",
                    "sp_dc=$spdc",
                )
            }
            header("App-platform", "WebPlayer")
            header("Spotify-App-Version", "1.2.61.20.g3b4cd5b2")
            header("Accept", "application/json")
            header("Origin", "https://open.spotify.com")
            header("Referer", "https://open.spotify.com/")
        }

    suspend fun getSpotifyAccessToken(
        spdc: String,
        otpValue: String,
        reason: String = "init",
        sTime: String,
        cTime: String,
        totpVersion: Int,
    ) = spotifyClient.get("https://open.spotify.com/api/token") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        if (spdc.isNotBlank()) {
            header(
                "Cookie",
                "sp_dc=$spdc",
            )
        }
        parameter("reason", reason)
        parameter("productType", "mobile-web-player")
        parameter("totp", otpValue)
        parameter("totpServer", otpValue)
        parameter("totpVer", totpVersion)
        header("Accept", "application/json")
        header("Origin", "https://open.spotify.com")
        header("Referer", "https://open.spotify.com/")
    }

    suspend fun getSpotifyLyricsToken(spdc: String) =
        spotifyClient.get("https://open.spotify.com/get_access_token?reason=transport&productType=web_player") {
            userAgent(USER_AGENT)
            contentType(ContentType.Application.Json)
            header("Cookie", "sp_dc=$spdc")
        }

    suspend fun getSpotifyLyrics(
        token: String,
        clientToken: String,
        trackId: String,
    ) = spotifyClient.get("https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId?format=json&vocalRemoval=false&market=from_token") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $token")
        header("Client-Token", clientToken)
        header("App-platform", "WebPlayer")
    }

    suspend fun searchSpotifyTrack(
        q: String,
        authToken: String,
        clientToken: String,
    ) = spotifyClient.get("https://api-partner.spotify.com/pathfinder/v1/query?operationName=searchTracks") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $authToken")
        header("Client-Token", clientToken)
        header(
            HttpHeaders
                .AcceptEncoding,
            "gzip, deflate, br",
        )
        val variable =
            "{\"searchTerm\":\"${q}\",\"offset\":0,\"limit\":3,\"numberOfTopResults\":3,\"includeAudiobooks\":true,\"includePreReleases\":false}"
        val sha = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
        parameter(
            "variables",
            variable,
        )
        parameter(
            "extensions",
            "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"${sha}\"}}",
        )
    }
    // {"searchTerm":"trình+hieuthuhai","offset":0,"limit":20,"numberOfTopResults":20,"includeAudiobooks":true,"includePreReleases":false}
    // {"persistedQuery":{"version":1,"sha256Hash":"e4ed1f91a2cc5415befedb85acf8671dc1a4bf3ca1a5b945a6386101a22e28a6"}}

    suspend fun getSpotifyCanvas(
        trackId: String,
        token: String,
        clientToken: String,
    ) = spotifyClient.post("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases") {
        headers {
            append(HttpHeaders.Accept, "application/protobuf")
            append(HttpHeaders.ContentType, "application/protobuf")
            append(
                HttpHeaders
                    .AcceptEncoding,
                "gzip, deflate, br",
            )
            append(HttpHeaders.Authorization, "Bearer $token")
            append("Client-Token", clientToken)
            append(HttpHeaders.UserAgent, "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)")
        }
        setBody(
            CanvasBody(
                tracks =
                    listOf(
                        CanvasBody.Track(
                            track_uri = "spotify:track:$trackId",
                        ),
                    ),
            ),
        )
    }

    /**
     * One persisted GraphQL query against the web player's own API (`api-partner.spotify.com`), the
     * same POST the web player sends. [body] is the full JSON body (variables, operationName and the
     * persisted-query hash); see [com.maxrave.spotify.library.SpotifyPathfinder].
     */
    suspend fun pathfinderQuery(
        body: String,
        authToken: String,
        clientToken: String,
    ): HttpResponse {
        val client = pathfinderClient ?: createSpotifyClient(onlyJson = true, quiet = true).also { pathfinderClient = it }
        return client.post("https://api-partner.spotify.com/pathfinder/v2/query") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $authToken")
                append("Client-Token", clientToken)
                append("App-Platform", "WebPlayer")
                append(HttpHeaders.Accept, "application/json")
                append(HttpHeaders.UserAgent, USER_AGENT)
                append(HttpHeaders.Origin, "https://open.spotify.com")
                append(HttpHeaders.Referrer, "https://open.spotify.com/")
            }
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }

    suspend fun getSpotifyClientToken() =
        jsonClient.post("https://clienttoken.spotify.com/v1/clienttoken") {
            headers {
                append(HttpHeaders.Accept, "application/json")
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.AcceptEncoding, "gzip, deflate, br")
                append(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0",
                )
            }
            setBody(
                SpotifyClientBody(
                    client_data = SpotifyClientBody.ClientData(),
                ),
            )
        }

    fun getRandomUserAgent(): String {
        val macOSVersion = "${Random.nextInt(11, 15)}_${Random.nextInt(4, 9)}"
        val webKitVersion = "${Random.nextInt(530, 537)}.${Random.nextInt(30, 37)}"
        val chromeVersion = "${Random.nextInt(80, 105)}.0.${Random.nextInt(3000, 4500)}.${Random.nextInt(60, 125)}"
        val safariVersion = "${Random.nextInt(530, 537)}.${Random.nextInt(30, 36)}"

        return "Mozilla/5.0 (Macintosh; Intel Mac OS X $macOSVersion) AppleWebKit/$webKitVersion (KHTML, like Gecko) Chrome/$chromeVersion Safari/$safariVersion"
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.157 Safari/537.36"
    }
}