package com.maxrave.domain.mediaservice.djmix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Band-limited interpolation with a Kaiser-windowed sinc read from a table. [cutoff] is the
 * pass band as a fraction of the input Nyquist (below 1 for decimation), [zeroCrossings] the
 * kernel's half width in units of 1 / cutoff input samples.
 */
class SincInterpolator(
    val cutoff: Double,
    private val zeroCrossings: Int = 24,
    private val resolution: Int = 256,
) {
    /** Kernel half width in input samples. */
    val halfWidth: Double = zeroCrossings / cutoff
    private val table: FloatArray

    init {
        val n = (halfWidth * resolution).toInt() + 2
        val beta = 8.6
        val i0b = besselI0(beta)
        table =
            FloatArray(n + 1) { k ->
                val d = k.toDouble() / resolution
                if (d > halfWidth) {
                    0f
                } else {
                    val x = d * cutoff
                    val s = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                    val r = d / halfWidth
                    val w = besselI0(beta * sqrt(max(0.0, 1 - r * r))) / i0b
                    (cutoff * s * w).toFloat()
                }
            }
    }

    private fun kernel(d: Double): Float {
        val p = abs(d) * resolution
        val i = p.toInt()
        if (i + 1 >= table.size) return 0f
        val f = (p - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * f
    }

    /** The value of [x] at fractional position [pos]; samples outside [x] count as zero. */
    fun at(
        x: FloatArray,
        pos: Double,
    ): Float {
        val lo = max(0, ceil(pos - halfWidth).toInt())
        val hi = min(x.size - 1, floor(pos + halfWidth).toInt())
        var s = 0f
        for (j in lo..hi) s += x[j] * kernel(pos - j)
        return s
    }

    companion object {
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (term > 1e-12 * sum) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}

object Resampler {
    /** [x] at [fromRate] resampled to [toRate] (anti-aliased when going down). */
    fun resample(
        x: FloatArray,
        fromRate: Int,
        toRate: Int,
    ): FloatArray {
        if (fromRate == toRate) return x.copyOf()
        val ratio = fromRate.toDouble() / toRate
        val cutoff = min(1.0, 1.0 / ratio) * 0.95
        val interp = SincInterpolator(cutoff, zeroCrossings = 16)
        val n = ceil(x.size / ratio).toInt()
        return FloatArray(n) { interp.at(x, it * ratio) }
    }
}
