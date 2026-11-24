package com.maxrave.domain.mediaservice.djmix

import com.maxrave.domain.mediaservice.automix.TrackStructureAnalyzer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Listening-test harness against the Python prototype (djmix5.py). Runs only when DJMIX_SCRATCH
 * points at the prototype's scratch folder (mixcache/<id>.f32, features.json, pairs.txt) and
 * DJMIX_OUT at a folder for the WAVs:
 * - analysis (tempo, beats, key) of every cached song against librosa's;
 * - the pairs from pairs.txt plus one skip transition rendered with the same framing as djmix5
 *   (12 s of A before its glide, the transition, B up to 15 s after the blend).
 * Writes report.txt into DJMIX_OUT.
 */
class DjMixVerification {
    private val sr = DjDsp.SAMPLE_RATE
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun verifyAgainstPrototype() {
        val scratch = System.getenv("DJMIX_SCRATCH")?.let(::File) ?: return
        val out = System.getenv("DJMIX_OUT")?.let(::File) ?: return
        out.mkdirs()
        val report = StringBuilder()
        fun log(s: String) {
            println(s)
            report.appendLine(s)
            File(out, "report.txt").writeText(report.toString())
        }
        val cache = File(scratch, "mixcache")
        val analysisDir = File(scratch, "djmix_kt").apply { mkdirs() }
        val features = json.parseToJsonElement(File(scratch, "features.json").readText()).jsonObject
        val only = System.getenv("DJMIX_ONLY_RENDER") == "1"

        val analyses = HashMap<String, DjTrackAnalysis>()
        fun analysis(vid: String): DjTrackAnalysis =
            analyses.getOrPut(vid) {
                val f = File(analysisDir, "$vid.json")
                DjTrackAnalysis.decode(if (f.exists()) f.readText() else "")
                    ?: DjTrackAnalyzer.analyze(load(File(cache, "$vid.f32"))).also { f.writeText(it.encode()) }
            }

        if (!only) {
            log("== Analysis vs librosa (features.json / mixcache/<vid>.json) ==")
            var n = 0
            var tempoExact = 0
            var tempo2 = 0
            var tempoOct = 0
            var tsaOct = 0
            var tsa2 = 0
            var keyExact = 0
            var keyRel = 0
            var fSum = 0.0
            var analyzeMs = 0L
            for ((vid, feat) in features) {
                val file = File(cache, "$vid.f32")
                if (!file.exists()) continue
                val pcm = load(file)
                val t0 = System.nanoTime()
                val a = DjTrackAnalyzer.analyze(pcm)
                analyzeMs += (System.nanoTime() - t0) / 1_000_000
                File(analysisDir, "$vid.json").writeText(a.encode())
                analyses[vid] = a
                val f = feat.jsonObject
                val lt = f["tempo"]!!.jsonPrimitive.double
                val lk = f["key"]!!.jsonPrimitive.content
                val lbeats =
                    File(cache, "$vid.json").takeIf { it.exists() }?.let {
                        json.parseToJsonElement(it.readText()).jsonObject["beats"]!!.jsonArray.map { b -> b.jsonPrimitive.double }
                    } ?: emptyList()
                // TrackStructureAnalyzer at 8 kHz mono 16-bit.
                val mono = FloatArray(pcm.frames) { (pcm.left[it] + pcm.right[it]) * 0.5f }
                val m8 = Resampler.resample(mono, sr, TrackStructureAnalyzer.SAMPLE_RATE)
                val shorts = ShortArray(m8.size) { (m8[it] * 32767).roundToInt().coerceIn(-32768, 32767).toShort() }
                val tsa = TrackStructureAnalyzer.analyze(shorts)?.bpm ?: 0.0
                n++
                if (abs(a.bpm - lt) < 0.01) tempoExact++
                if (abs(a.bpm / lt - 1) < 0.02) tempo2++
                if (octaveClose(a.bpm, lt)) tempoOct++
                if (abs(tsa / lt - 1) < 0.02) tsa2++
                if (octaveClose(tsa, lt)) tsaOct++
                if (a.keyName == lk) keyExact++
                val lTonic = KeyDetector.NOTES.indexOf(lk.removeSuffix("m"))
                if (KeyDetector.tonicMajor(a.keyTonic, a.keyMinor) == KeyDetector.tonicMajor(lTonic, lk.endsWith("m"))) keyRel++
                val fm = fMeasure(a.beats, lbeats, 0.07)
                fSum += fm
                log(
                    "$vid librosa ${"%.2f".format(lt)} ${lk.padEnd(3)} | kotlin ${"%.2f".format(a.bpm)} ${a.keyName.padEnd(3)} " +
                        "beatF ${"%.2f".format(fm)} | TrackStructureAnalyzer ${"%.2f".format(tsa)}",
                )
            }
            log(
                "songs $n: tempo exact $tempoExact, within 2% $tempo2, within 2% allowing x2/x0.5 $tempoOct; " +
                    "TrackStructureAnalyzer within 2% $tsa2, allowing x2/x0.5 $tsaOct; key exact $keyExact, same/relative $keyRel; " +
                    "mean beat F-measure (70 ms) ${"%.3f".format(fSum / max(1, n))}; analysis ${analyzeMs / max(1, n)} ms/song",
            )
        }

        log("== Renders ==")
        val pairs =
            File(scratch, "pairs.txt").readLines().filter { it.isNotBlank() }.map { line ->
                val p = line.split("|")
                Triple(p[0], p[1], p[2]) to (null as Double?)
            } + listOf(Triple("skip 9Cp-hNvSWZs into z-wNY9Uu4E4 at 60s", "9Cp-hNvSWZs", "z-wNY9Uu4E4") to 60.0)
        for ((pair, skip) in pairs) {
            val (name, va, vb) = pair
            val a = load(File(cache, "$va.f32"))
            val b = load(File(cache, "$vb.f32"))
            val ia = analysis(va)
            val ib = analysis(vb)
            val opts = DjTransitionOptions(skipAtSec = skip, levelMode = DjLevelMode.NORMALIZED, limiter = false)
            val plan = DjTransitionRenderer.plan(ia, ib, opts)
            val aw = a.slice(plan.aWindowFrom, plan.aWindowTo)
            val bw = b.slice(plan.bWindowFrom, plan.bWindowTo)
            val t0 = System.nanoTime()
            val r = DjTransitionRenderer.render(plan, aw, bw, ia, ib, opts)
            val ms = (System.nanoTime() - t0) / 1_000_000
            // djmix5's framing: A from 12 s before the glide, then B until 15 s after the blend.
            val preFrom = max(0, r.aStartFrame - 12 * sr)
            val afterBlend = r.pcm.frames - r.blendStart - r.blendLength
            val postLen = max(0, 15 * sr - afterBlend)
            val total = (r.aStartFrame - preFrom) + r.pcm.frames + postLen
            val l = FloatArray(total)
            val rr = FloatArray(total)
            var k = 0
            val ga = r.aGain.toFloat()
            val gb = r.bGain.toFloat()
            for (i in preFrom until r.aStartFrame) {
                l[k] = a.left[i] * ga
                rr[k++] = a.right[i] * ga
            }
            val joinA = k
            for (i in 0 until r.pcm.frames) {
                l[k] = r.pcm.left[i]
                rr[k++] = r.pcm.right[i]
            }
            val joinB = k
            for (i in 0 until postLen) {
                val j = r.bResumeFrame + i
                l[k] = if (j < b.frames) b.left[j] * gb else 0f
                rr[k++] = if (j < b.frames) b.right[j] * gb else 0f
            }
            val clip = StereoPcm(l, rr)
            val peak = DjDsp.peak(clip)
            if (peak > 0.98f) {
                val s = 0.98f / peak
                for (i in 0 until total) {
                    l[i] *= s
                    rr[i] *= s
                }
            }
            writeWav(File(out, "$name.wav"), clip)
            log(
                "$name: ${r.summary}; render ${ms} ms; segment ${"%.1f".format(r.pcm.frames / sr.toDouble())} s " +
                    "(A plays to ${"%.2f".format(r.aStartFrame / sr.toDouble())} s, B resumes at ${"%.2f".format(r.bResumeFrame / sr.toDouble())} s); " +
                    "blend starts ${"%.1f".format((joinA + r.blendStart) / sr.toDouble())} s into the clip; " +
                    "join steps A ${"%.4f".format(step(l, joinA))} B ${"%.4f".format(step(l, joinB))} " +
                    "(typical ${"%.4f".format(typicalStep(l, joinA))}/${"%.4f".format(typicalStep(l, joinB))})",
            )
            fun maxStep(x: FloatArray, g: Float): Float {
                var m = 0f
                for (i in 1 until x.size) m = max(m, abs(x[i] - x[i - 1]) * g)
                return m
            }
            log(
                "  largest sample step: segment ${"%.3f".format(max(maxStep(r.pcm.left, 1f), maxStep(r.pcm.right, 1f)))}, " +
                    "sources ${"%.3f".format(max(maxStep(aw.left, ga), maxStep(bw.left, gb)))}",
            )
            // Seamlessness in the player's own levels: raw A + rendered + raw B.
            val raw = DjTransitionRenderer.render(plan, aw, bw, ia, ib, opts.copy(levelMode = DjLevelMode.RAW_CONTINUOUS, limiter = true))
            val sA = abs(raw.pcm.left[0] - a.left[raw.aStartFrame - 1])
            val sB = abs(b.left[raw.bResumeFrame] - raw.pcm.left[raw.pcm.frames - 1])
            log("  raw-level seams: A->segment step ${"%.4f".format(sA)}, segment->B step ${"%.4f".format(sB)}")
        }
    }

