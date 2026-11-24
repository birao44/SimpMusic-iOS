package com.maxrave.data.repository

import com.maxrave.common.ITAG
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.QUALITY
import com.maxrave.common.VIDEO_QUALITY
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toSponsorSkipSegments
import com.maxrave.data.mapping.toTrack
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.extension.isBefore
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.plusSeconds
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.MediaType
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class StreamRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : StreamRepository {
    override suspend fun insertNewFormat(newFormat: NewFormatEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertNewFormat(newFormat)
        }

    override fun getNewFormat(videoId: String): Flow<NewFormatEntity?> = flow { emit(localDataSource.getNewFormat(videoId)) }.flowOn(Dispatchers.Main)

    override suspend fun getFormatFlow(videoId: String) = localDataSource.getNewFormatAsFlow(videoId)

    override fun getExtractSource(videoId: String): String? = youTube.getExtractSource(videoId)

    override suspend fun updateFormat(videoId: String) {
        localDataSource.getNewFormat(videoId)?.let { oldFormat ->
            Logger.d("Stream", "oldFormatExpired: ${oldFormat.expiredTime}")
            Logger.d("Stream", "now: ${now()}")
            Logger.d("Stream", "isExpired: ${oldFormat.expiredTime.isBefore(now())}")
            if (oldFormat.expiredTime.isBefore(now())) {
                youTube
                    .player(videoId)
                    .onSuccess { triple ->
                        val response = triple.second
                        localDataSource.updateNewFormat(
                            oldFormat.copy(
                                expiredTime = now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L),
                                playbackTrackingVideostatsPlaybackUrl =
                                    response.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingAtrUrl =
                                    response.playbackTracking?.atrUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                playbackTrackingVideostatsWatchtimeUrl =
                                    response.playbackTracking?.videostatsWatchtimeUrl?.baseUrl?.replace(
                                        "https://s.youtube.com",
                                        "https://music.youtube.com",
                                    ),
                                cpn = triple.first,
                            ),
                        )
                        Logger.w("UpdateFormat", "Updated format for $videoId")
                    }.onFailure { throwable ->
                        Logger.e("UpdateFormat", "Error: ${throwable.message}")
                    }
            }
        }
    }

    override fun getStream(
        dataStoreManager: DataStoreManager,
        videoId: String,
        isDownloading: Boolean,
        isVideo: Boolean,
        muxed: Boolean,
    ): Flow<String?> =
        flow {
            val wantedItag =
                if (isDownloading) {
                    QUALITY.itagOf(dataStoreManager.downloadQuality.first())
                } else {
                    QUALITY.itagOf(dataStoreManager.quality.first())
                }
            // AVPlayer has no Opus/WebM decoder: on iOS every quality maps onto its AAC counterpart.
            val itag = if (streamsForAvPlayer) aacItagFor(wantedItag) else wantedItag
            val videoItag =
                if (!muxed) {
                    VIDEO_QUALITY.itags.getOrNull(
                        VIDEO_QUALITY.items.indexOf(
                            if (isDownloading) {
                                dataStoreManager.videoDownloadQuality.first()
                            } else {
                                dataStoreManager.videoQuality.first()
                            },
                        ),
                    )
                        ?: ITAG.VIDEO_360P
                } else {
                    ITAG.MUXED_360P
                }
            youTube
                .player(
                    videoId,
                    noLogIn = muxed,
                    preferredAudioLanguage = dataStoreManager.preferredAudioLanguage.first(),
                )
                .onSuccess { data ->
                    val response = data.second
                    val isLive = response.videoDetails?.isLive == true
                    LiveStreamRegistry.recordLiveStatus(videoId, isLive)
                    // The song row follows, so the LIVE badge and the download gate know too —
                    // including when a broadcast has ended and its recording downloads normally.
                    localDataSource.updateSongLiveStatus(videoId, isLive)
                    if (isLive && isDownloading) {
                        // Nothing to save: handing an endless HLS playlist to a downloader stores
                        // the playlist text as if it were the song. Emitting nothing fails the
                        // download where the caller resolves its URL.
                        Logger.i("Stream", "$videoId: live stream, refusing download")
                        return@onSuccess
                    }
                    if (isLive) {
                        // A live broadcast plays from its HLS playlist alone, so none of the itag
                        // choice below applies. Nothing about it goes into the format table either:
                        // the playlist URL is good for a few hours and the broadcast may be over by
                        // the next run, so every play resolves it afresh — and the players learn
                        // that it is live from LiveStreamRegistry, recorded just above.
                        val liveHlsUrl = response.streamingData?.hlsManifestUrl
                        Logger.i("Stream", "$videoId: live stream, HLS URL ${if (liveHlsUrl == null) "missing" else "found"}")
                        emit(liveHlsUrl)
                        return@onSuccess
                    }
                    Logger.d(
                        "Stream",
                        response.streamingData
                            ?.formats
                            ?.map { it.itag }
                            .toString() + " " +
                            response.streamingData
                                ?.adaptiveFormats
                                ?.map { it.itag }
                                .toString(),
                    )
                    val formatList = mutableListOf<PlayerResponse.StreamingData.Format>()
                    formatList.addAll(
                        response.streamingData?.formats?.filter { it.url.isNullOrEmpty().not() } ?: emptyList(),
                    )
                    formatList.addAll(
                        response.streamingData?.adaptiveFormats?.filter { it.url.isNullOrEmpty().not() }
                            ?: emptyList(),
                    )
                    // AVPlayer plays one URL per item (docs/ios-player-bridge.md §3), so a video it
                    // plays must carry its own audio: the HLS manifest (pseudo-itag 96, added by
                    // YouTube.newPipePlayer), else the progressive H.264/AAC 360p MP4. Playback
                    // only: a download keeps the adaptive choice.
                    val avPlayerVideo = streamsForAvPlayer && !isDownloading && !muxed
                    val videoFormat =
                        if (avPlayerVideo) {
                            formatList.find { it.itag == ITAG_HLS_MANIFEST }
                                ?: formatList.find { it.itag == ITAG.MUXED_360P }
                        } else {
                            formatList.find { it.itag == videoItag }
                                ?: formatList.find { it.itag == ITAG.VIDEO_720P }
                                ?: formatList.find { it.itag == ITAG.VIDEO_360P }
                                ?: formatList.find { !it.isAudio && it.url.isNullOrEmpty().not() }
                        }
                    val audioTwinItag = ITAG.highQualityTwinOf(itag)
                    val audioFormat =
                        if (streamsForAvPlayer) {
                            // Never the Opus twin and never "any audio": only AAC in MP4 plays.
                            formatList.find { it.itag == itag }
                                ?: formatList.find { it.itag == ITAG_AUDIO_AAC_MEDIUM }
                                ?: formatList.find { it.isAudio && it.mimeType.startsWith("audio/mp4") }
                        } else {
                            formatList.find { it.itag == itag } ?: if (audioTwinItag != null) {
                                formatList.find { it.itag == audioTwinItag }
                            } else {
                                formatList.find { it.isAudio && it.url.isNullOrEmpty().not() }
                            }
                        }
                    var format =
                        if (isVideo) {
                            videoFormat
                        } else {
                            audioFormat
                        }
                    // No AAC stream in the response: AVPlayer plays the HLS manifest natively, which
                    // beats handing it whatever format happens to be last (usually WebM).
                    val hlsManifestUrl = response.streamingData?.hlsManifestUrl
                    val avPlayerHlsFallback = streamsForAvPlayer && !isVideo && !muxed && format == null && hlsManifestUrl != null
                    // Emitted as is: the manifest URL is path-style, so "&cpn=" cannot be appended.
                    val avPlayerVideoHls = avPlayerVideo && isVideo && format?.itag == ITAG_HLS_MANIFEST && hlsManifestUrl != null
                    // No muxed stream is no video for AVPlayer; any other format would play silent.
                    if (format == null && !avPlayerHlsFallback && !(avPlayerVideo && isVideo)) {
                        format = formatList.lastOrNull { it.url.isNullOrEmpty().not() }
                    }
                    if (muxed) {
                        format = formatList
                            .filter {
                                val url = it.url
                                url != null && youTube.isManifestUrl(url)
                            }.maxByOrNull { it.width ?: 0 } ?: formatList.find { it.itag == videoItag }
                    }
                    // One line per resolved stream and never the URL: a googlevideo URL carries the
                    // listener's IP address.
                    Logger.i(
                        "Stream",
                        "$videoId: ${data.third}, itag ${format?.itag} ${format?.mimeType?.substringBefore(';')}" +
                            (if (muxed) ", HLS" else "") +
                            ", expires in ${response.streamingData?.expiresInSeconds}s",
                    )
                    val durationSecond = response.videoDetails?.lengthSeconds?.toIntOrNull()
                    // AutoMix metadata from Tidal official API
                    var tidalBpm: Int? = null
                    var tidalMusicKey: String? = null
                    var tidalKeyScale: String? = null
                    if (!isVideo && durationSecond != null && data.third == MediaType.Song) {
                        val title = response.videoDetails?.title ?: ""
                        val author = response.videoDetails?.author ?: ""
                        val q =
                            "$title $author"
                                .replace(
                                    Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                                    " ",
                                ).replace(
                                    Regex("( và | & | и | e | und |, |和| dan)"),
                                    " ",
                                ).replace("  ", " ")
                                .replace(Regex("([()])"), "")
                                .replace(".", " ")
                                .replace("  ", " ")
                        Logger.d("Stream", "Search Tidal metadata for: $q")
                        youTube
                            .searchTidalMetadata(q, durationSecond)
                            .onSuccess { metadata ->
                                Logger.d("Stream", "Tidal metadata: $metadata")
                                tidalBpm = metadata.bpm
                                tidalMusicKey = metadata.musicKey
                                tidalKeyScale = metadata.keyScale
                            }.onFailure {
                                Logger.e("Stream", "Tidal metadata error: ${it.message}", it)
                            }
                    }
                    insertNewFormat(
                        NewFormatEntity(
                            // An AVPlayer video row (itag 96/18) must not overwrite the audio row.
                            videoId =
                                if (VIDEO_QUALITY.itags.contains(format?.itag) || (avPlayerVideo && isVideo)) {
                                    "${MERGING_DATA_TYPE.VIDEO}$videoId"
                                } else {
                                    videoId
                                },
                            itag = format?.itag ?: itag,
                            mimeType =
                                Regex("""([^;]+);\s*codecs=["']([^"']+)["']""")
                                    .find(
                                        format?.mimeType ?: "",
                                    )?.groupValues
                                    ?.getOrNull(1) ?: format?.mimeType ?: "",
                            codecs =
                                Regex("""([^;]+);\s*codecs=["']([^"']+)["']""")
                                    .find(
                                        format?.mimeType ?: "",
                                    )?.groupValues
                                    ?.getOrNull(2) ?: format?.mimeType ?: "",
                            bitrate = format?.bitrate,
                            sampleRate = format?.audioSampleRate,
                            contentLength = format?.contentLength,
                            loudnessDb =
                                response.playerConfig
                                    ?.audioConfig
                                    ?.loudnessDb
                                    ?.toFloat(),
                            lengthSeconds = response.videoDetails?.lengthSeconds?.toInt(),
                            playbackTrackingVideostatsPlaybackUrl =
                                response.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.replace(
                                    "https://s.youtube.com",
                                    "https://music.youtube.com",
                                ),
                            playbackTrackingAtrUrl =
                                response.playbackTracking?.atrUrl?.baseUrl?.replace(
                                    "https://s.youtube.com",
                                    "https://music.youtube.com",
                                ),
                            playbackTrackingVideostatsWatchtimeUrl =
                                response.playbackTracking?.videostatsWatchtimeUrl?.baseUrl?.replace(
                                    "https://s.youtube.com",
                                    "https://music.youtube.com",
                                ),
                            cpn = data.first,
                            expiredTime = now().plusSeconds(response.streamingData?.expiresInSeconds?.toLong() ?: 0L),
                            audioUrl = if (muxed || avPlayerHlsFallback) hlsManifestUrl else format?.url,
                            videoUrl = if (muxed) response.streamingData?.hlsManifestUrl else videoFormat?.url,
                            bpm = tidalBpm,
                            musicKey = tidalMusicKey,
                            keyScale = tidalKeyScale,
                        ),
                    )
                    if (data.first != null) {
                        emit(
                            if (muxed || avPlayerHlsFallback || avPlayerVideoHls) {
                                hlsManifestUrl
                            } else {
                                format?.url?.let { url ->
                                    if (youTube.isManifestUrl(url)) {
                                        url.plus("&cpn=${data.first}")
                                    } else {
                                        url.plus("&cpn=${data.first}").withWholeFileRange(format.contentLength)
                                    }
                                }
                            },
                        )
                    } else {
                        emit(
                            if (muxed || avPlayerHlsFallback) {
                                hlsManifestUrl
                            } else {
                                format?.url?.let { url ->
                                    if (youTube.isManifestUrl(url)) {
                                        url
                                    } else {
                                        url.withWholeFileRange(format.contentLength)
                                    }
                                }
                            },
                        )
                    }
                }.onFailure {
                    it.printStackTrace()
                    Logger.e("Stream", "Error: ${it.message}")
                    emit(null)
                }
        }.flowOn(Dispatchers.IO)

    override fun initPlayback(
        playback: String,
        atr: String,
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Pair<Int, Float>> =
        flow {
            youTube
                .initPlayback(playback, atr, watchTime, cpn, playlistId)
                .onSuccess { response ->
                    emit(response)
                }.onFailure {
                    Logger.e("InitPlayback", "Error: ${it.message}")
                    emit(Pair(0, 0f))
                }
        }.flowOn(Dispatchers.IO)

    override fun updateWatchTimeFull(
        watchTime: String,
        cpn: String,
        playlistId: String?,
    ): Flow<Int> =
        flow {
            runCatching {
                youTube
                    .updateWatchTimeFull(watchTime, cpn, playlistId)
                    .onSuccess { response ->
                        emit(response)
                    }.onFailure {
                        it.printStackTrace()
                        emit(0)
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun updateWatchTime(
        playbackTrackingVideostatsWatchtimeUrl: String,
        watchTimeList: ArrayList<Float>,
        cpn: String,
        playlistId: String?,
    ): Flow<Int> =
        flow {
            runCatching {
                youTube
                    .updateWatchTime(
                        playbackTrackingVideostatsWatchtimeUrl,
                        watchTimeList,
                        cpn,
                        playlistId,
                    ).onSuccess { response ->
                        emit(response)
                    }.onFailure {
                        it.printStackTrace()
                        emit(0)
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSkipSegments(videoId: String): Flow<Resource<List<SponsorSkipSegments>>> =
        flow {
            youTube
                .getSkipSegments(videoId)
                .onSuccess { data ->
                    emit(Resource.Success(data.map { it.toSponsorSkipSegments() }))
                }.onFailure {
                    emit(Resource.Error(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getFullMetadata(videoId: String): Flow<Resource<Track>> =
        flow {
            Logger.w("getFullMetadata", "videoId: $videoId")
            youTube
                .getFullMetadata(videoId)
                .onSuccess {
                    emit(Resource.Success(it.toTrack()))
                }.onFailure {
                    Logger.e("getFullMetadata", "Error: ${it.message}")
                    emit(Resource.Error(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun is403Url(url: String) = flow { emit(youTube.is403Url(url)) }.flowOn(Dispatchers.IO)

    override suspend fun invalidateFormat(videoId: String) {
        withContext(Dispatchers.IO) {
            localDataSource.getNewFormat(videoId)?.let { format ->
                Logger.d("Stream", "Invalidating cached format for $videoId")
                localDataSource.updateNewFormat(
                    format.copy(expiredTime = now().plusSeconds(-1)),
                )
            }
        }
    }
}
/** The HLS manifest's pseudo-itag in the format list (see YouTube.newPipePlayer). */
private const val ITAG_HLS_MANIFEST = 96

/** 140: AAC at 128 kbps, the stream every account gets. Not a user-facing quality, so not in ITAG. */
private const val ITAG_AUDIO_AAC_MEDIUM = 140

/** The AAC stream that stands in for [itag] where Opus cannot be decoded. */
private fun aacItagFor(itag: Int?): Int =
    when (itag) {
        ITAG.AUDIO_OPUS_HIGH, ITAG.AUDIO_AAC_HIGH -> ITAG.AUDIO_AAC_HIGH
        else -> ITAG_AUDIO_AAC_MEDIUM
    }

/**
 * ExoPlayer and mpv read the whole file through one `range=` query parameter. AVPlayer must not get
 * it: it seeks with HTTP Range headers of its own, and a URL that already pins the range answers
 * every one of them with the full file.
 */
private fun String.withWholeFileRange(contentLength: Long?): String =
    if (streamsForAvPlayer) this else plus("&range=0-${contentLength ?: 10000000}")
