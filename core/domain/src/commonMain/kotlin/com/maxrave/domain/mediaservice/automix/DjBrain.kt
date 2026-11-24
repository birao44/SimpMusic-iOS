package com.maxrave.domain.mediaservice.automix

import kotlin.math.abs
import kotlin.math.roundToLong

/** What the DJ brain knows about one side of a transition. Everything but the title may be missing. */
data class DjTrack(
    val title: String,
    /** The track's length in ms, 0 when unknown. */
    val durationMs: Long = 0,
    val structure: TrackStructure? = null,
    /** Tempo from metadata (Tidal); the measured one in [structure] wins when both exist. */
    val metaBpm: Double? = null,
    val key: CamelotKey? = null,
) {
    val bpm: Double? get() = structure?.bpm ?: metaBpm?.takeIf { it > 0 }
}

data class DjRequest(
    val outgoing: DjTrack,
    val incoming: DjTrack,
    val intensity: DjIntensity = DjIntensity.CLUB,
    /** Styles of the last few transitions, oldest first. */
    val recentStyles: List<TransitionStyle> = emptyList(),
    /** The user pressed Next: the transition starts at [outgoingPositionMs], now. */
    val manualSkip: Boolean = false,
    val outgoingPositionMs: Long = 0,
    /** Time since the previous manual skip, null when there was none recently. */
    val msSinceLastSkip: Long? = null,
    /** The user's fixed crossfade length, which caps the blends; null for Auto. */
    val maxBlendMs: Long? = null,
)

/**
 * The DJ brain: picks a [TransitionStyle], the point A leaves and the point B enters, and writes the
 * [TransitionScript] a renderer plays. Pure and deterministic, so every rule below is unit-tested.
 *
 * Rules of thumb, in order:
 *  - Quick repeated skips are plain cuts; a single skip is a 2-4 s transition from where A is now.
 *  - No structure or a shaky beat grid (live tempo, long free intros) avoids long beat-matched overlaps:
 *    echo out or a short sweep.
 *  - Tempo within [TEMPO_MATCH_GAP] and keys that do not clash: beat-matched blend or bass swap.
 *  - A moderate tempo gap: filter sweep or rise. A big gap or clashing keys: echo out, drop cut, brake.
 *  - A B that jumps into a big drop gets a drop cut or a rise.
 *  - Long overlaps only when A is in its outro and B in its intro, so two vocals do not run together.
 *  - The same style twice in a row only when nothing else fits.
 */
object DjBrain {
    /** Tempo gap a deck may be stretched to close: about what is inaudible on vocals. */
    const val TEMPO_MATCH_GAP = 0.06
    const val MODERATE_GAP = 0.12

    /** Below this the beat grid is not trusted for beat-matched overlaps. */
    const val MIN_BEAT_CONFIDENCE = 0.45

    /** Skips closer together than this are plain cuts. */
    const val QUICK_SKIP_MS = 3_000L

    /** A rise above this many dB into the next four bars is a drop. */
    const val DROP_DB = 6.0

    private const val DEFAULT_BEAT_MS = 500.0
    private const val ECHO_TAPS = 4
    private const val ECHO_FEEDBACK = 0.5
    private const val MAX_SKIP_MS = 4_000L
    private const val SKIP_ENTRY_MAX_MS = 15_000L
    private const val CUT_ENTRY_MAX_MS = 30_000L
    private const val DROP_SEARCH_MS = 60_000L
    private const val MIN_LEAD_IN_MS = 5_000L
    private const val UNSURE_BLEND_MS = 6_000L
    private const val CLICK_GUARD_MS = 20L

    fun plan(request: DjRequest): TransitionScript {
        if (request.manualSkip) return planSkip(request)
        val ctx = Context(request)
        val (candidates, reason) = autoCandidates(ctx)
        for (style in ordered(candidates, request.recentStyles)) {
            build(style, ctx, reason)?.let { return it }
        }
        // Nothing fitted (a short track, an exit too close to the start): a short sweep by time alone.
        return buildFilterSweep(ctx, "fallback: no room for $reason", forceTimed = true)
            ?: TransitionScript.cut("fallback: no room", ctx.aExit, ctx.bEntryForCut(), manualSkip = false)
    }

