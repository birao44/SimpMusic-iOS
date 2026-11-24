package com.maxrave.domain.mediaservice.djmix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How the rendered PCM relates to the songs' own levels. */
enum class DjLevelMode {
    /**
     * Both songs loudness-matched (A times its loudnessGain, B times its own), as djmix5 renders:
     * the player must then play A and B at those gains too.
     */
    NORMALIZED,

    /**
     * Starts at A's own level and ends at B's own level (the loudness match fades from one to the
     * other across the blend), so A[0 : aStart] + rendered + B[bResume :] needs no gain at all.
     */
    RAW_CONTINUOUS,
}

data class DjTransitionOptions(
    /**
     * Mid-song (skip) transition: A's tempo glide starts at this second of A (at the first beat
     * that allows it) instead of 8 beats + the blend before A's music end.
     */
    val skipAtSec: Double? = null,
    /** Blend length in beats at the common tempo for matched tempos (default 32). */
    val blendBeats: Int? = null,
    /** Length of the breakdown blend (djmix6) used when the tempos cannot be matched. */
    val unmatchedSecs: Double = 20.0,
    /** Largest tempo bend per song (djmix3.MAX_BEND). */
    val maxBend: Double = 0.085,
    /** Let A move up to one semitone toward a key compatible with B's. */
    val keyShift: Boolean = true,
    val levelMode: DjLevelMode = DjLevelMode.RAW_CONTINUOUS,
    /** Soft peak limiter at -0.2 dBFS on the rendered segment. */
    val limiter: Boolean = true,
)

/** Every decision of a transition, from the two analyses alone (no audio needed). */
data class DjTransitionPlan(
    val matched: Boolean,
    val tempoA: Double,
    /** B's tempo folded by half/double toward A's. */
    val tempoB: Double,
    val commonTempo: Double,
    val rateA: Double,
    val rateB: Double,
    val semitones: Int,
    val beatSec: Double,
    val blendBeats: Int,
    val blendSamples: Int,
    /** First A sample of the rendered segment (A's glide starts here). */
    val aStart: Int,
    /** A sample (input) where the blend starts. */
    val aOverStart: Double,
    /** A sample (input) of A's last played beat. */
    val aExit: Int,
    /** B sample of B's first played beat. */
    val bStart: Int,
    /** Where in the blend the track identity moves to B (B's melody is in), in samples. */
    val identityInBlend: Int,
    /** Song ranges [from, to) the renderer reads. */
    val aWindowFrom: Int,
    val aWindowTo: Int,
    val bWindowFrom: Int,
    val bWindowTo: Int,
)

class RenderedTransition(
    val pcm: StereoPcm,
    /** Play A[0 : aStartFrame], then [pcm], then B from [bResumeFrame]. */
    val aStartFrame: Int,
    val bResumeFrame: Int,
    /** Where the blend (both songs audible) starts in [pcm], and how long it lasts. */
    val blendStart: Int,
    val blendLength: Int,
    /** Frame of [pcm] where the track identity moves to B. */
    val identityFrame: Int,
    val plan: DjTransitionPlan,
    val aGain: Double,
    val bGain: Double,
    val levelMode: DjLevelMode,
    val summary: String,
)

/**
 * Port of djmix5 (the approved "stems + echo out" DJ transition):
 * - both songs loudness-matched; tempos meet in the geometric middle (each bends at most 8.5 %),
 *   A gliding there over the 8 beats before the blend, B playing at the common tempo through the
 *   blend and gliding back to its own over 8 beats after; A may shift a semitone toward B's key;
 * - B's first beat (moved later so B reaches its first full-strength part around beat 26) locks
 *   onto the A beat nearest the blend start;
 * - both blend windows split into harmonic/percussive (median-filter HPSS) and three bands;
 *   B's drums rise over beats 0-16, the bass swaps at 16-17, A's drums leave over 17-24, A's
 *   melody goes into a 3/4-beat echo (send 19-21, cut 24-25) while its dry signal fades 21-25,
 *   B's melody comes in 22-26;
 * - tempos too far apart (no bend possible): djmix6's breakdown blend instead, see [blendUnmatched];
 * - the level rides a line from A's level before to B's after.
 */
