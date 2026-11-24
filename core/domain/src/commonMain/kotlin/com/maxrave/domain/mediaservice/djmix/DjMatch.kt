package com.maxrave.domain.mediaservice.djmix

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What [DjMatch] compares between two songs; built from a [DjTrackAnalysis] with [matchTraits]. */
data class DjMatchTraits(
    val bpm: Double,
    val keyTonic: Int,
    val keyMinor: Boolean,
    /** [DjTrackAnalysis.percussiveRatio], null when unknown. */
    val percussive: Double? = null,
    /** [DjTrackAnalysis.onsetMean], null when unknown. */
    val onset: Double? = null,
    /** Level of the song's loud frames before loudness matching, in dBFS; null when unknown. */
    val loudDb: Double? = null,
) {
    val keyName: String get() = KeyDetector.NOTES[keyTonic.mod(12)] + if (keyMinor) "m" else ""
}

fun DjTrackAnalysis.matchTraits(): DjMatchTraits =
    DjMatchTraits(
        bpm = bpm,
        keyTonic = keyTonic,
        keyMinor = keyMinor,
        percussive = percussiveRatio,
        onset = onsetMean?.takeIf { it > 0.0 },
        // The gain puts the loud frames' median at -16 dBFS, so the song's own level is the rest.
        loudDb = if (loudnessGain > 0.0) -16.0 - 20.0 * log10(loudnessGain) else null,
    )

/** How well B follows A in a DJ transition; higher is better ([total] = 2t + 1.5k + 2v + e). */
data class DjMatchScore(
    val total: Double,
    /** 1 at equal tempo, 0 at the renderer's bend limit, -1.5 past it (an unmatched blend). */
    val tempo: Double,
    /** 1 same Camelot number, 0.8 a fifth apart, 0.5 one semitone the renderer shifts away, else 0. */
    val key: Double,
    /** -1..1 from the percussive share (same texture vs. drums-led into a ballad). */
    val vibe: Double,
    /** <= 0: penalty for a jump in punch (onset strength) and level. */
    val energy: Double,
    /** Whether the renderer will beat-match the pair (full 32-beat blend). */
    val matched: Boolean,
    /** Largest tempo bend either song needs when they meet in the middle (after half/double time). */
    val bend: Double,
)

/** The pick among the upcoming tracks: [index] into the candidates (0 = the queue's own next). */
data class DjNextChoice(
    val index: Int,
    /** Score of each candidate against the current track, null where it is not analysed. */
    val scores: List<DjMatchScore?>,
) {
    val moved: Boolean get() = index != 0
}

/**
 * Scoring for the "smart next" order: which of the next few queue tracks mixes best out of the
 * current one. The rules are the offline prototype's (order.py) with the renderer's own limits:
 * - tempo: after half/double time, the two songs meet in the geometric middle and each may bend
 *   at most [DjTransitionOptions.maxBend] (8.5 %, ~17.7 % apart in all); t = 1 - bend / maxBend,
 *   or -1.5 when the renderer falls back to its short unmatched blend;
 * - key: Camelot-style (a minor key counts as its relative major): same 1.0, ±5/7 semitones 0.8,
 *   one semitone off that the renderer's key shift fixes 0.5 (matched pairs only), else 0;
 * - vibe: the prototype's hand-made style tags become the measured percussive share;
 * - energy: a penalty on big jumps in punch (onset strength) and in the songs' own level.
 */
object DjMatch {
    /** A pick must beat the queue's own next by this much (or be matched where that is not). */
    const val MIN_MARGIN = 1.0

    fun score(
        a: DjMatchTraits,
        b: DjMatchTraits,
        options: DjTransitionOptions = DjTransitionOptions(),
    ): DjMatchScore {
        val bend = tempoBend(a.bpm, b.bpm)
        val matched = bend <= options.maxBend
        val t = if (matched) 1.0 - bend / options.maxBend else -1.5
        val k = keyScore(a, b, matched && options.keyShift)
        val v = vibeScore(a.percussive, b.percussive)
        val e = energyScore(a, b)
        return DjMatchScore(
            total = 2 * t + 1.5 * k + 2 * v + e,
            tempo = t,
            key = k,
            vibe = v,
            energy = e,
            matched = matched,
            bend = bend,
        )
    }

    /**
     * The bend DjTransitionRenderer.plan needs: B at half, equal or double time (whichever is
     * nearest A), both meeting at sqrt(ta * tb); the larger of the two rate changes. Infinite when
     * either tempo is unknown.
     */
    fun tempoBend(
        ta: Double,
        tbRaw: Double,
    ): Double {
        if (ta <= 0.0 || tbRaw <= 0.0) return Double.POSITIVE_INFINITY
        val tb = listOf(0.5, 1.0, 2.0).map { tbRaw * it }.minBy { abs(it / ta - 1) }
        val common = sqrt(ta * tb)
        return max(abs(common / ta - 1), abs(common / tb - 1))
    }

