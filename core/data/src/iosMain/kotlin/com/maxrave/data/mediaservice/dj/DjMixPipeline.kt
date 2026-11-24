package com.maxrave.data.mediaservice.dj

import com.maxrave.data.download.IosAudioTagger
import com.maxrave.data.download.IosHttp
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.djmix.DjDsp
import com.maxrave.domain.mediaservice.djmix.DjTrackAnalysis
import com.maxrave.domain.mediaservice.djmix.DjTrackAnalyzer
import com.maxrave.domain.mediaservice.djmix.DjTransitionOptions
import com.maxrave.domain.mediaservice.djmix.DjTransitionRenderer
import com.maxrave.domain.mediaservice.djmix.StereoPcm
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.posix.QOS_CLASS_UTILITY
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.TimeSource

private const val TAG = "DjMix"

/** Bumped when the render, its options or the file layout change: older renders are redone. */
private const val MIX_VERSION = 2

/** Sidecar of a rendered transition (`<a>__<b>.mix.json`). */
@Serializable
internal data class DjTransitionMeta(
    val version: Int = MIX_VERSION,
    val a: String,
    val b: String,
    val sampleRate: Int,
    /** Frames in the WAV. */
    val frames: Int,
    /** Frame of A where the segment starts: A plays untouched before it. */
    val aStartFrame: Int,
    /** Frame of B where normal playback resumes after the segment. */
    val bResumeFrame: Int,
    val blendStart: Int,
    val blendLength: Int,
    /** Frame of the segment where the track identity switches to B (B's melody entry). */
    val identityFrame: Int,
    val aTotalFrames: Long,
    val bTotalFrames: Long,
    val aAnalysisVersion: Int,
    val bAnalysisVersion: Int,
    val summary: String = "",
) {
    private fun ms(frames: Long): Long = frames * 1000L / sampleRate

    val aStartMs: Long get() = ms(aStartFrame.toLong())
    val bResumeMs: Long get() = ms(bResumeFrame.toLong())
    val mixLengthMs: Long get() = ms(frames.toLong())
    val identityMs: Long get() = ms(identityFrame.toLong())
    val bTotalMs: Long get() = ms(bTotalFrames)
}

/** A transition the player can use: both files exist. */
internal class DjReadyTransition(
    val a: String,
    val b: String,
    val mixPath: String,
    /** B's own audio (cache copy or download), which the player resumes B from. */
    val bAudioPath: String,
    val meta: DjTransitionMeta,
)

/** GCD's utility QoS queue: below the UI and the audio, above purely background work. */
private object DjUtilityDispatcher : CoroutineDispatcher() {
    private val queue = dispatch_get_global_queue(QOS_CLASS_UTILITY.toLong(), 0u)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatch_async(queue) { block.run() }
    }
}

/**
 * Builds DJ transitions in the background for the iOS player: for the current track A and the
 * track the queue will actually play next, B, it makes sure both songs are on disk (the download,
 * or a copy in [DjCache]) and analysed, then renders A→B into a WAV the player switches to.
 *
 * One stage at a time on a utility-QoS GCD queue, cancelled and restarted whenever (A, B)
 * changes; nothing here ever touches the player. [onReady] reports a finished transition.
 *
 * Memory: songs are analysed from streamed chunks (the analyser keeps a 22 kHz mono copy, ~5 MB a
 * minute) and the renderer only gets the two windows its plan asks for (~25-35 s each), decoded
 * from the files on demand — never a whole song at 44.1 kHz stereo.
 */