object DjTransitionRenderer {
    private const val SR = DjDsp.SAMPLE_RATE
    private const val HPSS_FFT = 4096
    private const val HPSS_HOP = 1024
    private const val SPLICE = 1024

    /** Unmatched: untouched A in the segment before the blend starts. */
    private const val UNMATCHED_LEAD_SEC = 2.0

    /** Unmatched: identity switch to B, in seconds of a 20 s blend (scaled with it). */
    private const val UNMATCHED_IDENTITY_SEC = 8.0

    fun plan(
        a: DjTrackAnalysis,
        b: DjTrackAnalysis,
        options: DjTransitionOptions = DjTransitionOptions(),
    ): DjTransitionPlan {
        val ta = a.bpm
        val tb = listOf(0.5, 1.0, 2.0).map { b.bpm * it }.minBy { abs(it / ta - 1) }
        var common = sqrt(ta * tb)
        var ra = common / ta
        var rb = common / tb
        val matched = abs(ra - 1) <= options.maxBend && abs(rb - 1) <= options.maxBend
        if (!matched) {
            ra = 1.0
            rb = 1.0
            common = ta
        }
        val semis = if (matched && options.keyShift) KeyDetector.keyShift(a.keyTonic, a.keyMinor, b.keyTonic, b.keyMinor) else 0
        val beat = 60 / common
        val blend = if (matched) (options.blendBeats ?: 32) * beat else options.unmatchedSecs
        val blendBeats = if (matched) options.blendBeats ?: 32 else (blend / beat).roundToInt()
        // Untouched A before the blend: A's 8-beat tempo glide, or a short lead-in when unmatched.
        val glideA = if (matched) 8 * 60 / ta else UNMATCHED_LEAD_SEC

        val beatsA = a.beats
        var exitT: Double
        val skip = options.skipAtSec
        if (skip == null) {
            exitT = a.musicEndSec
            if (beatsA.isNotEmpty()) exitT = beatsA.minBy { abs(it - exitT) }
        } else {
            val want = skip + glideA + blend * ra
            // Not before the requested start: the player is already past anything earlier.
            exitT = beatsA.firstOrNull { it >= want } ?: want
        }
        val overStart = exitT - blend * ra
        val bendStart = max(0.0, overStart - glideA)

        val beatsB = b.beats
        val fs = b.firstSoundSec
        val full = b.fullStrengthSec
        var b0: Double
        if (matched) {
            b0 = beatsB.firstOrNull { it >= fs } ?: fs
            if (full != null && beatsB.isNotEmpty()) {
                val want = full - 26 * beat * rb
                beatsB.lastOrNull { it >= b0 && it <= max(b0, want) }?.let { b0 = it }
            }
        } else {
            // djmix6: the end of B's intro plays under A, so B reaches full strength as the blend ends.
            val want = max(fs, (full ?: 0.0) - blend * 0.85)
            b0 = beatsB.firstOrNull { it >= want - 0.3 } ?: want
        }
        val aStart = (bendStart * SR).roundToInt()
        val aExit = (exitT * SR).toInt()
        val bStart = (b0 * SR).toInt()
        val n = (blend * SR).toInt()
        val bNeedSec = if (matched) (blend + 2 * beat) * rb + 8 * 60 / tb + 7.0 else blend + 7.0
        // Matched: B's melody is in by beat 24 of 32. Unmatched: at 8 s (of 20) B's melody is near
        // full, just before A's starts washing out.
        val identity = if (matched) n * 24 / blendBeats else (UNMATCHED_IDENTITY_SEC * blend / 20.0 * SR).toInt()
        return DjTransitionPlan(
            matched = matched,
            tempoA = ta,
            tempoB = tb,
            commonTempo = common,
            rateA = ra,
            rateB = rb,
            semitones = semis,
            beatSec = beat,
            blendBeats = blendBeats,
            blendSamples = n,
            aStart = aStart,
            aOverStart = overStart * SR,
            aExit = aExit,
            bStart = bStart,
            identityInBlend = identity.coerceIn(0, n),
            aWindowFrom = max(0, aStart - 5 * SR),
            aWindowTo = min(a.totalFrames.toInt(), aExit + SR),
            bWindowFrom = bStart,
            bWindowTo = min(b.totalFrames.toInt(), bStart + (bNeedSec * SR).toInt()),
        )
    }

