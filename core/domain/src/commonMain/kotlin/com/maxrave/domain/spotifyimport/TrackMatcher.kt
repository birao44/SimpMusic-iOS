package com.maxrave.domain.spotifyimport

import com.maxrave.domain.data.model.spotifyimport.MatchCandidate
import com.maxrave.domain.data.model.spotifyimport.SourceTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Decides which YouTube Music result is the same recording as a Spotify track.
 *
 * Pure and platform-free on purpose: it is the part of the Spotify import most likely to need
 * tuning, and every rule here is pinned by `TrackMatcherTest`.
 *
 * Score (0..~1) = 0.45 title + 0.30 artist + 0.25 duration, then
 * - version markers: a live/remix/acoustic… track that gets a plain result loses [MISSING_MARKER]
 *   per marker, a plain track that gets a live/remix… result loses [EXTRA_MARKER], and a karaoke,
 *   cover, sped-up/slowed or instrumental result nobody asked for loses [EXTRA_STRONG_MARKER];
 * - official song results (the "songs" filter) get [SONG_BONUS], videos lose it.
 *
 * A result is accepted at [ACCEPT_SCORE] or above, and only if its title alone reaches
 * [MIN_TITLE] — artist and duration agreeing is not enough to call two different titles the same.
 */
object TrackMatcher {
    const val ACCEPT_SCORE = 0.70
    const val MIN_TITLE = 0.55

    /** Below this a result is not even worth showing as a guess. */
    const val GUESS_SCORE = 0.40

    private const val W_TITLE = 0.45
    private const val W_ARTIST = 0.30
    private const val W_DURATION = 0.25
    private const val MISSING_MARKER = 0.15
    private const val EXTRA_MARKER = 0.25
    private const val EXTRA_STRONG_MARKER = 0.35
    private const val SONG_BONUS = 0.03

    data class Scored(
        val candidate: MatchCandidate,
        val score: Double,
        val title: Double,
        val artist: Double,
        val duration: Double,
    ) {
        val accepted: Boolean get() = score >= ACCEPT_SCORE && title >= MIN_TITLE
    }

    /** The search query for [track]: clean title, any version words, primary artist. */
    fun query(track: SourceTrack): String {
        val parsed = TitleNormalizer.parse(track.title)
        val lowerTitle = parsed.queryTitle.lowercase()
        val markerWords =
            parsed.markers
                .map { it.queryWord }
                .filterNot { lowerTitle.contains(it) }
                .joinToString(" ")
        return listOf(parsed.queryTitle, markerWords, track.primaryArtist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    fun score(
        track: SourceTrack,
        candidate: MatchCandidate,
    ): Scored {
        val want = TitleNormalizer.parse(track.title)
        val got = TitleNormalizer.parse(candidate.title)

        val titleScore = titleSimilarity(want.core, got.core)
        val artistScore = artistSimilarity(track.artists, candidate)
        val durationScore = durationSimilarity(track.durationMs / 1000.0, candidate.durationSeconds)

        val penalty =
            MISSING_MARKER * (want.markers - got.markers).size +
                (got.markers - want.markers).sumOf { if (it.strong) EXTRA_STRONG_MARKER else EXTRA_MARKER }
        val typeAdjust = if (candidate.isOfficialSong) SONG_BONUS else -SONG_BONUS

        val total = W_TITLE * titleScore + W_ARTIST * artistScore + W_DURATION * durationScore - penalty + typeAdjust
        return Scored(candidate, total, titleScore, artistScore, durationScore)
    }

    /** Every candidate scored, best first. Ties keep the search order, which already ranks relevance. */
    fun rank(
        track: SourceTrack,
        candidates: List<MatchCandidate>,
    ): List<Scored> =
        candidates
            .distinctBy { it.videoId }
            .map { score(track, it) }
            .sortedByDescending { it.score }

    /** The accepted best match, or null. */
    fun best(
        track: SourceTrack,
        candidates: List<MatchCandidate>,
    ): Scored? = rank(track, candidates).firstOrNull()?.takeIf { it.accepted }

    fun titleSimilarity(
        a: String,
        b: String,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val ratio = levenshteinRatio(a, b)
        val dice = tokenDice(a, b)
        var sim = max(ratio, dice)
        // "hello" vs "hello from the other side": one title cut short is close, not identical.
        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        if (compactA == compactB) return 1.0
        val shorter = if (compactA.length <= compactB.length) compactA else compactB
        val longer = if (shorter === compactA) compactB else compactA
        if (shorter.length >= 3 && longer.contains(shorter)) sim = max(sim, 0.8)
        return sim
    }

    fun artistSimilarity(
        wanted: List<String>,
        candidate: MatchCandidate,
    ): Double {
        val want = wanted.map { TitleNormalizer.normalizeName(it) }.filter { it.isNotEmpty() }
        if (want.isEmpty()) return 0.5
        val got =
            candidate.artists
                .flatMap { TitleNormalizer.splitArtists(it) }
                .map { TitleNormalizer.normalizeName(it) }
                .filter { it.isNotEmpty() }
        if (got.isEmpty()) {
            // Some videos carry no artist; the uploader's name is often in the title instead.
            val title = TitleNormalizer.normalizeName(candidate.title).replace(" ", "")
            return if (title.contains(want.first().replace(" ", ""))) 0.8 else 0.3
        }
        fun bestFor(name: String): Double = got.maxOf { nameSimilarity(name, it) }
        val primary = bestFor(want.first())
        val overlap = want.count { bestFor(it) >= 0.8 }.toDouble() / want.size
        return 0.75 * primary + 0.25 * overlap
    }

    /**
     * ≤3 s is the same master; ≤7 s is usually the same song with a different fade; beyond that
     * it is more and more likely a different edit, live take or video intro.
     */
    fun durationSimilarity(
        wantSeconds: Double,
        gotSeconds: Int?,
    ): Double {
        if (wantSeconds <= 0 || gotSeconds == null || gotSeconds <= 0) return 0.5
        val diff = abs(wantSeconds - gotSeconds)
        return when {
            diff <= 3 -> 1.0
            diff <= 7 -> 0.8
            diff <= 15 -> 0.5
            diff <= 30 -> 0.2
            else -> 0.0
        }
    }

    private fun nameSimilarity(
        a: String,
        b: String,
    ): Double {
        if (a == b) return 1.0
        val ca = a.replace(" ", "")
        val cb = b.replace(" ", "")
        if (ca == cb) return 1.0
        return max(levenshteinRatio(a, b), tokenDice(a, b))
    }

    internal fun levenshteinRatio(
        a: String,
        b: String,
    ): Double {
        // Long titles are compared on their first 80 characters: enough to tell songs apart and
        // keeps the quadratic cost trivial.
        val s = a.take(80)
        val t = b.take(80)
        val longest = max(s.length, t.length)
        if (longest == 0) return 1.0
        var prev = IntArray(t.length + 1) { it }
        var curr = IntArray(t.length + 1)
        for (i in 1..s.length) {
            curr[0] = i
            for (j in 1..t.length) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val swap = prev
            prev = curr
            curr = swap
        }
        return 1.0 - prev[t.length].toDouble() / longest
    }

    internal fun tokenDice(
        a: String,
        b: String,
    ): Double {
        val ta = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val common = ta.intersect(tb).size
        return 2.0 * common / (ta.size + tb.size)
    }
}