internal class DjMixPipeline(
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
) {
    private val controlDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val control = CoroutineScope(SupervisorJob() + controlDispatcher)
    private val work = DjUtilityDispatcher.limitedParallelism(1)

    private var wantedA: String? = null
    private var wantedB: String? = null
    private var job: Job? = null

    /**
     * Smart next: the further upcoming tracks to analyse after (A, B), in queue order. Read by the
     * running job between songs, so a change here alone never cancels work in flight.
     */
    @Volatile
    private var wantedLook: List<String> = emptyList()

    /** Ids whose analysis (or failure) was already reported for the current A. Control context only. */
    private val reported = HashSet<String>()
    private var reportedFor: String? = null

    /**
     * Smart next: the analysis of [id] (null = it cannot be analysed) made for current track [forA].
     * Called on the pipeline's thread; A, B and each look-ahead id are reported once per A.
     */
    @Volatile
    var onAnalysed: ((forA: String, id: String, analysis: DjTrackAnalysis?) -> Unit)? = null

    /** Pairs that cannot be rendered (plan or decode refused them): not retried this run. */
    private val failedPairs = HashSet<String>()

    @Volatile
    private var latest: DjReadyTransition? = null

    @Volatile
    var onReady: ((DjReadyTransition) -> Unit)? = null

    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    /** The ready transition for exactly ([a], [b]), or null. Any thread. */
    fun readyFor(
        a: String?,
        b: String?,
    ): DjReadyTransition? = latest?.takeIf { a != null && b != null && it.a == a && it.b == b }

    /**
     * The player's current track and the one it will play next (null = none, or not to be mixed
     * into). Cheap and idempotent; any thread.
     */
    fun update(
        a: String?,
        b: String?,
        lookAhead: List<String> = emptyList(),
    ) {
        control.launch {
            val look = lookAhead.filter { it != a && it != b }.distinct()
            if (a == wantedA && b == wantedB) {
                if (look == wantedLook) return@launch
                wantedLook = look
                // A running job picks the new list up itself; a finished one is restarted for the
                // ids it has not reported yet (everything before them is on disk by now).
                if (a != null && job?.isActive != true && look.any { it !in reported }) {
                    job = control.launch(work) { runPair(a, b) }
                }
                return@launch
            }
            wantedA = a
            wantedB = b
            wantedLook = look
            job?.cancel()
            job = null
            if (a != reportedFor) {
                reportedFor = a
                reported.clear()
            }
            if (a == null) return@launch
            job =
                control.launch(work) {
                    // Rapid skips should not each start a download.
                    delay(DEBOUNCE_MS)
                    runPair(a, b)
                }
        }
    }

    /** Reports [id]'s analysis for [a] once. Runs on [work]; [reported] is touched on [control]. */
    private suspend fun report(
        a: String,
        id: String,
        analysis: DjTrackAnalysis?,
    ) {
        val first = withContext(controlDispatcher) { reportedFor == a && reported.add(id) }
        if (first) onAnalysed?.invoke(a, id, analysis)
    }

    private suspend fun isReported(
        a: String,
        id: String,
    ): Boolean = withContext(controlDispatcher) { reportedFor == a && id in reported }

    fun release() {
        control.cancel()
    }

    private suspend fun runPair(
        a: String,
        b: String?,
    ) {
        try {
            DjCache.ensureDirectory()
            transition(a, b)
            // Smart next: the further upcoming tracks, one at a time, after the pair itself so
            // the transition about to be needed is never delayed by them.
            while (true) {
                currentCoroutineContext().ensureActive()
                val id = wantedLook.firstOrNull { it != a && it != b && !isReported(a, it) } ?: break
                val analysis =
                    try {
                        ensureAnalysis(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Logger.w(TAG, "DJ look-ahead $id failed: ${e.message}")
                        null
                    }
                report(a, id, analysis)
            }
            DjCache.evict(setOfNotNull(a, b) + wantedLook)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "DJ pipeline ${a}→$b failed: ${e.message}")
        }
    }

    /** Stage 1-3 for (A, B): the songs analysed (and reported) and the transition rendered. */
    private suspend fun transition(
        a: String,
        b: String?,
    ) {
        try {
            if (b != null) {
                existingTransition(a, b)?.let {
                    publish(it)
                    // Cached JSON reads: cheap, and the smart-next pick needs both.
                    if (!isReported(a, a)) report(a, a, ensureAnalysis(a))
                    if (!isReported(a, b)) report(a, b, ensureAnalysis(b))
                    return
                }
            }
            val ia = ensureAnalysis(a)
            report(a, a, ia)
            if (ia == null || b == null) return
            val key = "$a>$b"
            val ib = ensureAnalysis(b)
            report(a, b, ib)
            if (ib == null || key in failedPairs) return
            val ready = render(a, b, ia, ib)
            releaseMemory()
            if (ready == null) {
                failedPairs += key
                return
            }
            publish(ready)
            DjCache.evict(setOfNotNull(a, b) + wantedLook)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "DJ pipeline ${a}→$b failed: ${e.message}")
        }
    }

    private fun publish(ready: DjReadyTransition) {
        latest = ready
        onReady?.invoke(ready)
    }

    // ===== Stage 1: the song on disk =====

    private suspend fun ensureSong(videoId: String): String? {
        DjCache.audioFileFor(videoId)?.let {
            DjCache.touch(it)
            return it
        }
        val start = TimeSource.Monotonic.markNow()
        val url = resolveUrl(videoId)
        if (url == null) {
            Logger.w(TAG, "DJ cache $videoId: no AAC stream")
            return null
        }
        val part = DjCache.partPath(videoId)
        DjCache.delete(part)
        try {
            val bytes =
                try {
                    download(url, part)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    // The cached format row's URL may have expired: one more try with a fresh one.
                    val fresh =
                        streamRepository
                            .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = false)
                            .lastOrNull()
                            ?.takeIf { it != url && !it.contains(".m3u8") && !it.contains("manifest") }
                            ?: throw e
                    DjCache.delete(part)
                    download(fresh, part)
                }
            currentCoroutineContext().ensureActive()
            // A passthrough export turns YouTube's fragmented MP4 into a plain one with an exact
            // duration (as the downloader does); the stream itself is kept when that fails.
            val exportPath = DjCache.exportPath(videoId)
            val exported =
                runCatching {
                    IosAudioTagger.exportTagged(part, exportPath, videoId, null, null, null)
                }.getOrElse {
                    if (it is CancellationException) throw it
                    false
                }
            val final = DjCache.songPath(videoId)
            if (exported) {
                DjCache.move(exportPath, final)
                DjCache.delete(part)
            } else {
                DjCache.move(part, final)
            }
            Logger.i(TAG, "DJ cache $videoId: $bytes bytes in ${start.elapsedNow().inWholeMilliseconds} ms")
            return final
        } catch (e: Throwable) {
            DjCache.delete(part)
            DjCache.delete(DjCache.exportPath(videoId))
            if (e is CancellationException) throw e
            Logger.w(TAG, "DJ cache $videoId failed: ${e.message}")
            return null
        }
    }

    /** The AAC stream the player itself uses (cached format row), else a fresh resolve. */
    private suspend fun resolveUrl(videoId: String): String? {
        fun usable(url: String?) = !url.isNullOrEmpty() && !url.contains(".m3u8") && !url.contains("manifest")
        streamRepository.getNewFormat(videoId).lastOrNull()?.audioUrl?.let { url ->
            if (usable(url) && streamRepository.is403Url(url).firstOrNull() == false) return url
        }
        val url =
            streamRepository
                .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = false)
                .lastOrNull()
        return url.takeIf { usable(it) }
    }

    /** Ranged parallel GETs appended in order to [path] (as IosDownloadHandler does). Returns the size. */
    private suspend fun download(
        url: String,
        path: String,
    ): Long {
        var total = CLEN_PARAM.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
        var offset = 0L
        val fs = FileSystem.SYSTEM
        fs.write(path.toPath()) {}
        if (total == null) {
            val first = fetch(url, 0, CHUNK_BYTES - 1)
            total = first.totalLength ?: first.body.size.toLong()
            append(path, first.body)
            offset = first.body.size.toLong()
        }
        while (offset < total) {
            currentCoroutineContext().ensureActive()
            val ranges =
                (0 until PARALLEL_CHUNKS)
                    .map { offset + it * CHUNK_BYTES }
                    .filter { it < total }
                    .map { it to min(it + CHUNK_BYTES, total) - 1 }
            val bodies = coroutineScope { ranges.map { (f, t) -> async { fetch(url, f, t).body } }.awaitAll() }
            for ((i, body) in bodies.withIndex()) {
                val expected = ranges[i].second - ranges[i].first + 1
                if (body.size.toLong() != expected) throw IllegalStateException("short read ${body.size} of $expected")
                append(path, body)
                offset += body.size
            }
        }
        return offset
    }

    private suspend fun fetch(
        url: String,
        from: Long,
        to: Long,
    ): com.maxrave.data.download.HttpChunk {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val chunk = IosHttp.get(url, from, to)
                if (chunk.status == 206) return chunk
                if (chunk.status == 200) {
                    val end = min(chunk.body.size.toLong(), to + 1).toInt()
                    return com.maxrave.data.download.HttpChunk(206, chunk.body.copyOfRange(from.toInt(), max(from.toInt(), end)), chunk.body.size.toLong())
                }
                last = IllegalStateException("HTTP ${chunk.status}")
                if (chunk.status == 403 || chunk.status == 410) throw last!!
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                if (e.message?.startsWith("HTTP 4") == true) throw e
            }
            delay(1000L * (attempt + 1))
        }
        throw last ?: IllegalStateException("download failed")
    }

    private fun append(
        path: String,
        bytes: ByteArray,
    ) {
        FileSystem.SYSTEM.appendingSink(path.toPath()).buffer().use { it.write(bytes) }
    }

    // ===== Stage 2: analysis =====

    private suspend fun ensureAnalysis(videoId: String): DjTrackAnalysis? {
        val jsonPath = DjCache.analysisPath(videoId)
        DjCache.readText(jsonPath)?.let(DjTrackAnalysis::decode)?.let {
            DjCache.touch(jsonPath)
            ensureSong(videoId) ?: return null
            return it
        }
        val file = ensureSong(videoId) ?: return null
        val start = TimeSource.Monotonic.markNow()
        val analyzer = DjTrackAnalyzer()
        DjAudioDecoder.stream(file) { buffer, frames -> analyzer.feed(buffer, frames) }
        currentCoroutineContext().ensureActive()
        val analysis = analyzer.finish()
        releaseMemory()
        if (analysis.totalFrames < 30L * DjDsp.SAMPLE_RATE || analysis.beats.size < 16) {
            Logger.w(TAG, "DJ analyse $videoId: unusable (${analysis.totalFrames} frames, ${analysis.beats.size} beats)")
            return null
        }
        DjCache.writeText(jsonPath, analysis.encode())
        Logger.i(
            TAG,
            "DJ analyse $videoId: ${start.elapsedNow().inWholeMilliseconds} ms " +
                "(${(analysis.bpm * 10).roundToInt() / 10.0} bpm, ${analysis.keyName}, ${analysis.totalFrames / DjDsp.SAMPLE_RATE} s)",
        )
        return analysis
    }

    // ===== Stage 3: the transition =====

    private fun existingTransition(
        a: String,
        b: String,
    ): DjReadyTransition? {
        val metaPath = DjCache.mixMetaPath(a, b)
        val mixPath = DjCache.mixPath(a, b)
        val meta =
            DjCache.readText(metaPath)?.let { text -> runCatching { json.decodeFromString(DjTransitionMeta.serializer(), text) }.getOrNull() }
                ?: return null
        if (meta.version != MIX_VERSION ||
            meta.aAnalysisVersion != DjTrackAnalysis.VERSION ||
            meta.bAnalysisVersion != DjTrackAnalysis.VERSION ||
            meta.a != a ||
            meta.b != b
        ) {
            return null
        }
        val bAudio = DjCache.audioFileFor(b) ?: return null
        if (DjCache.sizeOf(mixPath) < WAV_HEADER + meta.frames * 4L) return null
        DjCache.touch(metaPath)
        DjCache.touch(mixPath)
        DjCache.touch(bAudio)
        return DjReadyTransition(a, b, mixPath, bAudio, meta)
    }

    private suspend fun render(
        a: String,
        b: String,
        ia: DjTrackAnalysis,
        ib: DjTrackAnalysis,
    ): DjReadyTransition? {
        val fileA = ensureSong(a) ?: return null
        val fileB = ensureSong(b) ?: return null
        val start = TimeSource.Monotonic.markNow()
        val options = DjTransitionOptions()
        val plan = DjTransitionRenderer.plan(ia, ib, options)
        val sr = DjDsp.SAMPLE_RATE
        // A must have enough music before the segment, B enough after it, for a switch worth making.
        if (plan.aStart < 20 * sr || plan.aWindowTo <= plan.aWindowFrom || plan.bWindowTo <= plan.bWindowFrom) {
            Logger.w(TAG, "DJ render $a→$b: plan unusable (aStart ${plan.aStart}, windows ${plan.aWindowFrom}-${plan.aWindowTo} / ${plan.bWindowFrom}-${plan.bWindowTo})")
            return null
        }
        val rendered =
            run {
                val aWindow = DjAudioDecoder.window(fileA, plan.aWindowFrom, plan.aWindowTo)
                currentCoroutineContext().ensureActive()
                val bWindow = DjAudioDecoder.window(fileB, plan.bWindowFrom, plan.bWindowTo)
                currentCoroutineContext().ensureActive()
                runCatching { DjTransitionRenderer.render(plan, aWindow, bWindow, ia, ib, options) }
                    .onFailure { Logger.w(TAG, "DJ render $a→$b refused: ${it.message}") }
                    .getOrNull()
            } ?: return null
        currentCoroutineContext().ensureActive()
        if (rendered.bResumeFrame >= ib.totalFrames - 10L * sr || rendered.pcm.frames < sr) {
            Logger.w(TAG, "DJ render $a→$b: B resumes too late (${rendered.bResumeFrame} of ${ib.totalFrames})")
            return null
        }
        val mixPath = DjCache.mixPath(a, b)
        writeWav(mixPath, rendered.pcm)
        val identity = rendered.identityFrame
        val meta =
            DjTransitionMeta(
                a = a,
                b = b,
                sampleRate = sr,
                frames = rendered.pcm.frames,
                aStartFrame = rendered.aStartFrame,
                bResumeFrame = rendered.bResumeFrame,
                blendStart = rendered.blendStart,
                blendLength = rendered.blendLength,
                identityFrame = identity,
                aTotalFrames = ia.totalFrames,
                bTotalFrames = ib.totalFrames,
                aAnalysisVersion = ia.version,
                bAnalysisVersion = ib.version,
                summary = rendered.summary,
            )
        DjCache.writeText(DjCache.mixMetaPath(a, b), json.encodeToString(DjTransitionMeta.serializer(), meta))
        Logger.i(
            TAG,
            "DJ render $a→$b: ${start.elapsedNow().inWholeMilliseconds} ms, segment ${meta.mixLengthMs} ms, " +
                "A from ${meta.aStartMs} ms, B resumes at ${meta.bResumeMs} ms; ${rendered.summary}",
        )
        return DjReadyTransition(a, b, mixPath, fileB, meta)
    }

    /** 16-bit stereo PCM WAV, written in chunks to a temp name and renamed. */
    private fun writeWav(
        path: String,
        pcm: StereoPcm,
    ) {
        val tmp = "$path.tmp"
        val frames = pcm.frames
        val dataBytes = frames * 4
        FileSystem.SYSTEM.write(tmp.toPath()) {
            writeUtf8("RIFF")
            writeIntLe(36 + dataBytes)
            writeUtf8("WAVE")
            writeUtf8("fmt ")
            writeIntLe(16)
            writeShortLe(1)
            writeShortLe(2)
            writeIntLe(pcm.sampleRate)
            writeIntLe(pcm.sampleRate * 4)
            writeShortLe(4)
            writeShortLe(16)
            writeUtf8("data")
            writeIntLe(dataBytes)
            val chunk = ByteArray(16_384 * 4)
            var pos = 0
            while (pos < frames) {
                val n = min(16_384, frames - pos)
                for (i in 0 until n) {
                    val l = toShort(pcm.left[pos + i])
                    val r = toShort(pcm.right[pos + i])
                    val o = i * 4
                    chunk[o] = l.toByte()
                    chunk[o + 1] = (l shr 8).toByte()
                    chunk[o + 2] = r.toByte()
                    chunk[o + 3] = (r shr 8).toByte()
                }
                write(chunk, 0, n * 4)
                pos += n
            }
        }
        DjCache.move(tmp, path)
    }

    /** Hands the stage's large buffers back now rather than whenever the GC next runs. */
    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
    private fun releaseMemory() {
        kotlin.native.runtime.GC.collect()
    }

    private fun toShort(v: Float): Int = (v * 32767f).roundToInt().coerceIn(-32768, 32767)

    private companion object {
        const val DEBOUNCE_MS = 2_000L
        const val CHUNK_BYTES = 2L * 1024 * 1024
        const val PARALLEL_CHUNKS = 4
        const val WAV_HEADER = 44L
        val CLEN_PARAM = Regex("""[?&]clen=([0-9]+)""")
    }
}
