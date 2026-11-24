package com.maxrave.domain.mediaservice.djmix

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Tempo and beat grid the way djmix2.analyse gets them: librosa.beat.beat_track(y, sr=22050,
 * tightness=120) on the mono mix resampled to 22.05 kHz, step by step (librosa 1.0):
 * - onset strength: 128-band Slaney mel power spectrogram (n_fft 2048, hop 512, centred), in dB
 *   clipped 80 dB below its maximum, positive first difference, median over bands, delayed by
 *   3 frames;
 * - tempo: mean autocorrelation tempogram over 8 s windows, weighted by a log-normal prior around
 *   120 BPM (one octave wide), on librosa's lag grid (so 123.05 = 21 frames per beat);
 * - beats: Ellis' dynamic programme with librosa's local score, back-links, last-beat choice and
 *   trimming quirks.
 */
object BeatTracker {
    const val SAMPLE_RATE = 22_050
    const val HOP = 512
    private const val N_FFT = 2048
    private const val N_MELS = 128
    private const val AC_FRAMES = 344 // floor(8 s * 22050 / 512)

    class Result(
        val bpm: Double,
        val beatFrames: IntArray,
        /** Mean of the onset-strength envelope (dB rises per frame): how punchy the song is. */
        val onsetMean: Double = 0.0,
    ) {
        val beatSeconds: DoubleArray get() = DoubleArray(beatFrames.size) { beatFrames[it] * HOP.toDouble() / SAMPLE_RATE }
    }

    fun track(
        mono22k: FloatArray,
        tightness: Double = 120.0,
    ): Result {
        val onset = onsetStrength(mono22k)
        if (onset.all { it == 0f }) return Result(0.0, IntArray(0))
        val bpm = tempo(onset)
        return Result(bpm, beats(onset, bpm, tightness), onset.sumOf { it.toDouble() } / onset.size)
    }

    fun onsetStrength(y: FloatArray): FloatArray {
        val stft = Stft(N_FFT, HOP)
        val frames = stft.frameCount(y.size)
        val filters = melFilters()
        val fft = RealFft(N_FFT)
        val window = hannPeriodic(N_FFT)
        val frame = DoubleArray(N_FFT)
        val re = DoubleArray(N_FFT / 2 + 1)
        val im = DoubleArray(N_FFT / 2 + 1)
        val melDb = FloatArray(frames * N_MELS)
        var top = Float.NEGATIVE_INFINITY
        for (t in 0 until frames) {
            val start = t * HOP - N_FFT / 2
            for (i in 0 until N_FFT) {
                val j = start + i
                frame[i] = if (j in y.indices) y[j] * window[i] else 0.0
            }
            fft.forward(frame, re, im)
            for (m in 0 until N_MELS) {
                val f = filters[m]
                var s = 0.0
                for (k in f.weights.indices) {
                    val b = f.first + k
                    s += f.weights[k] * (re[b] * re[b] + im[b] * im[b])
                }
                val db = (10 * log10(max(1e-10, s))).toFloat()
                melDb[t * N_MELS + m] = db
                if (db > top) top = db
            }
        }
        val floor = top - 80f
        for (i in melDb.indices) if (melDb[i] < floor) melDb[i] = floor
        val env = FloatArray(frames)
        val diffs = FloatArray(N_MELS)
        // env[t] = median over bands of max(0, S[t-2] - S[t-3]).
        for (t in 3 until frames) {
            val a = (t - 2) * N_MELS
            val b = (t - 3) * N_MELS
            for (m in 0 until N_MELS) diffs[m] = max(0f, melDb[a + m] - melDb[b + m])
            diffs.sort()
            env[t] = (diffs[N_MELS / 2 - 1] + diffs[N_MELS / 2]) / 2
        }
        return env
    }

    private class MelFilter(
        val first: Int,
        val weights: DoubleArray,
    )

    private fun hzToMel(f: Double): Double {
        val fSp = 200.0 / 3
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27
        return if (f >= minLogHz) minLogMel + ln(f / minLogHz) / logStep else f / fSp
    }

    private fun melToHz(m: Double): Double {
        val fSp = 200.0 / 3
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27
        return if (m >= minLogMel) minLogHz * exp(logStep * (m - minLogMel)) else fSp * m
    }

    private fun melFilters(): List<MelFilter> {
        val bins = N_FFT / 2 + 1
        val maxMel = hzToMel(SAMPLE_RATE / 2.0)
        val melF = DoubleArray(N_MELS + 2) { melToHz(maxMel * it / (N_MELS + 1)) }
        return List(N_MELS) { i ->
            val enorm = 2.0 / (melF[i + 2] - melF[i])
            val w = DoubleArray(bins)
            var first = -1
            var last = -1
            for (b in 0 until bins) {
                val f = b.toDouble() * SAMPLE_RATE / N_FFT
                val lower = (f - melF[i]) / (melF[i + 1] - melF[i])
                val upper = (melF[i + 2] - f) / (melF[i + 2] - melF[i + 1])
                val v = max(0.0, min(lower, upper)) * enorm
                w[b] = v
                if (v > 0) {
                    if (first < 0) first = b
                    last = b
                }
            }
            if (first < 0) MelFilter(0, DoubleArray(0)) else MelFilter(first, w.copyOfRange(first, last + 1))
        }
    }

