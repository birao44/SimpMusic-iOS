package com.maxrave.domain.mediaservice.djmix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DjDspTest {
    private val sr = DjDsp.SAMPLE_RATE

    @Test
    fun fftMatchesNaiveDftAndRoundTrips() {
        val n = 64
        val rnd = Random(1)
        val x = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val rf = RealFft(n)
        val re = DoubleArray(rf.bins)
        val im = DoubleArray(rf.bins)
        rf.forward(x, re, im)
        for (k in 0 until rf.bins) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                sr += x[t] * cos(2 * PI * k * t / n)
                si -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(sr, re[k], 1e-9)
            assertEquals(si, im[k], 1e-9)
        }
        val back = DoubleArray(n)
        rf.inverse(re, im, back)
        for (t in 0 until n) assertEquals(x[t], back[t], 1e-12)

        val big = RealFft(1 shl 14)
        val y = DoubleArray(big.size) { rnd.nextDouble(-1.0, 1.0) }
        val r2 = DoubleArray(big.bins)
        val i2 = DoubleArray(big.bins)
        big.forward(y, r2, i2)
        val y2 = DoubleArray(big.size)
        big.inverse(r2, i2, y2)
        var err = 0.0
        for (t in y.indices) err = max(err, abs(y[t] - y2[t]))
        assertTrue(err < 1e-10, "round trip error $err")
    }

    @Test
    fun istftReconstructsInput() {
        val rnd = Random(2)
        val x = FloatArray(30_011) { rnd.nextFloat() * 2 - 1 }
        val stft = Stft(4096, 1024)
        val spec = stft.forward(x)
        assertEquals(1 + x.size / 1024, spec.frames)
        val y = stft.synthesize(spec, x.size)
        var err = 0.0
        for (i in x.indices) err = max(err, abs((x[i] - y[i]).toDouble()))
        assertTrue(err < 1e-5, "ISTFT error $err")
        // Band masks are complementary: low + mid + high = input.
        val (wl, wh) = DjDsp.bandMasks(4096, sr)
        val mid = FloatArray(wl.size) { 1 - wl[it] - wh[it] }
        val sum = FloatArray(x.size)
        stft.inverseInto(spec, x.size, sum, band = wl)
        stft.inverseInto(spec, x.size, sum, band = mid)
        stft.inverseInto(spec, x.size, sum, band = wh)
        err = 0.0
        for (i in x.indices) err = max(err, abs((x[i] - sum[i]).toDouble()))
        assertTrue(err < 1e-4, "band split error $err")
    }

    @Test
    fun hpssSendsTonesToHarmonicAndClicksToPercussive() {
        val n = sr * 3
        val tone = FloatArray(n) { (0.3 * sin(2 * PI * 440 * it / sr)).toFloat() }
        val clicks = FloatArray(n) { if (it % (sr / 2) == 0) 0.9f else 0f }
        val stft = Stft(4096, 1024)
        fun percShare(x: FloatArray): Double {
            val spec = stft.forward(x)
            val mask = Hpss.percussiveMask(spec)
            val p = stft.synthesize(spec, n, perc = mask)
            val h = stft.synthesize(spec, n, perc = mask, harmonic = true)
            val ep = p.sumOf { it.toDouble() * it }
            val eh = h.sumOf { it.toDouble() * it }
            return ep / (ep + eh)
        }
        assertTrue(percShare(tone) < 0.05, "tone percussive share ${percShare(tone)}")
        assertTrue(percShare(clicks) > 0.8, "clicks percussive share ${percShare(clicks)}")
    }

    @Test
    fun slidingMedianMatchesSortWithReflectEdges() {
        val rnd = Random(3)
        val x = FloatArray(50) { rnd.nextFloat() }
        val out = FloatArray(50)
        SlidingMedian(7).run(x, 50, out)
        fun refl(i: Int) = if (i < 0) -i - 1 else if (i >= 50) 99 - i else i
        for (i in 0 until 50) {
            val w = (i - 3..i + 3).map { x[refl(it)] }.sorted()
            assertEquals(w[3], out[i])
        }
    }

    /** A music-like test signal: a chord with vibrato plus a kick and hat every half second. */
    private fun testSignal(seconds: Double): StereoPcm {
        val n = (seconds * sr).toInt()
        val l = FloatArray(n)
        val r = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var v = 0.0
            for ((k, f) in listOf(220.0, 277.18, 329.63, 440.0).withIndex()) {
                v += 0.08 / (k + 1) * sin(2 * PI * f * t + 0.3 * sin(2 * PI * 5 * t))
            }
            val beatT = t % 0.5
            v += 0.5 * sin(2 * PI * 60 * beatT) * kotlin.math.exp(-beatT * 18)
            val hatT = (t + 0.25) % 0.5
            v += 0.05 * sin(2 * PI * 7000 * hatT) * kotlin.math.exp(-hatT * 60)
            l[i] = v.toFloat()
            r[i] = (v * 0.8 + 0.05 * sin(2 * PI * 330 * t)).toFloat()
        }
        return StereoPcm(l, r)
    }

    private fun rmsDb(
        x: FloatArray,
        from: Int,
        to: Int,
    ): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return 10 * log10(s / (to - from) + 1e-20)
    }

    @Test
    fun stretchKeepsDurationLevelAndHasNoClicks() {
        val src = testSignal(12.0)
        val stretcher = TimeStretcher()
        val start = sr.toDouble()
        val end = 11.0 * sr
        val glide = 3.0 * sr
        // Hold 1.0, glide to 1.085 over 3 s, hold.
        val rate = { x: Double -> 1 + 0.085 * DjDsp.smoothstep((x - start - sr) / glide) }
        val res = stretcher.stretch(src, start, end, rate)
        // Expected output length: integral of 1 / rate over the input.
        var expected = 0.0
        var x = start
        while (x < end) {
            expected += 1 / rate(x + 0.5)
            x += 1.0
        }
        assertTrue(abs(res.pcm.frames - expected) < 2 * 512, "length ${res.pcm.frames} vs $expected")
        // Time map agrees with the rate.
        assertEquals(end, res.map.inputAt(res.pcm.frames.toDouble()), 600.0)
        // Before the glide the output is the input itself.
        for (i in 0 until sr / 2) assertEquals(src.left[start.toInt() + i], res.pcm.left[i], 1e-4f)
        // Level within 0.5 dB on the stretched part (same musical content, periodic).
        val inDb = rmsDb(src.left, 6 * sr, 10 * sr)
        val outFrom = res.map.outputAt(6.0 * sr).toInt()
        val outTo = res.map.outputAt(10.0 * sr).toInt()
        val outDb = rmsDb(res.pcm.left, outFrom, outTo)
        assertTrue(abs(inDb - outDb) < 0.5, "level in $inDb out $outDb")
        // No clicks: the largest sample step stays within what the input itself has.
        fun maxStep(a: FloatArray, from: Int, to: Int): Float {
            var m = 0f
            for (i in from + 1 until to) m = max(m, abs(a[i] - a[i - 1]))
            return m
        }
        val inStep = maxStep(src.left, sr, 11 * sr)
        val outStep = maxStep(res.pcm.left, 0, res.pcm.frames)
        assertTrue(outStep < inStep * 1.5f, "max step in $inStep out $outStep")
    }

    @Test
    fun stretchResyncsToTheInput() {
        val src = testSignal(10.0)
        val start = 0.5 * sr
        val res =
            TimeStretcher().stretch(
                src,
                start,
                9.0 * sr,
                tempo = { x -> if (x < 2.0 * sr) 1.06 else 1.06 + (1 - 1.06) * DjDsp.smoothstep((x - 2.0 * sr) / (2.0 * sr)) },
                silentBefore = true,
                resyncFrom = 4.0 * sr,
            )
        val off = res.identityOffset
        assertTrue(off != null, "no re-sync")
        var err = 0f
        for (i in res.identityFrom until res.pcm.frames) err = max(err, abs(res.pcm.left[i] - src.left[i + off]))
        assertTrue(err < 1e-4f, "identity error $err")
    }

    @Test
    fun pitchShiftMovesAToneBySemitone() {
        val n = 4 * sr
        val x = FloatArray(n) { (0.3 * sin(2 * PI * 440 * it / sr)).toFloat() }
        val src = StereoPcm(x, x.copyOf())
        val up = 2.0.let { kotlin.math.exp(kotlin.math.ln(it) / 12) }
        val res = TimeStretcher().stretch(src, 0.0, n.toDouble(), tempo = { 1.0 }, pitch = { up })
        assertTrue(abs(res.pcm.frames - n) < 1024, "duration ${res.pcm.frames}")
        // Zero crossings over the middle second.
        val y = res.pcm.left
        var crossings = 0
        for (i in sr + 1 until 2 * sr) if (y[i - 1] < 0 && y[i] >= 0) crossings++
        assertEquals(440 * up, crossings.toDouble(), 3.0)
        val lvl = sqrt(y.copyOfRange(sr, 3 * sr).sumOf { it.toDouble() * it } / (2 * sr))
        assertEquals(0.3 / sqrt(2.0), lvl, 0.02)
    }

    @Test
    fun beatTrackerFindsAClickTempo() {
        val rate = BeatTracker.SAMPLE_RATE
        val n = rate * 30
        val y = FloatArray(n)
        val period = 60.0 / 123.046875 * rate
        var t = 0.3 * rate
        while (t < n - 2000) {
            for (i in 0 until 1500) y[t.toInt() + i] += (0.8 * sin(2 * PI * 80 * i / rate) * kotlin.math.exp(-i / 300.0)).toFloat()
            t += period
        }
        val res = BeatTracker.track(y)
        assertEquals(123.046875, res.bpm, 0.01)
        val beats = res.beatSeconds
        assertTrue(beats.size > 50)
        val gaps = (1 until beats.size).map { beats[it] - beats[it - 1] }
        assertEquals(60 / 123.046875, gaps.average(), 0.01)
    }

    @Test
    fun keyOfAChordProgression() {
        val rate = 22_050
        val n = rate * 12
        // A minor: Am - F - C - G triads ... tonic A.
        val chords = listOf(listOf(57, 60, 64), listOf(53, 57, 60), listOf(57, 60, 64), listOf(52, 55, 59))
        val y = FloatArray(n)
        for (i in 0 until n) {
            val c = chords[(i / (rate * 3)) % 4]
            var v = 0.0
            for (m in c) {
                val f = 440.0 * kotlin.math.exp(kotlin.math.ln(2.0) * (m - 69) / 12)
                v += 0.1 * sin(2 * PI * f * i / rate) + 0.03 * sin(4 * PI * f * i / rate)
            }
            y[i] = v.toFloat()
        }
        val key = KeyDetector.detect(y)
        assertEquals("Am", key.name)
        assertEquals(0, KeyDetector.keyShift(9, true, 0, false)) // relative major
        assertEquals(1, KeyDetector.keyShift(0, false, 1, false)) // C -> C# : up one
    }
}