    /** Render from whole decoded songs (convenience; reads only the plan's windows). */
    fun render(
        a: StereoPcm,
        b: StereoPcm,
        aAnalysis: DjTrackAnalysis,
        bAnalysis: DjTrackAnalysis,
        options: DjTransitionOptions = DjTransitionOptions(),
    ): RenderedTransition {
        val plan = plan(aAnalysis, bAnalysis, options)
        return render(
            plan,
            a.slice(plan.aWindowFrom, plan.aWindowTo),
            b.slice(plan.bWindowFrom, plan.bWindowTo),
            aAnalysis,
            bAnalysis,
            options,
        )
    }

    /**
     * Render from the two windows the [plan] asks for: A[aWindowFrom, aWindowTo) and
     * B[bWindowFrom, bWindowTo) (shorter windows near a song's edge are fine).
     */
    fun render(
        plan: DjTransitionPlan,
        aWindow: StereoPcm,
        bWindow: StereoPcm,
        aAnalysis: DjTrackAnalysis,
        bAnalysis: DjTrackAnalysis,
        options: DjTransitionOptions = DjTransitionOptions(),
    ): RenderedTransition {
        val gA = aAnalysis.loudnessGain
        val gB = bAnalysis.loudnessGain
        val aw = scaled(aWindow, gA)
        val bw = scaled(bWindow, gB)
        val n = plan.blendSamples
        val stretcher = TimeStretcher()

        // --- A: untouched until its glide, then at the common tempo (and maybe a semitone off).
        val la = (plan.aStart - plan.aWindowFrom).toDouble()
        val le = (plan.aExit - plan.aWindowFrom).toDouble()
        val glideA = 8 * 60 / plan.tempoA * SR
        val stretchA = plan.matched && (abs(plan.rateA - 1) > 0.002 || plan.semitones != 0)
        val aOut: StereoPcm
        val aOutputAt: (Double) -> Double
        if (stretchA) {
            val ra = plan.rateA
            val pitchTo = 2.0.pow(plan.semitones / 12.0)
            val res =
                stretcher.stretch(
                    aw,
                    la,
                    le,
                    tempo = { x -> 1 + (ra - 1) * DjDsp.smoothstep((x - la) / glideA) },
                    pitch = if (plan.semitones != 0) ({ x -> 1 + (pitchTo - 1) * DjDsp.smoothstep((x - la) / glideA) }) else null,
                )
            aOut = res.pcm
            aOutputAt = { x -> res.map.outputAt(x) }
            // The pitch path's interpolation filter is not bit-exact at rate 1: blend in from raw A.
            val start = la.roundToInt()
            for (i in 0 until min(SPLICE, aOut.frames)) {
                val w = DjDsp.fadeIn(i, SPLICE)
                aOut.left[i] = aw.left[start + i] * (1 - w) + aOut.left[i] * w
                aOut.right[i] = aw.right[start + i] * (1 - w) + aOut.right[i] * w
            }
        } else {
            aOut = aw.slice(la.roundToInt(), le.toInt())
            aOutputAt = { x -> x - la.roundToInt() }
        }
        val overW = aOut.frames - n
        require(overW >= 0) { "A segment shorter than the blend" }

        // Anchor: the A beat nearest the blend start, in the rendered timeline.
        var anchor = overW.toDouble()
        var bestDist = Double.MAX_VALUE
        for (t in aAnalysis.beats) {
            val pos = aOutputAt(t * SR - plan.aWindowFrom)
            val d = abs(pos - overW)
            if (d < bestDist) {
                bestDist = d
                anchor = pos
            }
        }
        // Unmatched: the tempos never line up, so B simply starts with the blend.
        val lead = if (plan.matched) anchor.toInt() - overW else 0

        // --- B: from its first beat at the common tempo, gliding back to its own after the blend.
        val lb = (plan.bStart - plan.bWindowFrom).toDouble()
        val rb = plan.rateB
        val inBlend = (n + abs(lead)).toDouble() / SR * rb
        val glideB = 8 * 60 / plan.tempoB
        val stretchB = plan.matched && abs(rb - 1) > 0.002
        val bEnd = min(bw.frames.toDouble(), lb + max(inBlend + glideB + 2.5, inBlend + 5.0) * SR)
        val bOut: StereoPcm
        var identityOffset: Int
        var identityFrom: Int
        if (stretchB) {
            val res =
                stretcher.stretch(
                    bw,
                    lb,
                    bEnd,
                    tempo = { x ->
                        val rel = (x - lb) / SR
                        if (rel < inBlend) rb else rb + (1 - rb) * DjDsp.smoothstep((rel - inBlend) / glideB)
                    },
                    silentBefore = true,
                    resyncFrom = lb + (inBlend + glideB) * SR,
                )
            bOut = res.pcm
            if (res.identityOffset != null && res.identityFrom + SPLICE <= bOut.frames) {
                identityOffset = res.identityOffset
                identityFrom = res.identityFrom
            } else {
                // No clean re-sync: splice at the end, aligned by the time map.
                identityFrom = max(0, bOut.frames - SPLICE)
                identityOffset = (res.map.inputAt(identityFrom.toDouble()) - identityFrom).roundToInt()
            }
        } else {
            bOut = bw.slice(lb.roundToInt(), bEnd.toInt())
            identityOffset = lb.roundToInt()
            identityFrom = 0
        }

        fun bAt(
            ch: Int,
            o: Int,
        ): Float {
            if (o < 0) return 0f
            if (o < bOut.frames) return bOut.channel(ch)[o]
            val i = o + identityOffset
            return if (i in 0 until bw.frames) bw.channel(ch)[i] else 0f
        }

        // --- The blend.
        val aBlend = aOut.slice(overW, overW + n)
        val bBlend =
            StereoPcm(
                FloatArray(n) { bAt(0, it - lead) },
                FloatArray(n) { bAt(1, it - lead) },
            )
        val mix = if (plan.matched) blend(aBlend, bBlend, plan) else blendUnmatched(aBlend, bBlend, plan, options.unmatchedSecs)

        // Ride the level from A's 4 s before the blend to B's 4 s after it.
        var energyA = 0.0
        var countA = 0
        for (i in max(0, overW - 4 * SR) until overW) {
            energyA += sq(aOut.left[i]) + sq(aOut.right[i])
            countA++
        }
        val rawBefore = 4 * SR - countA
        val startRaw = la.roundToInt()
        for (i in max(0, startRaw - rawBefore) until startRaw) {
            energyA += sq(aw.left[i]) + sq(aw.right[i])
            countA++
        }
        var energyB = 0.0
        val restFrom = n - lead
        for (o in restFrom until restFrom + 4 * SR) energyB += sq(bAt(0, o)) + sq(bAt(1, o))
        val targetA = DjDsp.toDb(energyA / (2.0 * max(1, countA)))
        val targetB = DjDsp.toDb(energyB / (2.0 * 4 * SR))
        DjDsp.ride(mix, targetA, targetB)

        // --- Assemble: A's glide, the blend, B until its stretch is back in phase with B.
        val splice = if (stretchB) max(identityFrom, restFrom) else restFrom
        val fade = if (stretchB) SPLICE else 0
        val tailLen = splice - restFrom + fade
        val total = overW + n + tailLen
        val outL = FloatArray(total)
        val outR = FloatArray(total)
        aOut.left.copyInto(outL, 0, 0, overW)
        aOut.right.copyInto(outR, 0, 0, overW)
        mix.left.copyInto(outL, overW)
        mix.right.copyInto(outR, overW)
        for (j in 0 until tailLen) {
            val o = restFrom + j
            var l = bAt(0, o)
            var r = bAt(1, o)
            if (o >= splice) {
                val w = DjDsp.fadeIn(o - splice, fade)
                val i = o + identityOffset
                val rl = if (i in 0 until bw.frames) bw.left[i] else 0f
                val rr = if (i in 0 until bw.frames) bw.right[i] else 0f
                l = l * (1 - w) + rl * w
                r = r * (1 - w) + rr * w
            }
            outL[overW + n + j] = l
            outR[overW + n + j] = r
        }
        val out = StereoPcm(outL, outR)
        val bResume = plan.bWindowFrom + splice + fade + identityOffset

        if (options.levelMode == DjLevelMode.RAW_CONTINUOUS) {
            val dbA = -DjDsp.toDb(gA * gA)
            val dbB = -DjDsp.toDb(gB * gB)
            for (i in 0 until total) {
                val p = ((i - overW).toDouble() / n).coerceIn(0.0, 1.0)
                val g = 10.0.pow((dbA + (dbB - dbA) * p) / 20).toFloat()
                outL[i] *= g
                outR[i] *= g
            }
        }
        if (options.limiter) limit(out, 0.98f)

        val how =
            if (plan.matched) {
                "both meet at ${fmt(plan.commonTempo)} bpm (A x${fmt(plan.rateA, 3)}, B x${fmt(plan.rateB, 3)})"
            } else {
                "tempo gap too big, breakdown blend"
            }
        val summary =
            "A ${fmt(plan.tempoA)} bpm ${aAnalysis.keyName} -> B ${fmt(bAnalysis.bpm)} bpm ${bAnalysis.keyName}; $how; " +
                "key shift ${plan.semitones}; blend ${plan.blendBeats} beats (${fmt(n.toDouble() / SR)} s) " +
                "from ${fmt(overW.toDouble() / SR)} s into the segment; B from ${fmt(plan.bStart.toDouble() / SR)} s, lead ${lead} samples"
        return RenderedTransition(
            pcm = out,
            aStartFrame = plan.aStart,
            bResumeFrame = bResume,
            blendStart = overW,
            blendLength = n,
            identityFrame = overW + plan.identityInBlend,
            plan = plan,
            aGain = gA,
            bGain = gB,
            levelMode = options.levelMode,
            summary = summary,
        )
    }

