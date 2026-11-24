package com.maxrave.domain.mediaservice.djmix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DjMatchTest {
    private fun t(
        bpm: Double,
        key: String,
        perc: Double? = 0.3,
        onset: Double? = 1.0,
        loud: Double? = -9.0,
    ): DjMatchTraits {
        val minor = key.endsWith("m")
        return DjMatchTraits(bpm, KeyDetector.NOTES.indexOf(key.removeSuffix("m")), minor, perc, onset, loud)
    }

    @Test
    fun tempoFollowsTheRendererLimit() {
        assertEquals(0.0, DjMatch.tempoBend(120.0, 120.0), 1e-12)
        // Double time counts as the same tempo.
        assertEquals(0.0, DjMatch.tempoBend(120.0, 60.0), 1e-12)
        // 117.5 -> 136 met in the middle on the device: matched.
        assertTrue(DjMatch.score(t(117.5, "C"), t(136.0, "C")).matched)
        // 123 -> 99 and 136 -> 89 fell back to the short blend.
        assertFalse(DjMatch.score(t(123.0, "C"), t(99.0, "C")).matched)
        assertFalse(DjMatch.score(t(136.0, "C"), t(89.0, "C")).matched)
        assertEquals(-1.5, DjMatch.score(t(123.0, "C"), t(99.0, "C")).tempo, 1e-12)
        // Same plan decision as the renderer for a range of pairs.
        val options = DjTransitionOptions()
        for (tb in listOf(90.0, 100.0, 104.0, 105.0, 110.0, 126.0, 140.0, 141.5, 145.0, 200.0)) {
            val bend = DjMatch.tempoBend(120.0, tb)
            assertEquals(bend <= options.maxBend, DjMatch.score(t(120.0, "C"), t(tb, "C")).matched, "tb=$tb")
        }
    }

    @Test
    fun keyUsesRelativeMajorAndFifths() {
        val c = t(120.0, "C")
        assertEquals(1.0, DjMatch.score(c, t(120.0, "Am")).key, 1e-12)
        assertEquals(0.8, DjMatch.score(c, t(120.0, "G")).key, 1e-12)
        assertEquals(0.8, DjMatch.score(c, t(120.0, "F")).key, 1e-12)
        // One semitone off: the renderer shifts it, when the pair is matched.
        assertEquals(0.5, DjMatch.score(c, t(120.0, "C#")).key, 1e-12)
        assertEquals(0.0, DjMatch.score(c, t(90.0, "C#")).key, 1e-12)
        // Two semitones off cannot be shifted into place.
        assertEquals(0.0, DjMatch.score(c, t(120.0, "D")).key, 1e-12)
    }

    @Test
    fun vibeAndEnergyPenaliseJumps() {
        val a = t(120.0, "C", perc = 0.30, onset = 1.0, loud = -9.0)
        val same = DjMatch.score(a, t(120.0, "C", perc = 0.31, onset = 1.05, loud = -9.5))
        val ballad = DjMatch.score(a, t(120.0, "C", perc = 0.10, onset = 0.5, loud = -15.0))
        assertEquals(1.0, same.vibe, 1e-12)
        assertTrue(same.energy > -0.15)
        assertEquals(-1.0, ballad.vibe, 1e-12)
        assertTrue(ballad.energy < -1.5)
        // Perfect pair: 2 + 1.5 + 2 + ~0.
        assertTrue(same.total > 5.3)
        // Unknown features neither help nor hurt.
        val unknown = DjMatch.score(t(120.0, "C", null, null, null), t(120.0, "C", null, null, null))
        assertEquals(0.0, unknown.vibe, 1e-12)
        assertEquals(0.0, unknown.energy, 1e-12)
    }

    @Test
    fun choosePrefersAMatchedCandidateOnlyWhenClearlyBetter() {
        val cur = t(123.0, "C")
        // Next is unmatched (99 bpm); the third one is matched: move it.
        val moved = DjMatch.choose(cur, listOf(t(99.0, "C"), t(89.0, "G"), t(125.0, "Am")))
        assertEquals(2, moved.index)
        assertTrue(moved.moved)
        // Next is already a good match: keep it even though another is slightly better.
        val kept = DjMatch.choose(cur, listOf(t(126.0, "G"), t(123.0, "C")))
        assertEquals(0, kept.index)
        // Nothing analysed except the next: keep.
        assertEquals(0, DjMatch.choose(cur, listOf(t(99.0, "C"), null, null)).index)
        // Next could not be analysed at all: a matched one replaces it, an unmatched one does not.
        assertEquals(1, DjMatch.choose(cur, listOf(null, t(122.0, "C"))).index)
        assertEquals(0, DjMatch.choose(cur, listOf(null, t(90.0, "C"))).index)
        // Both unmatched: needs the margin (key + vibe here).
        val margin = DjMatch.choose(t(123.0, "C", perc = 0.3), listOf(t(99.0, "F#", perc = 0.1), t(95.0, "C", perc = 0.3)))
        assertEquals(1, margin.index)
    }
}
