package com.maxrave.domain.mediaservice.djmix

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Musical key by Krumhansl-Schmuckler, like djmix2.analyse: a chroma vector averaged over the
 * song, correlated (Pearson) with the 24 rotated major/minor profiles. The chroma is built from
 * STFT bins instead of a constant-Q transform: per frame, each semitone from C1 to B7 gets the
 * root of the power in its band (a CQT bin's magnitude, roughly), semitones fold into 12 pitch
 * classes, and each frame is scaled to its loudest class (librosa's chroma norm).
 */
object KeyDetector {
    val NOTES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    class Key(
        val tonic: Int,
        val minor: Boolean,
        val confidence: Double,
    ) {
        val name: String get() = NOTES[tonic] + if (minor) "m" else ""
    }

    private const val RATE = 11_025
    private const val N_FFT = 8192
    private const val HOP = 2048
    private const val LOWEST_MIDI = 24 // C1
    private const val HIGHEST_MIDI = 107 // B7

    /** Mean chroma of mono audio at 22.05 kHz. */
    fun chroma(mono22k: FloatArray): DoubleArray {
        val y = Resampler.resample(mono22k, 22_050, RATE)
        val fft = RealFft(N_FFT)
        val window = hannPeriodic(N_FFT)
        val frame = DoubleArray(N_FFT)
        val re = DoubleArray(N_FFT / 2 + 1)
        val im = DoubleArray(N_FFT / 2 + 1)
        val binSemitone =
            IntArray(N_FFT / 2 + 1) { b ->
                val f = b.toDouble() * RATE / N_FFT
                if (f <= 0) -1 else (69 + 12 * log2(f / 440.0)).roundToInt().takeIf { it in LOWEST_MIDI..HIGHEST_MIDI } ?: -1
            }
        val semis = DoubleArray(128)
        val chroma = DoubleArray(12)
        val mean = DoubleArray(12)
        var frames = 0
        var start = 0
        while (start + N_FFT <= y.size || (frames == 0 && start == 0)) {
            for (i in 0 until N_FFT) {
                val j = start + i
                frame[i] = if (j < y.size) y[j] * window[i] else 0.0
            }
            fft.forward(frame, re, im)
            semis.fill(0.0)
            for (b in re.indices) {
                val s = binSemitone[b]
                if (s >= 0) semis[s] += re[b] * re[b] + im[b] * im[b]
            }
            chroma.fill(0.0)
            for (s in LOWEST_MIDI..HIGHEST_MIDI) chroma[s % 12] += sqrt(semis[s])
            val top = chroma.max()
            if (top > 1e-12) for (c in 0 until 12) mean[c] += chroma[c] / top
            frames++
            start += HOP
        }
        for (c in 0 until 12) mean[c] /= max(1, frames)
        return mean
    }

    fun detect(mono22k: FloatArray): Key = fromChroma(chroma(mono22k))

    fun fromChroma(chroma: DoubleArray): Key {
        var best: Key? = null
        for (k in 0 until 12) {
            for (minor in listOf(false, true)) {
                val prof = if (minor) MINOR else MAJOR
                // np.roll(prof, k): rolled[i] = prof[(i - k) mod 12]
                val rolled = DoubleArray(12) { prof[(it - k + 12) % 12] }
                val r = pearson(rolled, chroma)
                if (best == null || r > best.confidence) best = Key(k, minor, r)
            }
        }
        return best!!
    }

    private fun pearson(
        a: DoubleArray,
        b: DoubleArray,
    ): Double {
        val ma = a.average()
        val mb = b.average()
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in a.indices) {
            sab += (a[i] - ma) * (b[i] - mb)
            saa += (a[i] - ma) * (a[i] - ma)
            sbb += (b[i] - mb) * (b[i] - mb)
        }
        return if (saa <= 0 || sbb <= 0) 0.0 else sab / sqrt(saa * sbb)
    }

    /** Pitch class of the relative major, so relative major and minor count as one key. */
    fun tonicMajor(
        tonic: Int,
        minor: Boolean,
    ): Int = if (minor) (tonic + 3) % 12 else tonic

    /**
     * djmix2.key_shift: semitones (+1, -1 or 0) to move A so it sits with B, counting the same
     * key, relative major/minor and a fifth either way as compatible.
     */
    fun keyShift(
        aTonic: Int,
        aMinor: Boolean,
        bTonic: Int,
        bMinor: Boolean,
    ): Int {
        val d = ((tonicMajor(bTonic, bMinor) - tonicMajor(aTonic, aMinor)) % 12 + 12) % 12
        val ok = setOf(0, 5, 7)
        if (d in ok) return 0
        for (s in intArrayOf(1, -1)) if (((d - s) % 12 + 12) % 12 in ok) return s
        return 0
    }
}
