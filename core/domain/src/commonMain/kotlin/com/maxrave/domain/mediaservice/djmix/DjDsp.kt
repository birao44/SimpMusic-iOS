package com.maxrave.domain.mediaservice.djmix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Stereo PCM, one float array per channel, at [sampleRate]. */
class StereoPcm(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int = DjDsp.SAMPLE_RATE,
) {
    init {
        require(left.size == right.size)
    }

    val frames: Int get() = left.size

    fun channel(c: Int): FloatArray = if (c == 0) left else right

    /** Frames [from, to), zero outside the signal. */
    fun slice(
        from: Int,
        to: Int,
    ): StereoPcm {
        val n = max(0, to - from)
        val l = FloatArray(n)
        val r = FloatArray(n)
        val a = max(from, 0)
        val b = min(to, frames)
        if (b > a) {
            left.copyInto(l, a - from, a, b)
            right.copyInto(r, a - from, a, b)
        }
        return StereoPcm(l, r, sampleRate)
    }

    fun interleaved(): FloatArray =
        FloatArray(frames * 2) { i -> if (i % 2 == 0) left[i / 2] else right[i / 2] }

    companion object {
        fun fromInterleaved(
            data: FloatArray,
            sampleRate: Int = DjDsp.SAMPLE_RATE,
        ): StereoPcm {
            val n = data.size / 2
            return StereoPcm(FloatArray(n) { data[2 * it] }, FloatArray(n) { data[2 * it + 1] }, sampleRate)
        }
    }
}

/** The small DSP pieces of djmix5, one function per Python helper. */
object DjDsp {
    const val SAMPLE_RATE = 44_100

    /** djmix.frames_db: 50 ms frames. */
    const val LEVEL_HOP = 2205