    /** The tuning log line every renderer writes when a transition starts. */
    fun logLine(
        request: DjRequest,
        script: TransitionScript,
    ): String {
        fun bpm(t: DjTrack) = t.bpm?.let { ((it * 10).roundToLong() / 10.0).toString() } ?: "?"
        return "DJ transition style=${script.style} reason=${script.reason} " +
            "A=${request.outgoing.title}@${script.outgoingExitMs} exit " +
            "B=${request.incoming.title}@${script.incomingStartMs} entry " +
            "bpm=${bpm(request.outgoing)}→${bpm(request.incoming)} " +
            "key=${request.outgoing.key ?: "?"}→${request.incoming.key ?: "?"} " +
            "dur=${script.durationMs}ms skip=${script.manualSkip}"
    }

    // ---------------------------------------------------------------- selection

    /** Tempo gap with half/double time folded away; null when either tempo is unknown. */
    fun tempoGap(
        a: Double?,
        b: Double?,
    ): Double? {
        if (a == null || b == null || a <= 0 || b <= 0) return null
        return abs(1 - foldedRatio(a, b))
    }

    /** B's tempo over A's, folded into 0.67..1.5 so half and double time count as a match. */
    fun foldedRatio(
        a: Double,
        b: Double,
    ): Double {
        var r = b / a
        while (r > 1.5) r /= 2
        while (r < 0.67) r *= 2
        return r
    }

    private fun autoCandidates(ctx: Context): Pair<List<TransitionStyle>, String> {
        val gap = ctx.gap
        val gapText = gap?.let { "gap=${(it * 1000).roundToLong() / 10.0}%" } ?: "gap=?"
        val (list, why) =
            when {
                !ctx.confident ->
                    listOf(TransitionStyle.ECHO_OUT, TransitionStyle.FILTER_SWEEP, TransitionStyle.SMOOTH_BLEND) to
                        "low beat confidence (${ctx.confidenceText})"
                gap != null && gap <= TEMPO_MATCH_GAP && !ctx.keyClash ->
                    if (ctx.bHasDrop) {
                        listOf(TransitionStyle.BASS_SWAP, TransitionStyle.RISE, TransitionStyle.SMOOTH_BLEND) to "tempo match, B drops"
                    } else {
                        listOf(TransitionStyle.SMOOTH_BLEND, TransitionStyle.BASS_SWAP) to "tempo match $gapText"
                    }
                gap != null && gap <= MODERATE_GAP && !ctx.keyClash ->
                    if (ctx.bHasDrop) {
                        listOf(TransitionStyle.RISE, TransitionStyle.FILTER_SWEEP) to "moderate $gapText, B drops"
                    } else {
                        listOf(TransitionStyle.FILTER_SWEEP, TransitionStyle.RISE) to "moderate $gapText"
                    }
                else -> {
                    val why = if (ctx.keyClash) "key clash" else "big $gapText"
                    if (ctx.bHasDrop) {
                        listOf(TransitionStyle.DROP_CUT, TransitionStyle.ECHO_OUT, TransitionStyle.BRAKE) to "$why, B drops"
                    } else {
                        listOf(TransitionStyle.ECHO_OUT, TransitionStyle.DROP_CUT, TransitionStyle.BRAKE) to why
                    }
                }
            }
        return smoothOnly(list, ctx.request.intensity, gap) to why
    }

    private fun smoothOnly(
        list: List<TransitionStyle>,
        intensity: DjIntensity,
        gap: Double?,
    ): List<TransitionStyle> {
        if (intensity == DjIntensity.CLUB) return list
        val gentle = list.filter { it == TransitionStyle.SMOOTH_BLEND || it == TransitionStyle.FILTER_SWEEP }
        if (gentle.isNotEmpty()) return gentle
        return if (gap != null && gap <= TEMPO_MATCH_GAP) {
            listOf(TransitionStyle.SMOOTH_BLEND, TransitionStyle.FILTER_SWEEP)
        } else {
            listOf(TransitionStyle.FILTER_SWEEP, TransitionStyle.SMOOTH_BLEND)
        }
    }

