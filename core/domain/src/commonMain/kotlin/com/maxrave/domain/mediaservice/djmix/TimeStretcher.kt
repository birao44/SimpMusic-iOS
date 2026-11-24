package com.maxrave.domain.mediaservice.djmix

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where each output sample of a stretch came from: the input position of every [step]-th output
 * sample, linearly interpolated in between.
 */
class TimeMap(
    val step: Int,
    private val positions: DoubleArray,
) {
    fun inputAt(output: Double): Double {
        val p = output / step
        val i = floor(p).toInt().coerceIn(0, positions.size - 2)
        return positions[i] + (positions[i + 1] - positions[i]) * (p - i)
    }

    /** The output position that reads input position [input] (inverse of [inputAt]). */
    fun outputAt(input: Double): Double {
        var lo = 0
        var hi = positions.size - 1
        if (input <= positions[0]) return (input - positions[0]) / max(1e-9, positions[1] - positions[0]) * step
        if (input >= positions[hi]) {
            val slope = (positions[hi] - positions[hi - 1]) / step
            return hi * step + (input - positions[hi]) / slope
        }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (positions[mid] <= input) lo = mid else hi = mid
        }
        val f = (input - positions[lo]) / (positions[hi] - positions[lo])
        return (lo + f) * step
    }
}

/**
 * Pitch-preserving time stretch with a tempo (and optionally pitch) that changes over time: a
 * phase vocoder with identity phase locking (Laroche & Dolson), phase reset on transients above
 * 200 Hz (like Rubber Band's "crisp" transients) and one phase rotation shared by both channels
 * (Rubber Band's channels=together), so the stereo image holds. Pitch moves are a stretch by
 * tempo / pitch followed by band-limited resampling by pitch.
 *
 * Rates are speeds: tempo 1.05 plays 5 % faster (output 1 / 1.05 as long), pitch 1.06 a
 * semitone up. Both are functions of the input position (in samples of [source]).
 */