    fun keyScore(
        a: DjMatchTraits,
        b: DjMatchTraits,
        shiftable: Boolean,
    ): Double {
        val d = (KeyDetector.tonicMajor(b.keyTonic, b.keyMinor) - KeyDetector.tonicMajor(a.keyTonic, a.keyMinor)).mod(12)
        return when {
            d == 0 -> 1.0
            d == 5 || d == 7 -> 0.8
            shiftable && KeyDetector.keyShift(a.keyTonic, a.keyMinor, b.keyTonic, b.keyMinor) != 0 -> 0.5
            else -> 0.0
        }
    }

    /** 1 within 0.04 of each other, falling to -1 at 0.20 apart; 0 when either is unknown. */
    fun vibeScore(
        pa: Double?,
        pb: Double?,
    ): Double {
        if (pa == null || pb == null) return 0.0
        val dp = abs(pa - pb)
        return (1 - 2 * (dp - 0.04) / 0.16).coerceIn(-1.0, 1.0)
    }

    /**
     * Punch: 2 × |ln(onset ratio)|, at most 1.5 (half or double the punch costs ~1.4). Level: the
     * part of the difference above 1.5 dB, 1 per 6 dB, at most 1. Unknown parts cost nothing.
     */
    fun energyScore(
        a: DjMatchTraits,
        b: DjMatchTraits,
    ): Double {
        var penalty = 0.0
        if (a.onset != null && b.onset != null && a.onset > 0 && b.onset > 0) {
            penalty += min(1.5, 2 * abs(ln(b.onset / a.onset)))
        }
        if (a.loudDb != null && b.loudDb != null) {
            penalty += min(1.0, max(0.0, abs(a.loudDb - b.loudDb) - 1.5) / 6)
        }
        return -penalty
    }

    /**
     * Which of [candidates] (in queue order, index 0 = the queue's own next; null = not analysed)
     * should follow [current]. The queue's next is kept unless the best one is clearly better: it
     * is matched where the next is not (or the next cannot be analysed at all), or it scores at
     * least [minMargin] more.
     */
    fun choose(
        current: DjMatchTraits,
        candidates: List<DjMatchTraits?>,
        minMargin: Double = MIN_MARGIN,
        options: DjTransitionOptions = DjTransitionOptions(),
    ): DjNextChoice {
        val scores = candidates.map { c -> c?.let { score(current, it, options) } }
        var best = -1
        for (i in scores.indices) {
            val s = scores[i] ?: continue
            if (best < 0 || s.total > scores[best]!!.total) best = i
        }
        if (best <= 0) return DjNextChoice(0, scores)
        val pick = scores[best]!!
        val next = scores.getOrNull(0)
        val better =
            if (next == null) {
                pick.matched
            } else {
                (!next.matched && pick.matched) || pick.total - next.total >= minMargin
            }
        return DjNextChoice(if (better) best else 0, scores)
    }

    /**
     * Share of the spectral energy that median-filter HPSS puts in the percussive part, over up to
     * six 4-second excerpts spread through the song's body ([fromSec] to [toSec], the first and
     * last 10 s left out when there is room). Costs a fraction of a second on a phone.
     */
    fun percussiveRatio(
        mono22k: FloatArray,
        fromSec: Double,
        toSec: Double,
    ): Double? {
        val sr = BeatTracker.SAMPLE_RATE
        val excerpt = 4 * sr
        var from = (fromSec * sr).toInt().coerceIn(0, mono22k.size)
        var to = (toSec * sr).toInt().coerceIn(from, mono22k.size)
        if (to - from > 3 * excerpt + 20 * sr) {
            from += 10 * sr
            to -= 10 * sr
        }
        if (to - from < excerpt) return null
        val count = min(6, (to - from) / excerpt)
        val step = if (count > 1) (to - from - excerpt) / (count - 1) else 0
        val stft = Stft(1024, 512)
        var perc = 0.0
        var all = 0.0
        for (i in 0 until count) {
            val spec = stft.forward(mono22k, from + i * step, excerpt)
            val mask = Hpss.percussiveMask(spec, kernel = 17)
            for (j in mask.indices) {
                val p = spec.re[j] * spec.re[j] + spec.im[j] * spec.im[j]
                perc += mask[j] * p
                all += p
            }
        }
        return if (all > 0.0) perc / all else null
    }
}