    /** Candidates in order, with the last style used moved to the back. */
    internal fun ordered(
        candidates: List<TransitionStyle>,
        recent: List<TransitionStyle>,
    ): List<TransitionStyle> {
        val last = recent.lastOrNull() ?: return candidates
        val (repeat, fresh) = candidates.partition { it == last }
        return fresh + repeat
    }

    private fun build(
        style: TransitionStyle,
        ctx: Context,
        reason: String,
    ): TransitionScript? =
        when (style) {
            TransitionStyle.SMOOTH_BLEND -> buildBlend(ctx, reason, bassSwap = false)
            TransitionStyle.BASS_SWAP -> buildBlend(ctx, reason, bassSwap = true)
            TransitionStyle.FILTER_SWEEP -> buildFilterSweep(ctx, reason, forceTimed = false)
            TransitionStyle.ECHO_OUT -> buildEchoOut(ctx, reason)
            TransitionStyle.BRAKE -> buildBrake(ctx, reason)
            TransitionStyle.DROP_CUT -> buildDropCut(ctx, reason)
            TransitionStyle.RISE -> buildRise(ctx, reason)
            TransitionStyle.CUT -> TransitionScript.cut(reason, ctx.aExit, ctx.bEntryForCut(), manualSkip = false)
        }

    // ---------------------------------------------------------------- auto styles

    /**
     * SMOOTH_BLEND and BASS_SWAP: A ramps to B's tempo over one bar, B enters on A's downbeat and
     * the two run beat-matched for 8 or 16 beats, ending on A's phrase end.
     */
    private fun buildBlend(
        ctx: Context,
        reason: String,
        bassSwap: Boolean,
    ): TransitionScript? {
        if (!ctx.confident) return if (bassSwap) null else buildTimedBlend(ctx, reason)
        val a = ctx.gridA
        val exitIndex = a.indexAtOrBefore(ctx.aExit) ?: return null
        val bars = ctx.overlapBars(2, a.beatMs)
        val gap = ctx.gap ?: return null
        val ratio = if (gap <= TEMPO_MATCH_GAP) foldedRatio(ctx.aBeatBpm, ctx.bBeatBpm) else return null
        val stretch = abs(ratio - 1) > 0.002
        val entryIndex = exitIndex - bars
        val startIndex = if (stretch) entryIndex - 1 else entryIndex
        if (startIndex < 0) return null
        val startA = a.downbeats[startIndex]
        val entryA = a.downbeats[entryIndex]
        val exitA = a.downbeats[exitIndex]
        if (startA < ctx.aMinStart) return null
        val rampWall = if (stretch) ((entryA - startA) * 2 / (1 + ratio)).roundToLong() else 0L
        val blendWall = ((exitA - entryA) / ratio).roundToLong()
        val total = rampWall + blendWall
        val rate = if (stretch) Lane.of(Breakpoint(0, 1.0), Breakpoint(rampWall, ratio, Curve.S_CURVE)) else null
        val bStart = ctx.bStartLandingAt(blendWall)

        val outgoing: DeckScript
        val incoming: DeckScript
        if (bassSwap) {
            val swap = rampWall + blendWall / 2
            outgoing =
                DeckScript(
                    gain = Lane.of(Breakpoint(0, 1.0), Breakpoint(swap, 1.0), Breakpoint(total, 0.0, Curve.EQUAL_POWER)),
                    bassGainDb =
                        Lane.of(Breakpoint(0, 0.0), Breakpoint(swap, 0.0), Breakpoint(swap + 40, DeckScript.BASS_KILL_DB)),
                    rate = rate,
                )
            incoming =
                DeckScript(
                    gain =
                        Lane.of(
                            Breakpoint(0, 0.0),
                            Breakpoint(rampWall, 0.0),
                            Breakpoint(rampWall + blendWall / 4, 1.0, Curve.S_CURVE),
                        ),
                    bassGainDb =
                        Lane.of(
                            Breakpoint(0, DeckScript.BASS_KILL_DB),
                            Breakpoint(swap, DeckScript.BASS_KILL_DB),
                            Breakpoint(swap + 40, 0.0),
                        ),
                )
        } else {
            outgoing =
                DeckScript(
                    gain = Lane.of(Breakpoint(0, 1.0), Breakpoint(rampWall, 1.0), Breakpoint(total, 0.0, Curve.EQUAL_POWER)),
                    rate = rate,
                )
            incoming =
                DeckScript(
                    gain = Lane.of(Breakpoint(0, 0.0), Breakpoint(rampWall, 0.0), Breakpoint(total, 1.0, Curve.EQUAL_POWER)),
                )
        }
        return TransitionScript(
            style = if (bassSwap) TransitionStyle.BASS_SWAP else TransitionStyle.SMOOTH_BLEND,
            reason = "$reason, ${bars * 4} beats" + if (stretch) " stretch=${(ratio * 1000).roundToLong() / 1000.0}" else "",
            durationMs = total,
            outgoingStartMs = startA,
            outgoingExitMs = exitA,
            incomingStartMs = bStart,
            incomingStartAtMs = rampWall,
            outgoing = outgoing,
            incoming = incoming,
        )
    }

