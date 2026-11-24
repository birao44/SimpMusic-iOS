package com.maxrave.domain.mediaservice.automix

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** How a DJ transition sounds. [CUT] is the plain hard switch every other style falls back to. */
@Serializable
enum class TransitionStyle {
    CUT,
    SMOOTH_BLEND,
    BASS_SWAP,
    FILTER_SWEEP,
    ECHO_OUT,
    BRAKE,
    DROP_CUT,
    RISE,
}

/** The user's taste: [SMOOTH] keeps to blends and sweeps, [CLUB] lets every style in. */
@Serializable
enum class DjIntensity {
    SMOOTH,
    CLUB,
    ;

    companion object {
        fun parse(value: String?): DjIntensity = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CLUB
    }
}

/** The shape of the segment that ENDS at a breakpoint, from the previous breakpoint's value to its own. */
@Serializable
enum class Curve {
    /** Straight line. */
    LINEAR,

    /** Quarter sine: rises as sin, falls as cos, so two opposite equal-power lanes sum to constant power. */
    EQUAL_POWER,

    /** Straight line in log space: the natural way to move a filter cutoff. A 0 end reads as [Lane.FREQ_FLOOR_HZ]. */
    EXPONENTIAL,

    /** Sigmoid in time (slow, steep, slow), linear in value. */
    S_CURVE,

    /** Sigmoid in time and log in value: the DJ filter sweep. */
    EXPONENTIAL_S_CURVE,

    /** Holds the previous value until the breakpoint, then jumps. */
    STEP,
}

@Serializable
data class Breakpoint(
    /** Milliseconds from the start of the transition. */
    val atMs: Long,
    val value: Double,
    val curve: Curve = Curve.LINEAR,
)

/** One automated parameter: breakpoints in time order; flat before the first and after the last. */
@Serializable
data class Lane(
    val points: List<Breakpoint>,
) {
    fun valueAt(ms: Long): Double {
        if (points.isEmpty()) return 0.0
        if (ms <= points.first().atMs) return points.first().value
        for (i in 1 until points.size) {
            val b = points[i]
            if (ms < b.atMs) {
                val a = points[i - 1]
                val span = (b.atMs - a.atMs).toDouble()
                val t = if (span <= 0) 1.0 else (ms - a.atMs) / span
                return interpolate(a.value, b.value, t, b.curve)
            }
        }
        return points.last().value
    }

    val isConstant: Boolean get() = points.all { it.value == points.first().value }

    companion object {
        /** A 0 cutoff means "filter off"; the exponential curves sweep to and from this instead. */
        const val FREQ_FLOOR_HZ = 20.0
        private const val SIGMOID_K = 6.0

        fun constant(value: Double): Lane = Lane(listOf(Breakpoint(0, value)))

        fun of(vararg points: Breakpoint): Lane = Lane(points.sortedBy { it.atMs })

        internal fun interpolate(
            from: Double,
            to: Double,
            t: Double,
            curve: Curve,
        ): Double {
            val x = t.coerceIn(0.0, 1.0)
            return when (curve) {
                Curve.LINEAR -> from + (to - from) * x
                Curve.STEP -> if (x >= 1.0) to else from
                Curve.EQUAL_POWER ->
                    if (to >= from) from + (to - from) * sin(x * PI / 2) else to + (from - to) * cos(x * PI / 2)
                Curve.S_CURVE -> from + (to - from) * sigmoid(x)
                Curve.EXPONENTIAL -> logLerp(from, to, x)
                Curve.EXPONENTIAL_S_CURVE -> logLerp(from, to, sigmoid(x))
            }
        }

        /** Sigmoid rescaled so it passes exactly through 0 and 1. */
        private fun sigmoid(x: Double): Double {
            fun raw(v: Double) = 1.0 / (1.0 + exp(-SIGMOID_K * (v - 0.5)))
            val lo = raw(0.0)
            val hi = raw(1.0)
            return (raw(x) - lo) / (hi - lo)
        }

        private fun logLerp(
            from: Double,
            to: Double,
            x: Double,
        ): Double {
            val a = if (from <= 0) FREQ_FLOOR_HZ else from
            val b = if (to <= 0) FREQ_FLOOR_HZ else to
            val v = exp(ln(a) + (ln(b) - ln(a)) * x)
            // Arriving at "off" really is off, not a 20 Hz high-pass left behind.
            return if (x >= 1.0 && to <= 0) 0.0 else v
        }
    }
}

