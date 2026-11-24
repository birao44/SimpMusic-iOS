package com.maxrave.domain.mediaservice.automix

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DjBrainTest {
    /** A steady track: [bars] bars at [bpm] from [firstSoundMs], optionally with an intro and an outro. */
    private fun structure(
        bpm: Double,
        bars: Int = 96,
        firstSoundMs: Long = 0,
        introBars: Int? = null,
        outroBars: Int? = null,
        confidence: Double = 0.9,
        dropAtBar: Int? = null,
    ): TrackStructure {
        val beat = 60_000.0 / bpm
        val downbeats = (0 until bars).map { firstSoundMs + (it * 4 * beat).roundToLong() }
        val musicEnd = firstSoundMs + (bars * 4 * beat).roundToLong()
        val sections = dropAtBar?.let { listOf(SectionChange(downbeats[it], 9.0)) } ?: emptyList()
        return TrackStructure(
            bpm = bpm,
            beatMs = beat,
            downbeatsMs = downbeats,
            sections = sections,
            firstSoundMs = firstSoundMs,
            lastSoundMs = musicEnd,
            musicEndMs = musicEnd,
            introEndMs = introBars?.let { downbeats[it] },
            lastChorusEndMs = outroBars?.let { downbeats[bars - it] },
            beatConfidence = confidence,
        )
    }

    private fun track(
        title: String,
        s: TrackStructure?,
        key: CamelotKey? = null,
        durationMs: Long = (s?.musicEndMs ?: 200_000) + 2_000,
    ) = DjTrack(title = title, durationMs = durationMs, structure = s, metaBpm = s?.bpm, key = key)

    private val am = CamelotKey(8, true)
    private val em = CamelotKey(9, true)
    private val fSharpMajor = CamelotKey(2, false)

    private fun isDownbeat(
        s: TrackStructure,
        ms: Long,
    ) = (s.downbeatsMs + s.musicEndMs).any { abs(it - ms) <= 2 }

    @Test
    fun `close tempo and compatible keys blend beat-matched on downbeats`() {
        val a = structure(124.0)
        val b = structure(126.0)
        val script = DjBrain.plan(DjRequest(track("A", a, am), track("B", b, em)))

        assertEquals(TransitionStyle.SMOOTH_BLEND, script.style)
        assertTrue(isDownbeat(a, script.outgoingStartMs), "A starts on a downbeat")
        assertTrue(isDownbeat(a, script.outgoingExitMs), "A exits on a downbeat")
        assertEquals(a.musicEndMs, script.outgoingExitMs)
        assertTrue(isDownbeat(b, script.incomingStartMs), "B enters on a downbeat")
        // Short overlap (no outro/intro): 8 beats of A between B's entry and A's exit, plus one ramp bar.
        val beatA = a.beatMs
        assertTrue(abs((12 * beatA).roundToLong() - (script.outgoingExitMs - script.outgoingStartMs)) <= 3, "12 beats of A")
        // A's own timeline covers exactly start→exit over the script's duration, following its tempo ramp.
        val advance = script.outgoingAdvanceMs()
        assertEquals((script.outgoingExitMs - script.outgoingStartMs).toDouble(), advance, absoluteTolerance = 15.0)
        // A ends at B's tempo.
        val endRate = assertNotNull(script.outgoing.rate).valueAt(script.durationMs)
        assertEquals(126.0 / 124.0, endRate, absoluteTolerance = 0.001)
        // B enters exactly when A reaches its entry downbeat.
        val atEntry = script.outgoingStartMs + TransitionScript.advance(script.outgoing.rate, script.incomingStartAtMs)
        assertTrue(isDownbeat(a, atEntry.roundToLong()), "B lands on A's downbeat at $atEntry")
    }

    @Test
    fun `long overlap only when A is in its outro and B in its intro`() {
        val a = structure(120.0, outroBars = 8)
        val b = structure(120.0, introBars = 8)
        val script = DjBrain.plan(DjRequest(track("A", a, am), track("B", b, am)))
        assertEquals(TransitionStyle.SMOOTH_BLEND, script.style)
        assertEquals(a.lastChorusEndMs, script.outgoingExitMs)
        assertEquals(16 * 500L, script.durationMs, "16 beats, no stretch at equal tempo")
        // B's intro end lands where A leaves.
        assertEquals(b.introEndMs!! - 16 * 500L, script.incomingStartMs)
    }

    @Test
    fun `the same style twice in a row is avoided when another fits`() {
        val req = DjRequest(track("A", structure(124.0)), track("B", structure(125.0)), recentStyles = listOf(TransitionStyle.SMOOTH_BLEND))
        val script = DjBrain.plan(req)
        assertEquals(TransitionStyle.BASS_SWAP, script.style)
    }

    @Test
    fun `bass swap trades the low end on the midpoint downbeat`() {
        val a = structure(124.0)
        val req = DjRequest(track("A", a), track("B", structure(124.0)), recentStyles = listOf(TransitionStyle.SMOOTH_BLEND))
        val script = DjBrain.plan(req)
        assertEquals(TransitionStyle.BASS_SWAP, script.style)
        val aBass = assertNotNull(script.outgoing.bassGainDb)
        val bBass = assertNotNull(script.incoming.bassGainDb)
        val mid = script.incomingStartAtMs + (script.durationMs - script.incomingStartAtMs) / 2
        assertEquals(0.0, aBass.valueAt(mid - 10))
        assertEquals(DeckScript.BASS_KILL_DB, bBass.valueAt(mid - 10))
        assertEquals(DeckScript.BASS_KILL_DB, aBass.valueAt(mid + 50))
        assertEquals(0.0, bBass.valueAt(mid + 50))
        val swapInA = script.outgoingStartMs + TransitionScript.advance(script.outgoing.rate, mid)
        assertTrue(isDownbeat(a, swapInA.roundToLong()), "swap on A's downbeat")
    }

    @Test
    fun `moderate tempo gap sweeps the filters`() {
        val script = DjBrain.plan(DjRequest(track("A", structure(120.0)), track("B", structure(131.0))))
        assertEquals(TransitionStyle.FILTER_SWEEP, script.style)
        assertNull(script.outgoing.rate)
        assertEquals(200.0, script.outgoing.highCutHz!!.valueAt(script.durationMs), absoluteTolerance = 0.5)
        assertEquals(0.0, script.incoming.lowCutHz!!.valueAt(script.durationMs))
        assertEquals(8 * 500L, script.durationMs)
    }

    @Test
    fun `big tempo gap echoes out and B starts on the cut downbeat`() {
        val a = structure(100.0)
        val b = structure(140.0)
        val script = DjBrain.plan(DjRequest(track("A", a), track("B", b)))
        assertEquals(TransitionStyle.ECHO_OUT, script.style)
        assertTrue(isDownbeat(a, script.outgoingExitMs))
        assertEquals(script.outgoingExitMs - script.outgoingStartMs, script.incomingStartAtMs)
        assertTrue(script.outgoing.gain.valueAt(script.incomingStartAtMs + 25) == 0.0, "dry cut")
        assertEquals(1.0, script.outgoing.echoSend!!.valueAt(script.incomingStartAtMs))
        assertEquals(0.75 * a.beatMs, script.outgoing.echoDelayMs, absoluteTolerance = 0.01)
        assertTrue(isDownbeat(b, script.incomingStartMs))
    }

    @Test
    fun `clashing keys avoid the long blend even at equal tempo`() {
        val script = DjBrain.plan(DjRequest(track("A", structure(120.0), am), track("B", structure(120.0), fSharpMajor)))
        assertTrue(script.style == TransitionStyle.ECHO_OUT || script.style == TransitionStyle.DROP_CUT, "${script.style}")
    }

    @Test
    fun `a B with a big drop and a big gap gets a drop cut into the drop`() {
        val b = structure(150.0, dropAtBar = 16)
        val script = DjBrain.plan(DjRequest(track("A", structure(100.0)), track("B", b)))
        assertEquals(TransitionStyle.DROP_CUT, script.style)
        assertEquals(b.downbeatsMs[16], script.incomingStartMs)
        assertNotNull(script.cutAtMs)
        assertTrue(script.incomingStartAtMs <= script.cutAtMs!!)
    }

    @Test
    fun `low beat confidence avoids long beat-matched overlaps`() {
        val a = structure(120.0, confidence = 0.2)
        val script = DjBrain.plan(DjRequest(track("A", a), track("B", structure(121.0))))
        assertEquals(TransitionStyle.ECHO_OUT, script.style)
        assertTrue(script.reason.startsWith("low beat confidence"), script.reason)
        val again = DjBrain.plan(DjRequest(track("A", a), track("B", structure(121.0)), recentStyles = listOf(TransitionStyle.ECHO_OUT)))
        assertEquals(TransitionStyle.FILTER_SWEEP, again.style)
        assertTrue(again.durationMs <= 6_000)
    }

    @Test
    fun `no structure at all still gives a short transition near the end`() {
        val a = DjTrack("A", durationMs = 200_000)
        val b = DjTrack("B", durationMs = 180_000)
        val script = DjBrain.plan(DjRequest(a, b))
        assertNotEquals(TransitionStyle.SMOOTH_BLEND, script.style)
        assertTrue(script.durationMs <= 6_000)
        assertTrue(script.outgoingExitMs <= 200_000)
        assertEquals(0, script.incomingStartMs)
    }

    @Test
    fun `smooth intensity keeps to blends and sweeps`() {
        val script =
            DjBrain.plan(DjRequest(track("A", structure(100.0)), track("B", structure(150.0, dropAtBar = 16)), intensity = DjIntensity.SMOOTH))
        assertEquals(TransitionStyle.FILTER_SWEEP, script.style)
        val skip =
            DjBrain.plan(
                DjRequest(track("A", structure(100.0)), track("B", structure(150.0)), intensity = DjIntensity.SMOOTH, manualSkip = true, outgoingPositionMs = 60_000),
            )
        assertEquals(TransitionStyle.FILTER_SWEEP, skip.style)
    }

    @Test
    fun `B never starts in silence`() {
        val b = structure(120.0, firstSoundMs = 3_000)
        val script = DjBrain.plan(DjRequest(track("A", structure(140.0)), track("B", b)))
        assertTrue(script.incomingStartMs >= 3_000, "B at ${script.incomingStartMs}")
    }

    @Test
    fun `a skip starts at the current position, lasts 2-4 s and enters B on its first downbeat`() {
        val a = structure(120.0)
        val b = structure(128.0, firstSoundMs = 800)
        val now = 60_834L
        val script = DjBrain.plan(DjRequest(track("A", a), track("B", b), manualSkip = true, outgoingPositionMs = now))
        assertTrue(script.manualSkip)
        assertEquals(TransitionStyle.ECHO_OUT, script.style)
        assertEquals(now, script.outgoingStartMs)
        assertTrue(script.durationMs in 2_000..4_000, "duration ${script.durationMs}")
        assertEquals(b.downbeatsMs.first(), script.incomingStartMs)
        // The dry cut lands on A's next downbeat at least a beat away.
        assertTrue(isDownbeat(a, script.outgoingExitMs), "cut at ${script.outgoingExitMs}")
        assertTrue(script.outgoingExitMs >= now + 500)

        val next = DjBrain.plan(DjRequest(track("A", a), track("B", b), manualSkip = true, outgoingPositionMs = now, recentStyles = listOf(TransitionStyle.ECHO_OUT)))
        assertEquals(TransitionStyle.FILTER_SWEEP, next.style)
        assertEquals(now, next.outgoingStartMs)
        assertTrue(next.durationMs in 2_000..4_000)
    }

    @Test
    fun `a skip with a big tempo gap brakes`() {
        val script = DjBrain.plan(DjRequest(track("A", structure(90.0)), track("B", structure(140.0)), manualSkip = true, outgoingPositionMs = 30_000))
        assertEquals(TransitionStyle.BRAKE, script.style)
        assertTrue(script.outgoing.varispeed)
        assertEquals(30_000, script.outgoingStartMs)
        assertTrue(script.outgoing.rate!!.valueAt(script.incomingStartAtMs) < 0.1)
    }

    @Test
    fun `quick skips in a row are plain cuts`() {
        val script = DjBrain.plan(DjRequest(track("A", structure(120.0)), track("B", structure(120.0)), manualSkip = true, outgoingPositionMs = 5_000, msSinceLastSkip = 1_200))
        assertEquals(TransitionStyle.CUT, script.style)
        assertEquals(5_000, script.outgoingStartMs)
    }

    @Test
    fun `log line carries everything needed to correlate`() {
        val req = DjRequest(track("Song A", structure(124.0), am), track("Song B", structure(126.0), em))
        val line = DjBrain.logLine(req, DjBrain.plan(req))
        assertTrue(line.startsWith("DJ transition style=SMOOTH_BLEND reason="), line)
        assertTrue(line.contains(" A=Song A@") && line.contains(" exit B=Song B@") && line.contains(" entry bpm=124.0→126.0 key=8A→9A dur="), line)
        assertTrue(line.endsWith("skip=false"), line)
    }

    // ------------------------------------------------------------- lanes

    @Test
    fun `equal power lanes keep constant power`() {
        val out = Lane.of(Breakpoint(0, 1.0), Breakpoint(1_000, 0.0, Curve.EQUAL_POWER))
        val inn = Lane.of(Breakpoint(0, 0.0), Breakpoint(1_000, 1.0, Curve.EQUAL_POWER))
        for (t in 0L..1_000L step 100) {
            val p = out.valueAt(t) * out.valueAt(t) + inn.valueAt(t) * inn.valueAt(t)
            assertEquals(1.0, sqrt(p), absoluteTolerance = 1e-9)
        }
    }

    @Test
    fun `exponential lanes move in log frequency and arrive at off`() {
        val lane = Lane.of(Breakpoint(0, 2_000.0), Breakpoint(1_000, 0.0, Curve.EXPONENTIAL))
        assertEquals(200.0, lane.valueAt(500), absoluteTolerance = 0.01) // geometric mean of 2000 and 20
        assertEquals(0.0, lane.valueAt(1_000))
        val s = Lane.of(Breakpoint(0, 20_000.0), Breakpoint(1_000, 200.0, Curve.EXPONENTIAL_S_CURVE))
        assertEquals(20_000.0, s.valueAt(0), absoluteTolerance = 0.01)
        assertEquals(2_000.0, s.valueAt(500), absoluteTolerance = 0.01)
        assertEquals(200.0, s.valueAt(1_000), absoluteTolerance = 0.01)
        assertTrue(s.valueAt(100) > 15_000, "S-curve holds near open at first")
    }

    @Test
    fun `step lanes jump at the breakpoint`() {
        val lane = Lane.of(Breakpoint(0, 1.0), Breakpoint(500, 0.0, Curve.STEP))
        assertEquals(1.0, lane.valueAt(499))
        assertEquals(0.0, lane.valueAt(500))
    }

    @Test
    fun `scripts survive a round trip through json`() {
        val script = DjBrain.plan(DjRequest(track("A", structure(100.0)), track("B", structure(140.0))))
        val json = kotlinx.serialization.json.Json
        val text = json.encodeToString(TransitionScript.serializer(), script)
        assertEquals(script, json.decodeFromString(TransitionScript.serializer(), text))
    }
}