    /** A short equal-power blend by the clock, for grids not trusted to beat-match. */
    private fun buildTimedBlend(
        ctx: Context,
        reason: String,
    ): TransitionScript? {
        val total = ctx.capBlend(UNSURE_BLEND_MS)
        val exit = ctx.aExit
        val start = exit - total
        if (start < ctx.aMinStart) return null
        return TransitionScript(
            style = TransitionStyle.SMOOTH_BLEND,
            reason = "$reason, short timed blend",
            durationMs = total,
            outgoingStartMs = start,
            outgoingExitMs = exit,
            incomingStartMs = ctx.bStartLandingAt(total),
            outgoing = DeckScript(gain = Lane.of(Breakpoint(0, 1.0), Breakpoint(total, 0.0, Curve.EQUAL_POWER))),
            incoming = DeckScript(gain = Lane.of(Breakpoint(0, 0.0), Breakpoint(total, 1.0, Curve.EQUAL_POWER))),
        )
    }

    /** A's low-pass falls while B's high-pass opens, both on an S-curve, under an equal-power fade. */
    private fun buildFilterSweep(
        ctx: Context,
        reason: String,
        forceTimed: Boolean,
    ): TransitionScript? {
        val a = ctx.gridA
        val exitIndex = if (ctx.confident && !forceTimed) a.indexAtOrBefore(ctx.aExit) else null
        val bars = ctx.overlapBars(2, a.beatMs)
        val (start, exit) =
            if (exitIndex != null && exitIndex - bars >= 0) {
                a.downbeats[exitIndex - bars] to a.downbeats[exitIndex]
            } else {
                val total = ctx.capBlend(UNSURE_BLEND_MS)
                (ctx.aExit - total) to ctx.aExit
            }
        if (start < ctx.aMinStart && !forceTimed) return null
        val safeStart = if (forceTimed) maxOf(start, 0L) else start
        val total = exit - safeStart
        if (total < 1_000) return null
        return TransitionScript(
            style = TransitionStyle.FILTER_SWEEP,
            reason = if (exitIndex != null) "$reason, ${bars * 4} beats" else "$reason, timed",
            durationMs = total,
            outgoingStartMs = safeStart,
            outgoingExitMs = exit,
            incomingStartMs = ctx.bStartLandingAt(total),
            outgoing = sweepOut(total),
            incoming = sweepIn(total),
        )
    }