    /** djmix5's stem schedule over the blend windows (both [n] long, loudness-matched). */
    private fun blend(
        a: StereoPcm,
        b: StereoPcm,
        plan: DjTransitionPlan,
    ): StereoPcm {
        val m = a.frames
        val beatS = (plan.beatSec * SR).toInt()

        fun ramp(
            b0: Double,
            b1: Double,
            sine: Boolean = true,
        ) = DjDsp.ramp(m, beatS, b0, b1, sine)

        val bDrums = ramp(0.0, 16.0)
        val bassB = ramp(16.0, 17.0)
        val aDry = cosOf(ramp(21.0, 25.0, false))
        val up = ramp(19.0, 21.0, false)
        val cut = ramp(24.0, 25.0, false)
        val aSend = FloatArray(m) { up[it] * (1 - cut[it]) }
        val aDrums = cosOf(ramp(17.0, 24.0, false))
        val bMel = ramp(22.0, 26.0)
        val bassA = FloatArray(m) { sqrt(max(0f, 1 - bassB[it] * bassB[it])) }
        val gALow = FloatArray(m) { bassA[it] * aDrums[it] }
        val gBLowDrums = FloatArray(m) { bassB[it] * max(bDrums[it], bMel[it]) }
        val gBLowMel = FloatArray(m) { bassB[it] * bMel[it] }

        val stft = Stft(HPSS_FFT, HPSS_HOP)
        val (wl, wh) = DjDsp.bandMasks(HPSS_FFT, SR)
        val bins = stft.bins
        val upper = FloatArray(bins) { 1 - wl[it] } // mid + high
        val echoBand = FloatArray(bins) { 1 - 0.5f * wl[it] } // mid + high + half the low
        val dark = FloatArray(bins) { 1 - 0.4f * wl[it] - wh[it] } // 0.6 low + mid
        val delay = (0.75 * plan.beatSec * SR).toInt()

        val outL = FloatArray(m)
        val outR = FloatArray(m)
        for (ch in 0..1) {
            val out = if (ch == 0) outL else outR
            // A
            val specA = stft.forward(a.channel(ch))
            val maskA = Hpss.percussiveMask(specA)
            stft.inverseInto(specA, m, out, band = wl, perc = maskA, harmonic = true, gain = bassA)
            stft.inverseInto(specA, m, out, band = wl, perc = maskA, harmonic = false, gain = gALow)
            stft.inverseInto(specA, m, out, band = upper, perc = maskA, harmonic = true, gain = aDry)
            stft.inverseInto(specA, m, out, band = upper, perc = maskA, harmonic = false, gain = aDrums)
            val send = stft.synthesize(specA, m, band = echoBand, perc = maskA, harmonic = true)
            for (i in 0 until m) send[i] *= aSend[i]
            val tail = DjDsp.echo(send, delay, 0.5, 8)
            stft.inverseInto(stft.forward(tail), m, out, band = dark)
            // B
            val specB = stft.forward(b.channel(ch))
            val maskB = Hpss.percussiveMask(specB)
            stft.inverseInto(specB, m, out, band = upper, perc = maskB, harmonic = false, gain = bDrums)
            stft.inverseInto(specB, m, out, band = wl, perc = maskB, harmonic = false, gain = gBLowDrums)
            stft.inverseInto(specB, m, out, band = wl, perc = maskB, harmonic = true, gain = gBLowMel)
            stft.inverseInto(specB, m, out, band = upper, perc = maskB, harmonic = true, gain = bMel)
        }
        return StereoPcm(outL, outR)
    }

