package com.maxrave.domain.mediaservice.djmix

/**
 * Harmonic/percussive separation by median filtering (Fitzgerald 2010), as librosa.decompose.hpss
 * does it: the magnitude spectrogram median-filtered across time (harmonic) and across frequency
 * (percussive), both with a [kernel]-long window and scipy's 'reflect' edges, turned into a soft
 * Wiener-like mask with [margin] (power 2).
 */
object Hpss {
    /**
     * The percussive soft mask, per frame and bin, of a [spec]trogram:
     * P^2 / (P^2 + (margin H)^2), 0 where both are zero. The harmonic part is everything else
     * (librosa's harmonic mask plus the residual its margin leaves out, which djmix5 adds back to
     * the harmonic part).
     */
    fun percussiveMask(
        spec: Spectrogram,
        kernel: Int = 31,
        margin: Double = 1.5,
    ): FloatArray {
        val frames = spec.frames
        val bins = spec.bins
        val mag = spec.magnitudes()
        val harm = FloatArray(mag.size)
        val perc = FloatArray(mag.size)
        val filter = SlidingMedian(kernel)
        // Harmonic: along time, for each bin.
        val seqIn = FloatArray(maxOf(frames, bins))
        val seqOut = FloatArray(maxOf(frames, bins))
        for (b in 0 until bins) {
            for (t in 0 until frames) seqIn[t] = mag[t * bins + b]
            filter.run(seqIn, frames, seqOut)
            for (t in 0 until frames) harm[t * bins + b] = seqOut[t]
        }
        // Percussive: along frequency, for each frame.
        for (t in 0 until frames) {
            mag.copyInto(seqIn, 0, t * bins, t * bins + bins)
            filter.run(seqIn, bins, seqOut)
            seqOut.copyInto(perc, t * bins, 0, bins)
        }
        val m2 = (margin * margin).toFloat()
        for (i in mag.indices) {
            val p = perc[i]
            val h = harm[i]
            val p2 = p * p
            val den = p2 + m2 * h * h
            harm[i] = if (den > 0f) p2 / den else 0f
        }
        return harm
    }
}

/** Median over a centred odd window with scipy.ndimage 'reflect' (half-sample symmetric) edges. */
internal class SlidingMedian(
    private val size: Int,
) {
    private val sorted = FloatArray(size)
    private var padded = FloatArray(0)

    init {
        require(size % 2 == 1)
    }

    fun run(
        x: FloatArray,
        n: Int,
        out: FloatArray,
    ) {
        val r = size / 2
        if (padded.size < n + 2 * r) padded = FloatArray(n + 2 * r)
        for (i in -r until n + r) padded[i + r] = x[reflect(i, n)]
        for (i in 0 until size) sorted[i] = padded[i]
        sorted.sort(0, size)
        out[0] = sorted[r]
        for (i in 1 until n) {
            remove(padded[i - 1])
            insert(padded[i - 1 + size])
            out[i] = sorted[r]
        }
    }

    private fun remove(v: Float) {
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < v) lo = mid + 1 else hi = mid
        }
        // lo is the first index with sorted[lo] >= v, which holds v.
        sorted.copyInto(sorted, lo, lo + 1, size)
    }

    private fun insert(v: Float) {
        // size - 1 values are live in sorted[0 until size - 1].
        var i = size - 1
        while (i > 0 && sorted[i - 1] > v) {
            sorted[i] = sorted[i - 1]
            i--
        }
        sorted[i] = v
    }

    private fun reflect(
        i: Int,
        n: Int,
    ): Int {
        if (n == 1) return 0
        var j = i
        val period = 2 * n
        j %= period
        if (j < 0) j += period
        return if (j < n) j else period - 1 - j
    }
}