    /**
     * A's echo comes in over one beat, A's dry signal cuts on its phrase-end downbeat and the echo
     * rings out for about three beats; B starts on that downbeat.
     */
    private fun buildEchoOut(
        ctx: Context,
        reason: String,
    ): TransitionScript? {
        val beat = ctx.gridA.beatMs
        val cutA = ctx.aExit
        val lead = (2 * beat).roundToLong()
        val start = cutA - lead
        if (start < ctx.aMinStart) return null
        val delay = if (ctx.confident) beat * 0.75 else beat * 0.5
        return echoScript(
            reason = "$reason, echo ${if (ctx.confident) "3/4" else "1/2"} beat",
            start = start,
            cutAt = lead,
            beat = beat,
            delay = delay,
            incomingStart = ctx.bEntryForCut(),
            manualSkip = false,
        )
    }

    /** A slows to a stop like a vinyl deck (pitch falling with it); B starts hard on its downbeat. */
    private fun buildBrake(
        ctx: Context,
        reason: String,
    ): TransitionScript? {
        val brake = (2 * ctx.gridA.beatMs).roundToLong().coerceIn(600, 1_000)
        val start = ctx.aExit - brake / 2
        if (start < ctx.aMinStart) return null
        return brakeScript("$reason, brake ${brake}ms", start, brake, ctx.bEntryForDrop(), manualSkip = false)
    }

    /** A hard cut on A's phrase end straight into B's drop. */
    private fun buildDropCut(
        ctx: Context,
        reason: String,
    ): TransitionScript? {
        val beat = ctx.gridA.beatMs.roundToLong()
        val start = ctx.aExit - beat
        if (start < ctx.aMinStart) return null
        return TransitionScript(
            style = TransitionStyle.DROP_CUT,
            reason = reason,
            durationMs = beat + 100,
            outgoingStartMs = start,
            outgoingExitMs = ctx.aExit,
            incomingStartMs = ctx.bEntryForDrop(),
            incomingStartAtMs = beat,
            cutAtMs = beat + CLICK_GUARD_MS,
            outgoing = DeckScript(gain = Lane.of(Breakpoint(0, 1.0), Breakpoint(beat, 1.0), Breakpoint(beat + CLICK_GUARD_MS, 0.0))),
            incoming = DeckScript(gain = Lane.constant(1.0)),
        )
    }

    /**
     * B rises under A's last phrase through a high-pass sweeping down from 2 kHz while its level
     * comes up; A dips and leaves on the phrase end, where B's drop (or intro end) lands.
     */
    private fun buildRise(
        ctx: Context,
        reason: String,
    ): TransitionScript? {
        val a = ctx.gridA
        val exitIndex = (if (ctx.confident) a.indexAtOrBefore(ctx.aExit) else null) ?: return null
        val bars = ctx.overlapBars(2, a.beatMs)
        if (exitIndex - bars < 0) return null
        val start = a.downbeats[exitIndex - bars]
        val exit = a.downbeats[exitIndex]
        if (start < ctx.aMinStart) return null
        val total = exit - start
        val bLanding = ctx.bDrop ?: ctx.bIntroEnd
        val bStart = if (bLanding != null) ctx.gridB.snapAtOrAfter(bLanding - total, ctx.bFirstBeat) else ctx.bFirstBeat
        return TransitionScript(
            style = TransitionStyle.RISE,
            reason = "$reason, ${bars * 4} beats",
            durationMs = total + 50,
            outgoingStartMs = start,
            outgoingExitMs = exit,
            incomingStartMs = bStart,
            cutAtMs = total + CLICK_GUARD_MS,
            outgoing =
                DeckScript(
                    gain =
                        Lane.of(
                            Breakpoint(0, 1.0),
                            Breakpoint(total, 0.7),
                            Breakpoint(total + CLICK_GUARD_MS, 0.0),
                        ),
                ),
            incoming =
                DeckScript(
                    gain = Lane.of(Breakpoint(0, 0.0), Breakpoint(total * 3 / 4, 0.75), Breakpoint(total, 1.0)),
                    lowCutHz = Lane.of(Breakpoint(0, 2_000.0), Breakpoint(total, 0.0, Curve.EXPONENTIAL)),
                ),
        )
    }