    /**
     * djmix6's breakdown blend for tempos too far apart to beat-match (times in seconds of a 20 s
     * blend, scaled by S = [secs] / 20; ramps are smoothstep-then-sine, "out" is cos of that):
     * A's drums out 0-4, A's bass out 5-10, B's melody/intro in 1.5-12, A's melody out 9-17 into a
     * 3/4-beat echo (send in 8-10, off 16-17; feedback 0.55, 10 repeats, darkened: half the low,
     * 0.9 of the mids, no highs), B's bass in 9-13 (times B's melody gain), B's drums in 13-17.5
     * (their low band also times B's bass). Only one song's drums ever play.
     */
    private fun blendUnmatched(
        a: StereoPcm,
        b: StereoPcm,
        plan: DjTransitionPlan,
        secs: Double,
    ): StereoPcm {
        val m = a.frames
        val sc = secs / 20.0

        // In seconds (one "beat" of DjDsp.ramp = one second here).
        fun ramp(
            t0: Double,
            t1: Double,
        ) = DjDsp.ramp(m, SR, t0 * sc, t1 * sc, true)

        val aDrums = cosOf(ramp(0.0, 4.0))
        val aBass = cosOf(ramp(5.0, 10.0))
        val bMel = ramp(1.5, 12.0)
        val aMel = cosOf(ramp(9.0, 17.0))
        val sendUp = ramp(8.0, 10.0)
        val sendOff = ramp(16.0, 17.0)
        val aSend = FloatArray(m) { sendUp[it] * (1 - sendOff[it]) }
        val bBass = ramp(9.0, 13.0)
        val bDrums = ramp(13.0, 17.5)
        val gBLowMel = FloatArray(m) { bBass[it] * max(bMel[it], 0f) }
        val gBLowDrums = FloatArray(m) { bDrums[it] * bBass[it] }

        val stft = Stft(HPSS_FFT, HPSS_HOP)
        val (wl, wh) = DjDsp.bandMasks(HPSS_FFT, SR)
        val bins = stft.bins
        val upper = FloatArray(bins) { 1 - wl[it] } // mid + high
        val dark = FloatArray(bins) { 0.5f * wl[it] + 0.9f * (1 - wl[it] - wh[it]) } // half low + 0.9 mid
        val delay = (0.75 * 60 / plan.tempoA * SR).toInt()

        val outL = FloatArray(m)
        val outR = FloatArray(m)
        for (ch in 0..1) {
            val out = if (ch == 0) outL else outR
            // A
            val specA = stft.forward(a.channel(ch))
            val maskA = Hpss.percussiveMask(specA)
            stft.inverseInto(specA, m, out, perc = maskA, harmonic = false, gain = aDrums)
            stft.inverseInto(specA, m, out, band = wl, perc = maskA, harmonic = true, gain = aBass)
            stft.inverseInto(specA, m, out, band = upper, perc = maskA, harmonic = true, gain = aMel)
            val send = stft.synthesize(specA, m, band = upper, perc = maskA, harmonic = true)
            for (i in 0 until m) send[i] *= aSend[i]
            val tail = DjDsp.echo(send, delay, 0.55, 10)
            stft.inverseInto(stft.forward(tail), m, out, band = dark)
            // B
            val specB = stft.forward(b.channel(ch))
            val maskB = Hpss.percussiveMask(specB)
            stft.inverseInto(specB, m, out, band = upper, perc = maskB, harmonic = true, gain = bMel)
            stft.inverseInto(specB, m, out, band = wl, perc = maskB, harmonic = true, gain = gBLowMel)
            stft.inverseInto(specB, m, out, band = upper, perc = maskB, harmonic = false, gain = bDrums)
            stft.inverseInto(specB, m, out, band = wl, perc = maskB, harmonic = false, gain = gBLowDrums)
        }
        return StereoPcm(outL, outR)
    }