/**
 * Everything one deck does during a transition. Null lanes are never touched, so a renderer only
 * installs the filters a script actually uses.
 *
 * [gain] is the channel fader. When [echoSend] is set the echo is a post-fader send with trails, as
 * on a DJ mixer: closing the fader stops new sound reaching the echo but lets what is in it ring out.
 */
@Serializable
data class DeckScript(
    /** Linear 0..1. */
    val gain: Lane = Lane.constant(1.0),
    /** High-pass cutoff in Hz; 0 = off. */
    val lowCutHz: Lane? = null,
    /** Low-pass cutoff in Hz; 0 (or ≥ 20 kHz) = off. */
    val highCutHz: Lane? = null,
    /** Low-shelf gain in dB around [BASS_SHELF_HZ], for the bass swap; 0 = flat. */
    val bassGainDb: Lane? = null,
    /** 0..1 amount fed into the echo. */
    val echoSend: Lane? = null,
    /** Level of each repeat relative to the one before. */
    val echoFeedback: Double = 0.5,
    /** Time between repeats, already converted from beats. */
    val echoDelayMs: Double = 0.0,
    /** How many repeats the tail has. */
    val echoRepeats: Int = 4,
    /** Playback rate, 1.0 = natural. */
    val rate: Lane? = null,
    /** True: pitch follows rate (a vinyl brake). False: tempo changes with pitch preserved. */
    val varispeed: Boolean = false,
) {
    companion object {
        const val BASS_SHELF_HZ = 180.0
        const val BASS_KILL_DB = -24.0
    }
}

/**
 * A complete transition from deck A (outgoing) to deck B (incoming), as pure data for a platform
 * renderer to play. Times inside lanes are milliseconds from the transition's start.
 */
@Serializable
data class TransitionScript(
    val style: TransitionStyle,
    /** Why the brain picked it, for the tuning log. */
    val reason: String,
    val durationMs: Long,
    /** A's own position when the transition starts. */
    val outgoingStartMs: Long,
    /** A's own position where it is meant to be gone (its phrase end, or the cut). For logs and checks. */
    val outgoingExitMs: Long,
    /** B's own position when it starts playing. */
    val incomingStartMs: Long,
    /** Transition time at which B starts playing. */
    val incomingStartAtMs: Long = 0,
    /** Transition time at which A stops dead, if it does. */
    val cutAtMs: Long? = null,
    val outgoing: DeckScript = DeckScript(),
    val incoming: DeckScript = DeckScript(),
    val manualSkip: Boolean = false,
) {
    /** How far A's own timeline advances by transition time [ms], following its rate lane. */
    fun outgoingAdvanceMs(ms: Long = durationMs): Double = advance(outgoing.rate, ms)

    companion object {
        /** Integrates a rate lane in 10 ms steps: the time a deck covers of its own track. */
        fun advance(
            rate: Lane?,
            ms: Long,
        ): Double {
            if (rate == null) return ms.toDouble()
            var total = 0.0
            var t = 0L
            while (t < ms) {
                val step = minOf(10L, ms - t)
                total += rate.valueAt(t + step / 2) * step
                t += step
            }
            return total
        }

        /** A plain switch: A gone, B in at full level at once. */
        fun cut(
            reason: String,
            outgoingPositionMs: Long,
            incomingStartMs: Long,
            manualSkip: Boolean,
        ): TransitionScript =
            TransitionScript(
                style = TransitionStyle.CUT,
                reason = reason,
                durationMs = 30,
                outgoingStartMs = outgoingPositionMs,
                outgoingExitMs = outgoingPositionMs,
                incomingStartMs = incomingStartMs,
                incomingStartAtMs = 0,
                cutAtMs = 0,
                outgoing = DeckScript(gain = Lane.constant(0.0)),
                incoming = DeckScript(gain = Lane.constant(1.0)),
                manualSkip = manualSkip,
            )
    }
}