    /**
     * djmix6 (breakdown blend for unmatched tempos) listening test: runs when DJMIX_SCRATCH and
     * DJMIX_UNMATCHED_OUT are set. Renders the three approved pairs with djmix6's framing (15 s of
     * A before the blend, the blend, 15 s of B after it), loudness-matched like the prototype.
     */
    @Test
    fun renderUnmatchedPairs() {
        val scratch = System.getenv("DJMIX_SCRATCH")?.let(::File) ?: return
        val out = System.getenv("DJMIX_UNMATCHED_OUT")?.let(::File) ?: return
        out.mkdirs()
        val cache = File(scratch, "mixcache")
        val analysisDir = File(scratch, "djmix_kt").apply { mkdirs() }
        fun analysis(vid: String): DjTrackAnalysis {
            val f = File(analysisDir, "$vid.json")
            return DjTrackAnalysis.decode(if (f.exists()) f.readText() else "")
                ?: DjTrackAnalyzer.analyze(load(File(cache, "$vid.f32"))).also { f.writeText(it.encode()) }
        }
        val report = StringBuilder()
        val pairs =
            listOf(
                Triple("1 Zaalima into Darkhaast", "eXkHvT--DBU", "-3vUz7QPljM"),
                Triple("2 Bandhu 2.0 into Zaalima", "eQoI2bPIkDY", "eXkHvT--DBU"),
                Triple("3 Khairiyat into Jaana Samjho Na", "q3HNo5a3ol4", "5G4CQS9h8WA"),
            )
        for ((name, va, vb) in pairs) {
            val a = load(File(cache, "$va.f32"))
            val b = load(File(cache, "$vb.f32"))
            val ia = analysis(va)
            val ib = analysis(vb)
            val opts = DjTransitionOptions(levelMode = DjLevelMode.NORMALIZED, limiter = false)
            val plan = DjTransitionRenderer.plan(ia, ib, opts)
            val t0 = System.nanoTime()
            val r = DjTransitionRenderer.render(plan, a.slice(plan.aWindowFrom, plan.aWindowTo), b.slice(plan.bWindowFrom, plan.bWindowTo), ia, ib, opts)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val blendInA = r.aStartFrame + r.blendStart
            val preFrom = max(0, blendInA - 15 * sr)
            val postLen = 15 * sr
            val total = (r.aStartFrame - preFrom) + r.pcm.frames + postLen
            val l = FloatArray(total)
            val rr = FloatArray(total)
            var k = 0
            val ga = r.aGain.toFloat()
            val gb = r.bGain.toFloat()
            for (i in preFrom until r.aStartFrame) {
                l[k] = a.left[i] * ga
                rr[k++] = a.right[i] * ga
            }
            for (i in 0 until r.pcm.frames) {
                l[k] = r.pcm.left[i]
                rr[k++] = r.pcm.right[i]
            }
            for (i in 0 until postLen) {
                val j = r.bResumeFrame + i
                l[k] = if (j < b.frames) b.left[j] * gb else 0f
                rr[k++] = if (j < b.frames) b.right[j] * gb else 0f
            }
            val clip = StereoPcm(l, rr)
            val peak = DjDsp.peak(clip)
            if (peak > 0.98f) {
                val g = 0.98f / peak
                for (i in 0 until total) {
                    l[i] *= g
                    rr[i] *= g
                }
            }
            writeWav(File(out, "$name.wav"), clip)
            val line =
                "$name: ${r.summary}; render $ms ms; matched ${plan.matched}; A blend from " +
                    "${"%.2f".format(blendInA / sr.toDouble())} s, B from ${"%.2f".format(plan.bStart / sr.toDouble())} s, " +
                    "B resumes ${"%.2f".format(r.bResumeFrame / sr.toDouble())} s; identity at +${"%.1f".format((r.identityFrame - r.blendStart) / sr.toDouble())} s of the blend"
            println(line)
            report.appendLine(line)
        }
        File(out, "report.txt").writeText(report.toString())
    }

