package com.maxrave.data.download

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.isLive
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.min

private const val TAG = "IosDownloadHandler"

/** Songs downloading at once; the rest wait their turn. */
private const val PARALLEL_TRACKS = 2

/** Ranged requests in flight per song. One connection runs at about 2x realtime. */
private const val PARALLEL_CHUNKS = 4

/** Size of each ranged request. An un-ranged GET is throttled by googlevideo. */
private const val CHUNK_BYTES = 2L * 1024 * 1024

private const val CHUNK_ATTEMPTS = 4

/**
 * Downloads on iOS: plain files, no Media3 and no ffmpeg.
 *
 * Design, compared with Desktop's `DownloadUtils` (the file-based reference):
 * - The stream is the one AVPlayer plays — AAC in MP4 (itag 140/141), resolved through
 *   [StreamRepository.getStream] with `isDownloading = true` — not `YouTube.download`, which picks
 *   the highest-bitrate audio (Opus/WebM, unplayable on iOS) and then needs ffmpeg.
 * - It is fetched in ranged chunks, [PARALLEL_CHUNKS] at a time, appended in order to
 *   `<id>.part.m4a`, so a partial file is always a clean prefix and a restart resumes from its size.
 * - Finished, it is passthrough-exported into a tagged `.m4a` (see [IosAudioTagger]) or, if that
 *   fails, renamed as is. Only then is the song marked downloaded.
 * - [downloadTrack] queues and returns at once (as Android's DownloadService does), so a screen
 *   that starts "download all" and is then closed does not cancel the batch with its viewModelScope.
 *
 * State: the song row's `downloadState` is the durable truth the Library and playlists read;
 * [downloadTask] and [downloads] mirror it for the screens that observe this handler. Both are
 * rebuilt from the database and the files on launch ([restore]).
 */