    // ---------------------------------------------------------------- manual skip

    private fun planSkip(request: DjRequest): TransitionScript {
        val ctx = Context(request)
        val now = request.outgoingPositionMs
        val entry = ctx.bEntryForSkip()
        val since = request.msSinceLastSkip
        if (since != null && since < QUICK_SKIP_MS) {
            return TransitionScript.cut("quick skips ${since}ms apart", now, entry, manualSkip = true)
        }
        val candidates =
            when {
                request.intensity == DjIntensity.SMOOTH -> listOf(TransitionStyle.FILTER_SWEEP)
                ctx.keyClash || (ctx.gap ?: 0.0) > MODERATE_GAP ->
                    listOf(TransitionStyle.BRAKE, TransitionStyle.ECHO_OUT, TransitionStyle.FILTER_SWEEP)
                ctx.confidentA -> listOf(TransitionStyle.ECHO_OUT, TransitionStyle.FILTER_SWEEP)
                else -> listOf(TransitionStyle.FILTER_SWEEP, TransitionStyle.ECHO_OUT)
            }
        val beat = ctx.gridA.beatMs
        return when (ordered(candidates, request.recentStyles).first()) {
            TransitionStyle.ECHO_OUT -> {
                // The dry cut lands on A's next downbeat when one comes soon enough, else on a beat.
                val earliest = now + maxOf(beat, 800.0)
                val cutPos =
                    ctx.gridA
                        .takeIf { ctx.confidentA }
                        ?.let { g -> g.downbeats.firstOrNull { it >= earliest && it <= now + 2_000 } ?: g.beatAtOrAfter(earliest) }
                        ?: earliest.roundToLong()
                echoScript(
                    reason = "skip",
                    start = now,
                    cutAt = cutPos - now,
                    beat = beat,
                    delay = beat * if (ctx.confidentA) 0.75 else 0.5,
                    incomingStart = entry,
                    manualSkip = true,
                )
            }
            TransitionStyle.BRAKE ->
                brakeScript("skip", now, (2 * beat).roundToLong().coerceIn(600, 1_000), entry, manualSkip = true)
            else -> {
                val total = (4 * beat).roundToLong().coerceIn(2_000, 3_000)
                TransitionScript(
                    style = TransitionStyle.FILTER_SWEEP,
                    reason = "skip",
                    durationMs = total,
                    outgoingStartMs = now,
                    outgoingExitMs = now + total,
                    incomingStartMs = entry,
                    outgoing = sweepOut(total, endHz = 300.0),
                    incoming = sweepIn(total, startHz = 1_500.0),
                    manualSkip = true,
                )
            }
        }
    }

    // ---------------------------------------------------------------- shared shapes

    private fun sweepOut(
        total: Long,
        endHz: Double = 200.0,
    ) = DeckScript(
        gain = Lane.of(Breakpoint(0, 1.0), Breakpoint(total, 0.0, Curve.EQUAL_POWER)),
        highCutHz = Lane.of(Breakpoint(0, 20_000.0), Breakpoint(total, endHz, Curve.EXPONENTIAL_S_CURVE)),
    )

    private fun sweepIn(
        total: Long,
        startHz: Double = 2_000.0,
    ) = DeckScript(
        gain = Lane.of(Breakpoint(0, 0.0), Breakpoint(total, 1.0, Curve.EQUAL_POWER)),
        lowCutHz = Lane.of(Breakpoint(0, startHz), Breakpoint(total, 0.0, Curve.EXPONENTIAL_S_CURVE)),
    )