    /** librosa.feature.tempo with its defaults (start_bpm 120, std 1 octave, max 320 BPM). */
    fun tempo(
        onset: FloatArray,
        startBpm: Double = 120.0,
    ): Double {
        val n = onset.size
        val win = AC_FRAMES
        val half = win / 2
        // linear_ramp padding to 0 on both sides.
        val padded = DoubleArray(n + 2 * half)
        val left = onset[0].toDouble()
        val right = onset[n - 1].toDouble()
        for (i in 0 until half) padded[i] = left * i / half
        for (i in 0 until n) padded[half + i] = onset[i].toDouble()
        for (j in 0 until half) padded[half + n + j] = right * (half - 1 - j) / half
        val window = hannPeriodic(win)
        val size = nextPowerOfTwo(2 * win - 1)
        val fft = RealFft(size)
        val buf = DoubleArray(size)
        val re = DoubleArray(size / 2 + 1)
        val im = DoubleArray(size / 2 + 1)
        val acc = DoubleArray(win)
        for (t in 0 until n) {
            for (i in 0 until win) buf[i] = padded[t + i] * window[i]
            for (i in win until size) buf[i] = 0.0
            fft.forward(buf, re, im)
            for (b in re.indices) {
                re[b] = re[b] * re[b] + im[b] * im[b]
                im[b] = 0.0
            }
            fft.inverse(re, im, buf)
            var peak = 0.0
            for (l in 0 until win) peak = max(peak, kotlin.math.abs(buf[l]))
            val scale = if (peak < 1e-300) 1.0 else 1.0 / peak
            for (l in 0 until win) acc[l] += buf[l] * scale
        }
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in 1 until win) {
            val bpm = 60.0 * SAMPLE_RATE / (HOP * lag)
            if (bpm >= 320.0) continue
            val tg = acc[lag] / n
            val prior = -0.5 * ((log2(bpm) - log2(startBpm))).let { it * it }
            val score = ln(1 + 1e6 * tg) + prior
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
        }
        return 60.0 * SAMPLE_RATE / (HOP * best)
    }

    /** librosa's __beat_tracker on [onset] at [bpm]: beat frame indices. */
    fun beats(
        onset: FloatArray,
        bpm: Double,
        tightness: Double,
    ): IntArray {
        val n = onset.size
        val fps = SAMPLE_RATE.toDouble() / HOP
        val fpb = round(fps * 60.0 / bpm)
        // Normalise by the standard deviation (ddof 1).
        var mean = 0.0
        for (v in onset) mean += v
        mean /= n
        var sq = 0.0
        for (v in onset) sq += (v - mean) * (v - mean)
        val std = sqrt(sq / max(1, n - 1))
        val norm = DoubleArray(n) { onset[it] / (std + 1e-300) }
        val p = fpb.toInt()
        val kLen = 2 * p + 1
        val window = DoubleArray(kLen) { k -> exp(-0.5 * ((k - p) * 32.0 / fpb).let { it * it }) }
        val local = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            val kFrom = max(0, i + kLen / 2 - n + 1)
            val kTo = min(i + kLen / 2, kLen)
            for (k in kFrom until kTo) s += window[k] * norm[i + kLen / 2 - k]
            local[i] = s
        }
        // Dynamic programme.
        val cum = DoubleArray(n)
        val back = IntArray(n)
        val thresh = 0.01 * (local.maxOrNull() ?: 0.0)
        var firstBeat = true
        val lnFpb = ln(fpb)
        val halfBeat = round(fpb / 2).toInt()
        for (i in 0 until n) {
            var bestScore = Double.NEGATIVE_INFINITY
            var loc = -1
            var l = i - halfBeat
            val stop = (i - 2 * fpb - 1).toInt()
            while (l > stop) {
                if (l < 0) break
                val d = ln((i - l).toDouble()) - lnFpb
                val score = cum[l] - tightness * d * d
                if (score > bestScore) {
                    bestScore = score
                    loc = l
                }
                l--
            }
            cum[i] = if (loc >= 0) local[i] + bestScore else local[i]
            if (firstBeat && local[i] < thresh) {
                back[i] = -1
            } else {
                back[i] = loc
                firstBeat = false
            }
        }
        // Last beat: last local maximum of the cumulative score at least half their median.
        val isMax = BooleanArray(n) { i -> i > 0 && cum[i] > cum[i - 1] && (i == n - 1 || cum[i] >= cum[i + 1]) }
        val maxima = (0 until n).filter { isMax[it] }.map { cum[it] }.toDoubleArray()
        val threshold = 0.5 * DjDsp.median(maxima)
        var tail = n - 1
        for (i in n - 1 downTo 0) {
            if (isMax[i] && cum[i] >= threshold) {
                tail = i
                break
            }
        }
        val dense = BooleanArray(n)
        var t = tail
        while (t >= 0) {
            dense[t] = true
            t = back[t]
        }
        // Trim: threshold from the Hann-smoothed local score on the beats (librosa's slicing).
        val onBeats = (0 until n).filter { dense[it] }.map { local[it] }
        val h = doubleArrayOf(0.0, 0.5, 1.0, 0.5, 0.0)
        val full = DoubleArray(onBeats.size + 4)
        for (j in onBeats.indices) for (k in 0 until 5) full[j + k] += onBeats[j] * h[k]
        val smooth = full.copyOfRange(2, min(full.size, n + 2))
        val trimThreshold = 0.5 * sqrt(smooth.sumOf { it * it } / max(1, smooth.size))
        var a = 0
        while (a < n && local[a] <= trimThreshold) {
            dense[a] = false
            a++
        }
        var b = n - 1
        while (b >= 0 && local[b] <= trimThreshold) {
            dense[b] = false
            b--
        }
        return (0 until n).filter { dense[it] }.toIntArray()
    }
}