class TimeStretcher(
    private val nFft: Int = 2048,
    private val hop: Int = 512,
) {
    class Result(
        val pcm: StereoPcm,
        val map: TimeMap,
        /**
         * Once phases were re-synchronised ([stretch]'s resyncFrom) and the rate is back to 1, the
         * output equals the input shifted by this many samples: output i = input (i + offset).
         * Null when no re-sync happened.
         */
        val identityOffset: Int?,
        /** First output sample from which [identityOffset] holds. */
        val identityFrom: Int,
    )

    private val bins = nFft / 2 + 1
    private val window = hannPeriodic(nFft)
    private val fft = RealFft(nFft)

    /**
     * Stretch [source] from input position [start] to [end].
     * - [silentBefore]: read the input before [start] as silence (B starts hard on its beat).
     * - [resyncFrom]: input position after which, at the first transient (or 1 s later at the
     *   latest) and once tempo and pitch are exactly 1, phases snap back to the input's, so the
     *   rest of the output is the input itself and can be spliced to it.
     */
    fun stretch(
        source: StereoPcm,
        start: Double,
        end: Double,
        tempo: (Double) -> Double,
        pitch: ((Double) -> Double)? = null,
        silentBefore: Boolean = false,
        resyncFrom: Double? = null,
    ): Result {
        val speed: (Double) -> Double = if (pitch == null) tempo else { x -> tempo(x) / pitch(x) }
        // Analysis positions of frames centred on u = k * hop, k from -LEAD.
        val lead = 3
        val xs = ArrayList<Double>()
        val s0 = speed(start)
        for (k in -lead..0) xs.add(start + k * hop * s0)
        while (xs.last() < end + nFft) {
            val x = xs.last()
            val mid = x + 0.5 * hop * speed(x)
            xs.add(x + hop * speed(mid))
        }
        val frameCount = xs.size
        val xpos = xs.toDoubleArray()
        // Intermediate length: where the frame positions pass [end].
        var kEnd = lead
        while (kEnd + 1 < frameCount && xpos[kEnd + 1] < end) kEnd++
        val frac = (end - xpos[kEnd]) / (xpos[kEnd + 1] - xpos[kEnd])
        val interLen = ceil((kEnd - lead + frac) * hop).toInt().coerceAtLeast(0)

        val lowestReset = (200.0 * nFft / source.sampleRate).toInt() + 1
        val outOffset = lead * hop + nFft / 2 // intermediate index of u = 0 in the OLA buffer
        val bufLen = outOffset + interLen + nFft + hop
        val olaL = FloatArray(bufLen)
        val olaR = FloatArray(bufLen)

        val frame = DoubleArray(nFft)
        val lRe = DoubleArray(bins)
        val lIm = DoubleArray(bins)
        val rRe = DoubleArray(bins)
        val rIm = DoubleArray(bins)
        val mag = DoubleArray(bins)
        val prevMag = DoubleArray(bins)
        val prevMRe = DoubleArray(bins)
        val prevMIm = DoubleArray(bins)
        val rot = DoubleArray(bins)
        val prevRot = DoubleArray(bins)
        val peaks = IntArray(bins)
        val tr = DoubleArray(bins)
        val ti = DoubleArray(bins)
        val lo = if (silentBefore) start.roundToInt() else 0
        val hi = source.frames
        var prevCentre = 0
        var prevRatio = 0.0
        var lastTransient = -1_000_000
        var resyncDone = false
        var identityOffset: Int? = null
        var identityFrom = Int.MAX_VALUE
        val resyncDeadline = resyncFrom?.let { it + source.sampleRate }

        val norm = (1.0 / 1.5).toFloat() // sum of squared Hann windows at 75 % overlap
        val maxFrame = min(frameCount, kEnd + 4)
        for (k in 0 until maxFrame) {
            val centre = xpos[k].roundToInt()
            val begin = centre - nFft / 2
            for (ch in 0..1) {
                val src = source.channel(ch)
                for (i in 0 until nFft) {
                    val j = begin + i
                    frame[i] = if (j in lo until hi) src[j] * window[i] else 0.0
                }
                if (ch == 0) fft.forward(frame, lRe, lIm) else fft.forward(frame, rRe, rIm)
            }
            // Mid spectrum carries the phase both channels follow.
            var rising = 0
            var counted = 0
            for (b in 0 until bins) {
                val mr = lRe[b] + rRe[b]
                val mi = lIm[b] + rIm[b]
                mag[b] = sqrt(mr * mr + mi * mi)
                if (mag[b] > 1e-6) {
                    counted++
                    if (mag[b] * mag[b] >= 2.0 * prevMag[b] * prevMag[b]) rising++
                }
            }
            val ratio = if (counted > 0) rising.toDouble() / counted else 0.0
            val transient = k > 0 && ratio > 0.35 && ratio > prevRatio && k - lastTransient > 3
            if (transient) lastTransient = k
            prevRatio = ratio

            val tempoHere = tempo(xpos[k])
            val pitchHere = pitch?.invoke(xpos[k]) ?: 1.0
            val unity = tempoHere == 1.0 && pitchHere == 1.0
            val resyncNow =
                !resyncDone && resyncFrom != null && unity && xpos[k] >= resyncFrom &&
                    (transient || xpos[k] >= resyncDeadline!!)

            if (k == 0 || resyncDone || resyncNow) {
                rot.fill(0.0)
                if (resyncNow) {
                    resyncDone = true
                    identityOffset = centre - (k - lead) * hop
                    // Frames before this one still overlap the next nFft samples.
                    identityFrom = (k - lead) * hop + nFft
                }
            } else {
                val ha = (centre - prevCentre).toDouble()
                var np = 0
                for (b in 2 until bins - 2) {
                    val m = mag[b]
                    if (m > 1e-6 && m > mag[b - 1] && m >= mag[b + 1] && m > mag[b - 2] && m >= mag[b + 2]) peaks[np++] = b
                }
                if (np == 0) {
                    rot.fill(0.0)
                } else {
                    for (q in 0 until np) {
                        val p = peaks[q]
                        val mr = lRe[p] + rRe[p]
                        val mi = lIm[p] + rIm[p]
                        val phi = atan2(mi, mr)
                        val phiPrev = atan2(prevMIm[p], prevMRe[p])
                        val omega = 2 * PI * p / nFft
                        val thetaPrev = phiPrev + prevRot[p]
                        val theta =
                            if (ha > 0) {
                                val dev = princarg(phi - phiPrev - omega * ha)
                                thetaPrev + (omega + dev / ha) * hop
                            } else {
                                thetaPrev + omega * hop
                            }
                        // Region of influence: halfway to the neighbouring peaks.
                        val from = if (q == 0) 0 else (peaks[q - 1] + p) / 2 + 1
                        val to = if (q == np - 1) bins - 1 else (p + peaks[q + 1]) / 2
                        val r = princarg(theta - phi)
                        for (b in from..to) rot[b] = r
                    }
                }
                if (transient) for (b in lowestReset until bins) rot[b] = 0.0
            }

            // Rotate both channels, inverse, window, overlap-add at u = (k - lead) * hop.
            val at = outOffset + (k - lead) * hop - nFft / 2
            for (ch in 0..1) {
                val re = if (ch == 0) lRe else rRe
                val im = if (ch == 0) lIm else rIm
                for (b in 0 until bins) {
                    val c = cos(rot[b])
                    val s = sin(rot[b])
                    tr[b] = re[b] * c - im[b] * s
                    ti[b] = re[b] * s + im[b] * c
                }
                fft.inverse(tr, ti, frame)
                val ola = if (ch == 0) olaL else olaR
                for (i in 0 until nFft) {
                    val j = at + i
                    if (j in 0 until bufLen) ola[j] += (frame[i] * window[i]).toFloat() * norm
                }
            }
            for (b in 0 until bins) {
                prevMag[b] = mag[b]
                prevMRe[b] = lRe[b] + rRe[b]
                prevMIm[b] = lIm[b] + rIm[b]
                prevRot[b] = rot[b]
            }
            prevCentre = centre
        }

        // Intermediate time -> input position, per frame.
        fun inputAtU(u: Double): Double {
            val p = u / hop + lead
            val i = floor(p).toInt().coerceIn(0, frameCount - 2)
            return xpos[i] + (xpos[i + 1] - xpos[i]) * (p - i)
        }

        val step = 64
        if (pitch == null) {
            val l = olaL.copyOfRange(outOffset, outOffset + interLen)
            val r = olaR.copyOfRange(outOffset, outOffset + interLen)
            val map = TimeMap(step, DoubleArray(interLen / step + 2) { inputAtU((it * step).toDouble()) })
            return Result(StereoPcm(l, r, source.sampleRate), map, identityOffset, identityFrom)
        }

        // Resample by pitch: output sample t reads intermediate u(t), du/dt = pitch(input(u)).
        val interp = SincInterpolator(cutoff = 0.92, zeroCrossings = 16)
        val outL = FloatArrayBuilder(interLen + 1024)
        val outR = FloatArrayBuilder(interLen + 1024)
        val marks = ArrayList<Double>()
        var u = 0.0
        var t = 0
        while (true) {
            val x = inputAtU(u)
            if (t % step == 0) marks.add(x)
            if (x >= end || u >= interLen) break
            outL.add(interp.at(olaL, u + outOffset))
            outR.add(interp.at(olaR, u + outOffset))
            u += pitch(x)
            t++
        }
        marks.add(inputAtU(u + step))
        return Result(
            StereoPcm(outL.toArray(), outR.toArray(), source.sampleRate),
            TimeMap(step, marks.toDoubleArray()),
            null,
            Int.MAX_VALUE,
        )
    }

    private fun princarg(a: Double): Double {
        var x = a % (2 * PI)
        if (x > PI) x -= 2 * PI
        if (x < -PI) x += 2 * PI
        return x
    }
}

internal class FloatArrayBuilder(
    capacity: Int,
) {
    private var data = FloatArray(max(16, capacity))
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
