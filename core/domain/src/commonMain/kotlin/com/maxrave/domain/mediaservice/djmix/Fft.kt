package com.maxrave.domain.mediaservice.djmix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 complex FFT of a fixed power-of-two [size], in place on separate real and imaginary
 * arrays. Twiddles and the bit-reversal table are built once per instance; an instance is not
 * thread-safe only in the sense that callers own the arrays they pass.
 */
class Fft(
    val size: Int,
) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val reversed: IntArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two: $size" }
        cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
        sinTable = DoubleArray(size / 2) { sin(2 * PI * it / size) }
        var bits = 0
        while (1 shl bits < size) bits++
        reversed =
            IntArray(size) { i ->
                var r = 0
                var v = i
                for (b in 0 until bits) {
                    r = (r shl 1) or (v and 1)
                    v = v shr 1
                }
                r
            }
    }

    /** Forward (e^-i) or inverse (e^+i, scaled by 1/size) transform. */
    fun transform(
        re: DoubleArray,
        im: DoubleArray,
        inverse: Boolean,
    ) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosTable[k]
                    val wi = sign * sinTable[k]
                    val a = i + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    k += step
                }
                i += len
            }
            len *= 2
        }
        if (inverse) {
            val s = 1.0 / size
            for (i in 0 until size) {
                re[i] *= s
                im[i] *= s
            }
        }
    }
}

/**
 * Real FFT of a power-of-two [size] through a complex FFT of half the size. The spectrum has
 * size / 2 + 1 bins (DC to Nyquist), like numpy's rfft; [inverse] undoes [forward] exactly.
 */
class RealFft(
    val size: Int,
) {
    val bins = size / 2 + 1
    private val half = size / 2
    private val fft = Fft(half)
    private val zr = DoubleArray(half)
    private val zi = DoubleArray(half)
    private val wr = DoubleArray(half + 1) { cos(2 * PI * it / size) }
    private val wi = DoubleArray(half + 1) { -sin(2 * PI * it / size) }

    /** [input] (length [size]) to [outRe] / [outIm] (length [bins]). */
    fun forward(
        input: DoubleArray,
        outRe: DoubleArray,
        outIm: DoubleArray,
    ) {
        for (k in 0 until half) {
            zr[k] = input[2 * k]
            zi[k] = input[2 * k + 1]
        }
        fft.transform(zr, zi, inverse = false)
        for (k in 0..half) {
            val a = k % half
            val b = (half - k) % half
            // E = (Z[k] + conj Z[M-k]) / 2, O = (Z[k] - conj Z[M-k]) / 2i
            val er = (zr[a] + zr[b]) * 0.5
            val ei = (zi[a] - zi[b]) * 0.5
            val or = (zi[a] + zi[b]) * 0.5
            val oi = -(zr[a] - zr[b]) * 0.5
            outRe[k] = er + wr[k] * or - wi[k] * oi
            outIm[k] = ei + wr[k] * oi + wi[k] * or
        }
    }

    /** Half spectrum ([bins]) back to [size] real samples. */
    fun inverse(
        inRe: DoubleArray,
        inIm: DoubleArray,
        output: DoubleArray,
    ) {
        for (k in 0 until half) {
            val xr = inRe[k]
            val xi = inIm[k]
            val yr = inRe[half - k]
            val yi = -inIm[half - k]
            val er = (xr + yr) * 0.5
            val ei = (xi + yi) * 0.5
            val dr = (xr - yr) * 0.5
            val di = (xi - yi) * 0.5
            // O = D * e^{+2 pi i k / n}  (conjugate of the forward twiddle)
            val c = wr[k]
            val s = -wi[k]
            val or = dr * c - di * s
            val oi = dr * s + di * c
            zr[k] = er - oi
            zi[k] = ei + or
        }
        fft.transform(zr, zi, inverse = true)
        for (k in 0 until half) {
            output[2 * k] = zr[k]
            output[2 * k + 1] = zi[k]
        }
    }
}

internal fun nextPowerOfTwo(n: Int): Int {
    var p = 1
    while (p < n) p = p shl 1
    return p
}