internal class IosDownloadHandler(
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val songRepository: SongRepository,
) : DownloadHandler {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val slots = Semaphore(PARALLEL_TRACKS)

    private val _downloads = MutableStateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>(emptyMap())
    override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>> get() = _downloads

    private val _downloadTask = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val downloadTask: StateFlow<Map<String, Int>> get() = _downloadTask

    /** Running or queued downloads, by videoId. Replaced whole, so updates are atomic. */
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    init {
        scope.launch { restore() }
    }

    // ===== Public API =====

    override suspend fun downloadTrack(
        videoId: String,
        title: String,
        thumbnail: String,
    ) {
        if (jobs.value.containsKey(videoId)) return
        val song = songRepository.getSongById(videoId).firstOrNull()
        if (song == null) {
            Logger.w(TAG, "$videoId is not in the database, not downloading")
            return
        }
        if (song.isLive) {
            Logger.i(TAG, "$videoId is a live stream, not downloading")
            songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
            publish(videoId, null)
            return
        }
        if (IosDownloadFiles.isDownloaded(videoId)) {
            songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)
            publish(videoId, DownloadState.STATE_DOWNLOADED)
            return
        }
        enqueue(song, thumbnail)
    }

    override fun removeDownload(videoId: String) {
        val job = jobs.value[videoId]
        scope.launch {
            job?.cancelAndJoin()
            IosDownloadFiles.deleteAll(videoId)
            publish(videoId, null)
            songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
        }
    }

    override fun removeAllDownloads() {
        val running = jobs.value
        _downloadTask.value = emptyMap()
        _downloads.value = emptyMap()
        scope.launch {
            running.values.forEach { it.cancelAndJoin() }
            val onDisk = IosDownloadFiles.downloadedIds() + IosDownloadFiles.partialIds()
            IosDownloadFiles.deleteEverything()
            (onDisk + running.keys).toSet().forEach { videoId ->
                songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
            }
        }
    }

    // ===== Queue =====

    private suspend fun enqueue(
        song: SongEntity,
        thumbnail: String,
    ) {
        val videoId = song.videoId
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    slots.withPermit { runDownload(song, thumbnail) }
                } finally {
                    jobs.update { it - videoId }
                    updateBackgroundTask()
                }
            }
        var added = false
        jobs.update { current ->
            if (current.containsKey(videoId)) {
                added = false
                current
            } else {
                added = true
                current + (videoId to job)
            }
        }
        if (!added) {
            job.cancel()
            return
        }
        songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADING)
        publish(videoId, DownloadState.STATE_DOWNLOADING)
        updateBackgroundTask()
        job.start()
    }

    private suspend fun runDownload(
        song: SongEntity,
        thumbnail: String,
    ) {
        val videoId = song.videoId
        try {
            fetchAudio(song)
            currentCoroutineContext().ensureActive()
            finish(song, thumbnail)
            currentCoroutineContext().ensureActive()
            songRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)
            publish(videoId, DownloadState.STATE_DOWNLOADED)
            Logger.i(TAG, "$videoId downloaded (${IosDownloadFiles.sizeOf(IosDownloadFiles.finalPath(videoId))} bytes)")
        } catch (e: CancellationException) {
            // removeDownload / removeAllDownloads own the files and the state from here.
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "$videoId download failed: ${e.message}")
            // The part file stays: the next attempt in this run resumes from it. It is cleared on
            // the next launch unless the song is queued again by then.
            songRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
            publish(videoId, DownloadState.STATE_NOT_DOWNLOADED)
        }
    }

    // ===== Transfer =====

    private suspend fun resolveUrl(videoId: String): String {
        val url =
            streamRepository
                .getStream(dataStoreManager, videoId, isDownloading = true, isVideo = false)
                .lastOrNull()
                ?: throw IllegalStateException("No stream for $videoId")
        // AVPlayer can fall back to an HLS playlist when no AAC file exists; that cannot be saved
        // as one file here.
        if (url.contains(".m3u8") || url.contains("manifest")) throw IllegalStateException("Only HLS is available for $videoId")
        return url
    }

    private suspend fun fetchAudio(song: SongEntity) {
        val videoId = song.videoId
        IosDownloadFiles.ensureDirectory()
        var url = resolveUrl(videoId)
        val format = streamRepository.getNewFormat(videoId).lastOrNull()
        val durationMs =
            DUR_PARAM.find(url)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { (it * 1000.0).toLong() }
                ?: format?.lengthSeconds?.takeIf { it > 0 }?.let { (it + 1) * 1000L }
                ?: 0L
        var total =
            CLEN_PARAM.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: format?.contentLength?.takeIf { it > 0 }

        val part = IosDownloadFiles.partPath(videoId)
        if (total == null || IosDownloadFiles.partExpectedLength(videoId) != total) {
            // Unknown length, another quality, or no part yet: start from zero.
            IosDownloadFiles.deletePart(videoId)
        }
        var offset = IosDownloadFiles.sizeOf(part)

        if (total == null) {
            // Learn the length from the first chunk's Content-Range.
            val first = fetchChunk(url, 0, CHUNK_BYTES - 1) { url = resolveUrl(videoId); url }
            total = first.totalLength ?: throw IllegalStateException("No length for $videoId")
            IosDownloadFiles.append(part, first.body)
            offset = first.body.size.toLong()
        }
        IosDownloadFiles.writePartExpectedLength(videoId, total)
        if (offset > 0) Logger.i(TAG, "$videoId resuming at $offset of $total bytes")

        var lastLoggedTenth = -1L
        while (offset < total) {
            currentCoroutineContext().ensureActive()
            val ranges =
                (0 until PARALLEL_CHUNKS)
                    .map { i -> offset + i * CHUNK_BYTES }
                    .filter { it < total }
                    .map { start -> start to min(start + CHUNK_BYTES, total) - 1 }
            val bodies =
                coroutineScope {
                    ranges
                        .map { (from, to) ->
                            async { fetchChunk(url, from, to) { url = resolveUrl(videoId); url }.body }
                        }.awaitAll()
                }
            for ((index, body) in bodies.withIndex()) {
                val expected = ranges[index].second - ranges[index].first + 1
                if (body.size.toLong() != expected) throw IllegalStateException("Short read for $videoId: ${body.size} of $expected")
                IosDownloadFiles.append(part, body)
                offset += body.size
            }
            val tenth = offset * 10 / total
            if (tenth != lastLoggedTenth) {
                lastLoggedTenth = tenth
                Logger.d(TAG, "$videoId ${tenth * 10}% ($offset / $total)")
            }
        }
        if (IosDownloadFiles.sizeOf(part) != total) {
            IosDownloadFiles.deletePart(videoId)
            throw IllegalStateException("Size mismatch for $videoId")
        }
        IosDownloadFiles.writeDurationMs(videoId, durationMs)
    }

    /**
     * One ranged GET with retries. A 403/410 means the URL expired (they last ~6 h, a long queue
     * can outlive one): [refreshUrl] resolves a fresh one before the next try.
     */
    private suspend fun fetchChunk(
        url: String,
        from: Long,
        to: Long,
        refreshUrl: suspend () -> String,
    ): HttpChunk {
        var current = url
        var lastError: Exception? = null
        repeat(CHUNK_ATTEMPTS) { attempt ->
            try {
                val chunk = IosHttp.get(current, from, to)
                when (chunk.status) {
                    206, 200 -> {
                        // A 200 is the whole file: keep only the range asked for.
                        if (chunk.status == 200 && (from > 0 || chunk.body.size.toLong() > to - from + 1)) {
                            val end = min(chunk.body.size.toLong(), to + 1).toInt()
                            if (from.toInt() < end) {
                                return HttpChunk(206, chunk.body.copyOfRange(from.toInt(), end), chunk.body.size.toLong())
                            }
                        }
                        return chunk
                    }
                    403, 410 -> {
                        lastError = IllegalStateException("HTTP ${chunk.status}")
                        current = refreshUrl()
                    }
                    else -> lastError = IllegalStateException("HTTP ${chunk.status}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            delay(1000L * (attempt + 1))
        }
        throw lastError ?: IllegalStateException("Download failed")
    }

    /** Part file → tagged `<id>.m4a`, or the untagged stream renamed if the export fails. */
    private suspend fun finish(
        song: SongEntity,
        thumbnail: String,
    ) {
        val videoId = song.videoId
        val part = IosDownloadFiles.partPath(videoId)
        val tagged = IosDownloadFiles.tagPath(videoId)
        val final = IosDownloadFiles.finalPath(videoId)
        val artwork = fetchArtwork(thumbnail.ifBlank { song.thumbnails.orEmpty() })
        val exported =
            runCatching {
                IosAudioTagger.exportTagged(
                    sourcePath = part,
                    destinationPath = tagged,
                    title = song.title,
                    artist = song.artistName?.joinToString(", "),
                    album = song.albumName,
                    artworkJpeg = artwork,
                )
            }.getOrElse {
                if (it is CancellationException) throw it
                Logger.w(TAG, "Tagging $videoId threw: ${it.message}")
                false
            }
        if (exported) {
            IosDownloadFiles.move(tagged, final)
        } else {
            Logger.w(TAG, "$videoId kept untagged")
            IosDownloadFiles.move(part, final)
        }
        IosDownloadFiles.deletePart(videoId)
        IosDownloadFiles.excludeFromBackup(final)
    }

    /** Cover art for the tag; null when there is none or it cannot be fetched. Never fails the download. */
    private suspend fun fetchArtwork(thumbnail: String): ByteArray? {
        if (!thumbnail.startsWith("http")) return null
        // YouTube Music thumbnails are sized in the URL; ask for one fit for a lock screen.
        val url = thumbnail.replace(Regex("""=w\d+-h\d+"""), "=w544-h544")
        return runCatching {
            IosHttp.get(url).takeIf { it.status == 200 && it.body.isNotEmpty() }?.body
        }.getOrNull()
    }

    // ===== Launch =====

    /**
     * Rebuilds the in-memory state after a launch and repairs what an earlier run left behind:
     * - a song marked downloaded whose file is gone (a reinstall, a restored backup) is unmarked;
     * - a song that was downloading is queued again and resumes from its part file;
     * - part files nobody is downloading any more, and finished files no song claims, are deleted.
     */
    private suspend fun restore() {
        runCatching {
            IosDownloadFiles.ensureDirectory()
            IosDownloadFiles.deleteStaleTagFiles()
            val onDisk = IosDownloadFiles.downloadedIds().toSet()

            val markedDownloaded = songRepository.getDownloadedSongs().firstOrNull().orEmpty()
            val keep = mutableSetOf<String>()
            for (song in markedDownloaded) {
                if (song.videoId in onDisk) {
                    keep += song.videoId
                    publish(song.videoId, DownloadState.STATE_DOWNLOADED)
                } else {
                    Logger.w(TAG, "${song.videoId} was marked downloaded but has no file")
                    songRepository.updateDownloadState(song.videoId, DownloadState.STATE_NOT_DOWNLOADED)
                }
            }

            val interrupted =
                (songRepository.getDownloadingSongs().firstOrNull().orEmpty() + songRepository.getPreparingSongs().firstOrNull().orEmpty())
                    .distinctBy { it.videoId }
            val resuming = mutableSetOf<String>()
            for (song in interrupted) {
                if (song.videoId in onDisk) {
                    keep += song.videoId
                    songRepository.updateDownloadState(song.videoId, DownloadState.STATE_DOWNLOADED)
                    publish(song.videoId, DownloadState.STATE_DOWNLOADED)
                } else {
                    resuming += song.videoId
                }
            }

            IosDownloadFiles.partialIds().filter { it !in resuming }.forEach { IosDownloadFiles.deletePart(it) }
            onDisk.filter { it !in keep }.forEach {
                Logger.i(TAG, "Deleting orphaned download $it")
                IosDownloadFiles.deleteAll(it)
            }

            for (song in interrupted.filter { it.videoId in resuming }) {
                Logger.i(TAG, "Resuming ${song.videoId}")
                downloadTrack(song.videoId, song.title, song.thumbnails.orEmpty())
            }
        }.onFailure { Logger.e(TAG, "Restoring downloads failed: ${it.message}") }
    }

    // ===== State =====

    private fun publish(
        videoId: String,
        state: Int?,
    ) {
        _downloadTask.update { if (state == null) it - videoId else it + (videoId to state) }
        val handlerState =
            when (state) {
                null -> null
                DownloadState.STATE_DOWNLOADED -> DownloadHandler.STATE_COMPLETED
                DownloadState.STATE_DOWNLOADING, DownloadState.STATE_PREPARING -> DownloadHandler.STATE_DOWNLOADING
                else -> DownloadHandler.STATE_FAILED
            }
        _downloads.update {
            // Audio only: the video half stays null, which the playlist screen reads as "complete".
            if (handlerState == null) it - videoId else it + (videoId to (DownloadHandler.Download(handlerState) to null))
        }
    }

    // ===== Background time =====

    // While music plays the app runs in the background anyway (audio background mode). Without
    // playback, this asks iOS for the usual extra minute or so to finish what is queued; past that
    // the app is suspended and the queue carries on when it is next in the foreground.
    private var backgroundTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    private fun updateBackgroundTask() {
        scope.launch {
            withContext(Dispatchers.Main) {
                // Read here, on Main, so calls that arrive out of order still settle on the last state.
                val busy = jobs.value.isNotEmpty()
                val app = UIApplication.sharedApplication
                if (busy && backgroundTask == UIBackgroundTaskInvalid) {
                    backgroundTask =
                        app.beginBackgroundTaskWithName("SimpMusic downloads") {
                            app.endBackgroundTask(backgroundTask)
                            backgroundTask = UIBackgroundTaskInvalid
                        }
                } else if (!busy && backgroundTask != UIBackgroundTaskInvalid) {
                    app.endBackgroundTask(backgroundTask)
                    backgroundTask = UIBackgroundTaskInvalid
                }
            }
        }
    }

    private companion object {
        val DUR_PARAM = Regex("""[?&]dur=([0-9]+(?:\.[0-9]+)?)""")
        val CLEN_PARAM = Regex("""[?&]clen=([0-9]+)""")
    }
}