    private fun echoScript(
        reason: String,
        start: Long,
        cutAt: Long,
        beat: Double,
        delay: Double,
        incomingStart: Long,
        manualSkip: Boolean,
    ): TransitionScript {
        val sendIn = minOf(beat.roundToLong(), cutAt)
        var taps = ECHO_TAPS
        // A skip stays within MAX_SKIP_MS: fewer repeats rather than a longer tail.
        while (manualSkip && taps > 2 && cutAt + (taps * delay).roundToLong() > MAX_SKIP_MS) taps--
        val tail = (taps * delay).roundToLong()
        val total = cutAt + tail + 100
        return TransitionScript(
            style = TransitionStyle.ECHO_OUT,
            reason = reason,
            durationMs = total,
            outgoingStartMs = start,
            outgoingExitMs = start + cutAt,
            incomingStartMs = incomingStart,
            incomingStartAtMs = cutAt,
            outgoing =
                DeckScript(
                    gain = Lane.of(Breakpoint(0, 1.0), Breakpoint(cutAt, 1.0), Breakpoint(cutAt + CLICK_GUARD_MS, 0.0)),
                    echoSend = Lane.of(Breakpoint(0, 0.0), Breakpoint(sendIn, 1.0)),
                    echoFeedback = ECHO_FEEDBACK,
                    echoDelayMs = delay,
                    echoRepeats = taps,
                    // Thin the tail out of B's bass.
                    lowCutHz = Lane.of(Breakpoint(cutAt, 0.0), Breakpoint(cutAt + beat.roundToLong(), 400.0, Curve.EXPONENTIAL)),
                ),
            incoming = DeckScript(gain = Lane.constant(1.0)),
            manualSkip = manualSkip,
        )
    }

    private fun brakeScript(
        reason: String,
        start: Long,
        brake: Long,
        incomingStart: Long,
        manualSkip: Boolean,
    ): TransitionScript =
        TransitionScript(
            style = TransitionStyle.BRAKE,
            reason = reason,
            durationMs = brake + 100,
            outgoingStartMs = start,
            outgoingExitMs = start + (brake * (1 + BRAKE_END_RATE) / 2).roundToLong(),
            incomingStartMs = incomingStart,
            incomingStartAtMs = brake,
            cutAtMs = brake,
            outgoing =
                DeckScript(
                    gain = Lane.of(Breakpoint(0, 1.0), Breakpoint(brake * 6 / 10, 1.0), Breakpoint(brake, 0.0)),
                    rate = Lane.of(Breakpoint(0, 1.0), Breakpoint(brake, BRAKE_END_RATE)),
                    varispeed = true,
                ),
            incoming = DeckScript(gain = Lane.constant(1.0)),
            manualSkip = manualSkip,
        )

    private const val BRAKE_END_RATE = 0.05

    // ---------------------------------------------------------------- analysis of the pair

    internal class Grid(
        val beatMs: Double,
        val downbeats: List<Long>,
    ) {
        fun indexAtOrBefore(ms: Long): Int? = downbeats.indexOfLast { it <= ms + 5 }.takeIf { it >= 0 }

        /** The first downbeat at or after [ms], never before [floor]. */
        fun snapAtOrAfter(
            ms: Long,
            floor: Long,
        ): Long = downbeats.firstOrNull { it >= ms && it >= floor } ?: floor

        /** The next beat at or after [ms] on the grid laid by the downbeats. */
        fun beatAtOrAfter(ms: Double): Long {
            val bar = downbeats.lastOrNull { it <= ms } ?: return ms.roundToLong()
            val beats = kotlin.math.ceil((ms - bar) / beatMs)
            return (bar + beats * beatMs).roundToLong()
        }
    }