    private fun cosOf(p: FloatArray): FloatArray = FloatArray(p.size) { cos(p[it] * PI / 2).toFloat() }

    private fun sq(v: Float): Double = v.toDouble() * v

    private fun scaled(
        pcm: StereoPcm,
        g: Double,
    ): StereoPcm {
        val gf = g.toFloat()
        return StereoPcm(FloatArray(pcm.frames) { pcm.left[it] * gf }, FloatArray(pcm.frames) { pcm.right[it] * gf }, pcm.sampleRate)
    }

    /** Look-ahead (5 ms) peak limiter with a 200 ms release; untouched when nothing exceeds [ceiling]. */
    private fun limit(
        pcm: StereoPcm,
        ceiling: Float,
    ) {
        if (DjDsp.peak(pcm) <= ceiling) return
        val n = pcm.frames
        val look = SR / 200
        val need = FloatArray(n) { i -> min(1f, ceiling / max(1e-9f, max(abs(pcm.left[i]), abs(pcm.right[i])))) }
        // Minimum over the look-ahead window, then attack over it and release afterwards.
        val target = FloatArray(n)
        val dq = ArrayDeque<Int>()
        for (i in n - 1 downTo 0) {
            while (dq.isNotEmpty() && need[dq.last()] >= need[i]) dq.removeLast()
            dq.addLast(i)
            while (dq.first() > i + look) dq.removeFirst()
            target[i] = need[dq.first()]
        }
        val release = (1 - 1.0 / (0.2 * SR)).toFloat()
        val attack = (1 - 1.0 / look).toFloat()
        var g = 1f
        for (i in 0 until n) {
            val t = target[i]
            g = if (t < g) min(g * attack + t * (1 - attack), g) else g * release + t * (1 - release)
            g = min(g, need[i])
            pcm.left[i] *= g
            pcm.right[i] *= g
        }
    }

    private fun fmt(
        v: Double,
        digits: Int = 1,
    ): String {
        val p = 10.0.pow(digits)
        val r = (v * p).roundToInt() / p
        return r.toString()
    }
}