    private fun step(
        x: FloatArray,
        at: Int,
    ): Float = if (at in 1 until x.size) abs(x[at] - x[at - 1]) else 0f

    /** 99th-percentile sample step around [at] (+-0.5 s). */
    private fun typicalStep(
        x: FloatArray,
        at: Int,
    ): Float {
        val from = max(1, at - sr / 2)
        val to = min(x.size, at + sr / 2)
        if (to <= from) return 0f
        val steps = FloatArray(to - from) { abs(x[from + it] - x[from + it - 1]) }
        steps.sort()
        return steps[(steps.size * 0.99).toInt().coerceAtMost(steps.size - 1)]
    }

    private fun octaveClose(
        a: Double,
        b: Double,
    ): Boolean = listOf(0.5, 1.0, 2.0).any { abs(a * it / b - 1) < 0.02 }

    private fun fMeasure(
        est: List<Double>,
        ref: List<Double>,
        tol: Double,
    ): Double {
        if (est.isEmpty() || ref.isEmpty()) return 0.0
        var hits = 0
        var j = 0
        val used = BooleanArray(est.size)
        for (r in ref) {
            while (j < est.size && est[j] < r - tol) j++
            var k = j
            while (k < est.size && est[k] <= r + tol) {
                if (!used[k]) {
                    used[k] = true
                    hits++
                    break
                }
                k++
            }
        }
        val p = hits.toDouble() / est.size
        val rc = hits.toDouble() / ref.size
        return if (p + rc == 0.0) 0.0 else 2 * p * rc / (p + rc)
    }

    private fun load(file: File): StereoPcm {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt())
            raf.readFully(bytes)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val n = fb.remaining() / 2
            val l = FloatArray(n)
            val r = FloatArray(n)
            for (i in 0 until n) {
                l[i] = fb.get()
                r[i] = fb.get()
            }
            return StereoPcm(l, r)
        }
    }

    private fun writeWav(
        file: File,
        pcm: StereoPcm,
    ) {
        val n = pcm.frames
        val data = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sr).putInt(sr * 4).putShort(4).putShort(16)
        data.put("data".toByteArray()).putInt(n * 4)
        for (i in 0 until n) {
            data.putShort((pcm.left[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            data.putShort((pcm.right[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        file.writeBytes(data.array())
    }
}
