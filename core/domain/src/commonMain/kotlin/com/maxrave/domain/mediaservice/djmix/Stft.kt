package com.maxrave.domain.mediaservice.djmix

import kotlin.math.PI
import kotlin.math.cos

/** Periodic Hann window (scipy get_window('hann', n, fftbins=True)). */
internal fun hannPeriodic(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }

/** A complex spectrogram, frame-major: bin b of frame t lives at index t * bins + b. */
class Spectrogram(
    val frames: Int,
    val bins: Int,
) {
    val re = FloatArray(frames * bins)
    val im = FloatArray(frames * bins)

    fun magnitudes(): FloatArray =
        FloatArray(re.size) { i ->
            val r = re[i]
            val m = im[i]
            kotlin.math.sqrt(r * r + m * m)
        }
}

/**
 * Short-time Fourier transform matching librosa's defaults: periodic Hann window, frames centred
 * on multiples of [hop] with the signal zero-padded by nFft / 2 at both ends, so a signal of
 * length L has 1 + L / hop frames; the inverse overlap-adds and divides by the summed squared
 * window, which reconstructs the input exactly.
 */
class Stft(
    val nFft: Int,
    val hop: Int,
) {
    val bins = nFft / 2 + 1
    private val window = hannPeriodic(nFft)
    private val fft = RealFft(nFft)
    private val frame = DoubleArray(nFft)
    private val specRe = DoubleArray(bins)
    private val specIm = DoubleArray(bins)

    fun frameCount(length: Int): Int = 1 + length / hop

    /** Spectrogram of x[offset until offset + length]; samples outside that range count as zero. */
    fun forward(
        x: FloatArray,
        offset: Int = 0,
        length: Int = x.size - offset,
    ): Spectrogram {
        val frames = frameCount(length)
        val out = Spectrogram(frames, bins)
        val halfN = nFft / 2
        for (t in 0 until frames) {
            val start = t * hop - halfN
            for (i in 0 until nFft) {
                val j = start + i
                frame[i] = if (j in 0 until length) x[offset + j] * window[i] else 0.0
            }
            fft.forward(frame, specRe, specIm)
            val base = t * bins
            for (b in 0 until bins) {
                out.re[base + b] = specRe[b].toFloat()
                out.im[base + b] = specIm[b].toFloat()
            }
        }
        return out
    }

    /**
     * Inverse of [forward] to [length] samples, with every bin weighted by
     * band[b] * (perc[t,b] or 1 - perc[t,b] when [harmonic]); no [perc] means no split.
     * The result is added into [out] (from [outOffset]) times [gain] (per output sample, or 1).
     */
    fun inverseInto(
        spec: Spectrogram,
        length: Int,
        out: FloatArray,
        outOffset: Int = 0,
        band: FloatArray? = null,
        perc: FloatArray? = null,
        harmonic: Boolean = false,
        gain: FloatArray? = null,
    ) {
        val y = synthesize(spec, length, band, perc, harmonic)
        if (gain == null) {
            for (i in 0 until length) out[outOffset + i] += y[i]
        } else {
            for (i in 0 until length) out[outOffset + i] += y[i] * gain[i]
        }
    }

    /** Inverse of [forward] to [length] samples (see [inverseInto] for the weights). */
    fun synthesize(
        spec: Spectrogram,
        length: Int,
        band: FloatArray? = null,
        perc: FloatArray? = null,
        harmonic: Boolean = false,
    ): FloatArray {
        val halfN = nFft / 2
        val padded = DoubleArray(length + nFft)
        val norm = DoubleArray(length + nFft)
        for (t in 0 until spec.frames) {
            val base = t * bins
            for (b in 0 until bins) {
                var w = band?.get(b)?.toDouble() ?: 1.0
                if (perc != null) {
                    val p = perc[base + b].toDouble()
                    w *= if (harmonic) 1.0 - p else p
                }
                specRe[b] = spec.re[base + b] * w
                specIm[b] = spec.im[base + b] * w
            }
            fft.inverse(specRe, specIm, frame)
            val start = t * hop
            for (i in 0 until nFft) {
                val j = start + i
                if (j >= padded.size) break
                padded[j] += frame[i] * window[i]
                norm[j] += window[i] * window[i]
            }
        }
        val y = FloatArray(length)
        for (i in 0 until length) {
            val n = norm[i + halfN]
            y[i] = if (n > 1e-10) (padded[i + halfN] / n).toFloat() else 0f
        }
        return y
    }
}