    fun smoothstep(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    fun toDb(energy: Double): Double = 10 * log10(energy + 1e-12)

    /** Mean square of both channels per 50 ms frame (djmix.frames_db before the log). */
    fun frameEnergies(pcm: StereoPcm): DoubleArray = frameEnergies(pcm.left, pcm.right)

    fun frameEnergies(
        left: FloatArray,
        right: FloatArray,
        hop: Int = LEVEL_HOP,
    ): DoubleArray {
        val n = left.size / hop
        return DoubleArray(n) { f ->
            var s = 0.0
            for (j in f * hop until (f + 1) * hop) {
                val l = left[j].toDouble()
                val r = right[j].toDouble()
                s += l * l + r * r
            }
            s / (2 * hop)
        }
    }

    /** numpy.median. */
    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sortedArray()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** djmix.loudness_gain: linear gain that puts the median of the loud frames at [targetDb]. */
    fun loudnessGain(
        energies: DoubleArray,
        targetDb: Double = -16.0,
    ): Double {
        if (energies.isEmpty()) return 1.0
        val db = DoubleArray(energies.size) { toDb(energies[it]) }
        val top = db.max()
        val loud = db.filter { it > top - 25 }.toDoubleArray()
        return 10.0.pow((targetDb - median(loud)) / 20)
    }

    /** Frame levels in dB after a linear [gain]. */
    fun levelsDb(
        energies: DoubleArray,
        gain: Double,
    ): DoubleArray = DoubleArray(energies.size) { toDb(energies[it] * gain * gain) }

    /** djmix.music_end, in samples: end of the last stretch within 9 dB of the usual loudness. */
    fun musicEnd(
        db: DoubleArray,
        totalSamples: Int,
    ): Int {
        if (db.isEmpty()) return totalSamples
        val top = db.max()
        val med = median(db.filter { it > top - 30 }.toDoubleArray())
        val last = db.indexOfLast { it > med - 9 }
        return if (last >= 0) last * LEVEL_HOP else totalSamples
    }

    /** djmix.first_sound, in samples: first frame above -45 dB (of the loudness-matched song). */
    fun firstSound(db: DoubleArray): Int {
        val i = db.indexOfFirst { it > -45 }
        return if (i >= 0) i * LEVEL_HOP else 0
    }

    /**
     * djmix5's "first full-strength part", in samples, or null: the first 50 ms frame from which
     * the 4 s smoothed level stays within 4 dB of the usual loudness for 300 of the next 320
     * frames (~94 % of 16 s). The smoothing pads with zeros like numpy's convolve(mode='same').
     */
    fun fullStrength(db: DoubleArray): Int? {
        val n = db.size
        if (n == 0) return null
        val top = db.max()
        val usual = median(db.filter { it > top - 30 }.toDoubleArray())
        // np.convolve(db, ones(80)/80, 'same'): out[i] = sum db[i - 40 + 1 .. i + 40] / 80 for even 80.
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + db[i]
        val loud = IntArray(n)
        for (i in 0 until n) {
            // numpy 'same' for an even kernel of 80 centres at offset (80 - 1) / 2 = 39 (rounded down):
            // out[i] = full[i + 39] = sum_{j = i - 40}^{i + 39} db[j].
            val a = max(0, i - 40)
            val b = min(n, i + 40)
            val lvl = (prefix[b] - prefix[a]) / 80
            loud[i] = if (lvl >= usual - 4) 1 else 0
        }
        val lp = IntArray(n + 1)
        for (i in 0 until n) lp[i + 1] = lp[i] + loud[i]
        for (i in 0 until n) {
            val run = lp[min(n, i + 320)] - lp[i]
            if (run >= 300) return i * LEVEL_HOP
        }
        return null
    }

    /**
     * djmix4.split3's FFT masks per STFT bin: low shelf below [lo] Hz and high shelf above [hi] Hz
     * (8th-order Butterworth-like magnitudes); the mid band is 1 - low - high.
     */
    fun bandMasks(
        nFft: Int,
        sampleRate: Int,
        lo: Double = 200.0,
        hi: Double = 2500.0,
    ): Pair<FloatArray, FloatArray> {
        val bins = nFft / 2 + 1
        val low = FloatArray(bins)
        val high = FloatArray(bins)
        for (b in 0 until bins) {
            val f = b.toDouble() * sampleRate / nFft
            low[b] = (1 / (1 + (f / lo).pow(8))).toFloat()
            high[b] = (1 / (1 + (hi / max(f, 1e-6)).pow(8))).toFloat()
        }
        return low to high
    }

    /** djmix5.echo: [taps] repeats [delay] samples apart, each [feedback] times the last, cut to x's length. */
    fun echo(
        x: FloatArray,
        delay: Int,
        feedback: Double,
        taps: Int,
    ): FloatArray {
        val out = FloatArray(x.size)
        var g = 1.0
        for (k in 1..taps) {
            g *= feedback
            val off = k * delay
            if (off >= x.size) break
            val gf = g.toFloat()
            for (i in 0 until x.size - off) out[i + off] += x[i] * gf
        }
        return out
    }

    /**
     * djmix5's ramp: 0..1 from beat [b0] to beat [b1] of the blend (smoothstep), as an equal-power
     * sine or linear, for each of [m] samples, with [beatSamples] per beat.
     */
    fun ramp(
        m: Int,
        beatSamples: Int,
        b0: Double,
        b1: Double,
        sine: Boolean,
    ): FloatArray {
        val start = b0 * beatSamples
        val len = max(1.0, (b1 - b0) * beatSamples)
        return FloatArray(m) { i ->
            val p = smoothstep((i - start) / len)
            (if (sine) sin(p * PI / 2) else p).toFloat()
        }
    }

    fun rmsDb(
        pcm: StereoPcm,
        from: Int,
        to: Int,
    ): Double {
        val a = max(0, from)
        val b = min(pcm.frames, to)
        if (b <= a) return toDb(0.0)
        var s = 0.0
        for (i in a until b) {
            val l = pcm.left[i].toDouble()
            val r = pcm.right[i].toDouble()
            s += l * l + r * r
        }
        return toDb(s / (2.0 * (b - a)))
    }

    /**
     * djmix5.ride: keep the blend's loudness on a straight line from [targetA] dB (A before) to
     * [targetB] dB (B after), within +-9 dB, measured in 0.5 s windows every 0.25 s, the gains
     * smoothed over three windows (zero-padded at the ends like numpy 'same') and interpolated per
     * sample. Applied in place.
     */
    fun ride(
        mix: StereoPcm,
        targetA: Double,
        targetB: Double,
        win: Int = SAMPLE_RATE / 2,
    ) {
        val n = mix.frames
        if (n == 0) return
        val step = win / 2
        val count = (n + step - 1) / step
        val centers = IntArray(count) { it * step }
        val gains =
            DoubleArray(count) { k ->
                val c = centers[k]
                val want = targetA + (targetB - targetA) * (c.toDouble() / n)
                (want - rmsDb(mix, c - win / 2, c + win / 2)).coerceIn(-9.0, 9.0)
            }
        val smooth =
            DoubleArray(count) { k ->
                var s = gains[k]
                if (k > 0) s += gains[k - 1]
                if (k < count - 1) s += gains[k + 1]
                s / 3
            }
        var k = 0
        for (i in 0 until n) {
            while (k < count - 1 && centers[k + 1] <= i) k++
            val g =
                if (k >= count - 1) {
                    smooth[count - 1]
                } else {
                    val f = (i - centers[k]).toDouble() / (centers[k + 1] - centers[k])
                    smooth[k] + (smooth[k + 1] - smooth[k]) * f
                }
            val lin = 10.0.pow(g / 20).toFloat()
            mix.left[i] *= lin
            mix.right[i] *= lin
        }
    }

    fun peak(pcm: StereoPcm): Float {
        var p = 0f
        for (i in 0 until pcm.frames) p = max(p, max(abs(pcm.left[i]), abs(pcm.right[i])))
        return p
    }

    /** Raised-cosine crossfade weight for position [i] of [n] (0 -> 1). */
    fun fadeIn(
        i: Int,
        n: Int,
    ): Float = (0.5 - 0.5 * cos(PI * (i + 0.5) / n)).toFloat()
}