    private class Context(
        val request: DjRequest,
    ) {
        val a = request.outgoing
        val b = request.incoming
        val sa = a.structure
        val sb = b.structure

        val gridA = grid(a)
        val gridB = grid(b)
        val confidentA = sa != null && sa.beatConfidence >= MIN_BEAT_CONFIDENCE && sa.downbeatsMs.size >= 8
        val confidentB = sb != null && sb.beatConfidence >= MIN_BEAT_CONFIDENCE && sb.downbeatsMs.size >= 8
        val confident = confidentA && confidentB
        val confidenceText = "${sa?.beatConfidence ?: "-"}/${sb?.beatConfidence ?: "-"}"

        val gap = tempoGap(a.bpm, b.bpm)

        /** Tempo of each grid itself, which is what the stretch has to match. */
        val aBeatBpm = 60_000.0 / gridA.beatMs
        val bBeatBpm = 60_000.0 / gridB.beatMs

        val keyClash = a.key != null && b.key != null && a.key.distanceTo(b.key) >= 3

        val aHasOutro = sa?.lastChorusEndMs != null
        val bIntroEnd = sb?.introEndMs
        val bFirstBeat: Long =
            sb?.let { s -> s.downbeatsMs.firstOrNull { it >= s.firstSoundMs - 50 } ?: s.firstSoundMs } ?: 0L
        val bDrop: Long? =
            sb
                ?.sections
                ?.firstOrNull { it.deltaDb >= DROP_DB && it.timeMs > bFirstBeat && it.timeMs <= DROP_SEARCH_MS }
                ?.timeMs
        val bHasDrop = bDrop != null

        /** Where A leaves: its last chorus's end, else its music's end, else close to its end. */
        val aExit: Long =
            run {
                val end = sa?.let { it.lastChorusEndMs ?: it.musicEndMs }
                val limit = if (a.durationMs > 0) a.durationMs - 200 else Long.MAX_VALUE
                when {
                    end != null -> minOf(end, limit)
                    a.durationMs > 0 -> a.durationMs - 1_000
                    else -> 0L
                }
            }

        /** The transition never starts in A's first seconds of sound. */
        val aMinStart: Long = (sa?.firstSoundMs ?: 0L) + MIN_LEAD_IN_MS

        private fun grid(t: DjTrack): Grid {
            val s = t.structure
            // The bar line closing the last bar (the music's end) is a downbeat too.
            if (s != null && s.beatMs > 0) {
                val lines = if (s.downbeatsMs.isNotEmpty() && s.musicEndMs > s.downbeatsMs.last()) s.downbeatsMs + s.musicEndMs else s.downbeatsMs
                return Grid(s.beatMs, lines)
            }
            val bpm = t.metaBpm?.takeIf { it > 0 }
            return Grid(if (bpm != null) 60_000.0 / bpm else DEFAULT_BEAT_MS, emptyList())
        }

        /** Bars of overlap: the long one only when A is in its outro and B in its intro. */
        fun overlapBars(
            longBars: Int,
            beatMs: Double,
        ): Int {
            var bars = if (aHasOutro && bIntroEnd != null) longBars * 2 else longBars
            val cap = request.maxBlendMs
            while (cap != null && bars > 1 && bars * 4 * beatMs > cap) bars /= 2
            return maxOf(bars, 1)
        }

        fun capBlend(ms: Long): Long = request.maxBlendMs?.let { minOf(ms, maxOf(it, 1_000)) } ?: ms

        /**
         * B's start for a blend [wallMs] long: its intro end lands where A leaves, B starting on one
         * of its downbeats. Without an intro, B starts on its first downbeat, never in silence.
         */
        fun bStartLandingAt(wallMs: Long): Long {
            val introEnd = bIntroEnd ?: return bFirstBeat
            return gridB.snapAtOrAfter(introEnd - wallMs, bFirstBeat)
        }

        /** B for a cut: its intro end when that comes early, else its first downbeat. */
        fun bEntryForCut(): Long = bIntroEnd?.takeIf { it in bFirstBeat..CUT_ENTRY_MAX_MS } ?: bFirstBeat

        /** B for a drop cut or brake: its drop, else as for a cut. */
        fun bEntryForDrop(): Long = bDrop ?: bEntryForCut()

        /** B for a skip: early enough to sit in what the precache already buffered. */
        fun bEntryForSkip(): Long =
            bIntroEnd?.takeIf { it in bFirstBeat..SKIP_ENTRY_MAX_MS }
                ?: bFirstBeat.takeIf { it <= SKIP_ENTRY_MAX_MS }
                ?: 0L
    }
}
