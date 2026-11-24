package com.maxrave.domain.spotifyimport

import com.maxrave.domain.data.model.spotifyimport.MatchCandidate
import com.maxrave.domain.data.model.spotifyimport.SourceTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Spotify → YouTube Music matching rules. A wrong "accept" puts the wrong song in someone's
 * playlist silently; a wrong "reject" only costs them a manual search, so the cases lean on the first.
 */
class TrackMatcherTest {
    private fun track(
        title: String,
        vararg artists: String,
        seconds: Int = 200,
    ) = SourceTrack(spotifyUri = null, title = title, artists = artists.toList(), album = null, durationMs = seconds * 1000L)

    private fun yt(
        id: String,
        title: String,
        vararg artists: String,
        seconds: Int? = 200,
        song: Boolean = true,
    ) = MatchCandidate(videoId = id, title = title, artists = artists.toList(), durationSeconds = seconds, isOfficialSong = song)

    @Test
    fun remasterSuffixIsNoise() {
        val parsed = TitleNormalizer.parse("Here Comes The Sun - Remastered 2009")
        assertEquals("here comes the sun", parsed.core)
        assertTrue(parsed.markers.isEmpty())
        assertEquals("Here Comes The Sun", parsed.queryTitle)
    }

    @Test
    fun featuredArtistsAreStripped() {
        assertEquals("stay", TitleNormalizer.parse("Stay (feat. Justin Bieber)").core)
        assertEquals("stay", TitleNormalizer.parse("STAY ft. Justin Bieber").core)
        assertEquals("Stay", TitleNormalizer.parse("Stay (with Justin Bieber)").queryTitle)
    }

    @Test
    fun diacriticsAndPunctuationAreFolded() {
        assertEquals("cancion de amor", TitleNormalizer.parse("Canción de Amor!").core)
        assertEquals("dont stop me now", TitleNormalizer.parse("Don’t Stop Me Now").core)
        assertEquals("beyonce", TitleNormalizer.normalizeName("Beyoncé"))
        assertEquals("beatles", TitleNormalizer.normalizeName("The Beatles"))
    }

    @Test
    fun dashThatIsPartOfTheTitleIsKept() {
        assertEquals("love hate", TitleNormalizer.parse("Love - Hate").core)
    }

    @Test
    fun versionMarkersAreDetected() {
        assertEquals(setOf(VersionMarker.LIVE), TitleNormalizer.parse("Yellow - Live in Buenos Aires").markers)
        assertEquals(setOf(VersionMarker.REMIX), TitleNormalizer.parse("Levitating (The Blessed Madonna Remix)").markers)
        assertEquals(setOf(VersionMarker.KARAOKE), TitleNormalizer.parse("Yellow (Karaoke Version)").markers)
        // "Live" in the title itself is part of the song's name, not a marker.
        assertTrue(TitleNormalizer.parse("Live Forever").markers.isEmpty())
        assertTrue(TitleNormalizer.parse("Blinding Lights (Radio Edit)").markers.isEmpty())
    }

    @Test
    fun exactMatchIsAccepted() {
        val best = TrackMatcher.best(track("Blinding Lights", "The Weeknd", seconds = 200), listOf(yt("a", "Blinding Lights", "The Weeknd", seconds = 201)))
        assertNotNull(best)
        assertEquals("a", best.candidate.videoId)
    }

    @Test
    fun remasteredTrackMatchesPlainResult() {
        val best =
            TrackMatcher.best(
                track("Here Comes The Sun - Remastered 2009", "The Beatles", seconds = 185),
                listOf(yt("x", "Here Comes The Sun", "The Beatles", seconds = 186)),
            )
        assertEquals("x", best?.candidate?.videoId)
    }

    @Test
    fun durationPicksTheRightEdit() {
        val best =
            TrackMatcher.best(
                track("Song", "Artist", seconds = 210),
                listOf(
                    yt("long", "Song", "Artist", seconds = 330),
                    yt("right", "Song", "Artist", seconds = 212),
                ),
            )
        assertEquals("right", best?.candidate?.videoId)
    }

    @Test
    fun liveTrackPrefersLiveResult() {
        val ranked =
            TrackMatcher.rank(
                track("Yellow - Live in Buenos Aires", "Coldplay", seconds = 300),
                listOf(
                    yt("studio", "Yellow", "Coldplay", seconds = 267),
                    yt("live", "Yellow (Live in Buenos Aires)", "Coldplay", seconds = 301),
                ),
            )
        assertEquals("live", ranked.first().candidate.videoId)
        assertTrue(ranked.first().accepted)
    }

    @Test
    fun studioTrackAvoidsKaraokeAndCovers() {
        val ranked =
            TrackMatcher.rank(
                track("Yellow", "Coldplay", seconds = 267),
                listOf(
                    yt("karaoke", "Yellow (Karaoke Version)", "Coldplay", seconds = 267),
                    yt("real", "Yellow", "Coldplay", seconds = 269),
                ),
            )
        assertEquals("real", ranked.first().candidate.videoId)
        assertFalse(ranked.first { it.candidate.videoId == "karaoke" }.accepted)
    }

    @Test
    fun differentSongBySameArtistIsRejected() {
        assertNull(
            TrackMatcher.best(
                track("Fix You", "Coldplay", seconds = 295),
                listOf(yt("other", "Viva La Vida", "Coldplay", seconds = 242)),
            ),
        )
    }

    @Test
    fun sameTitleWrongArtistAndLengthIsRejected() {
        assertNull(
            TrackMatcher.best(
                track("Intro", "The xx", seconds = 128),
                listOf(yt("other", "Intro", "Some Other Band", seconds = 75)),
            ),
        )
    }

    @Test
    fun songIsPreferredOverVideoAtEqualFit() {
        val ranked =
            TrackMatcher.rank(
                track("Shape of You", "Ed Sheeran", seconds = 234),
                listOf(
                    yt("video", "Shape of You", "Ed Sheeran", seconds = 234, song = false),
                    yt("song", "Shape of You", "Ed Sheeran", seconds = 234, song = true),
                ),
            )
        assertEquals("song", ranked.first().candidate.videoId)
    }

    @Test
    fun combinedArtistNamesAreSplit() {
        val best =
            TrackMatcher.best(
                track("Under Pressure", "Queen", "David Bowie", seconds = 248),
                listOf(yt("q", "Under Pressure", "Queen & David Bowie", seconds = 249)),
            )
        assertNotNull(best)
        assertTrue(best.artist > 0.9)
    }

    @Test
    fun queryUsesCleanTitleAndPrimaryArtist() {
        assertEquals("Stay The Kid LAROI", TrackMatcher.query(track("Stay (feat. Justin Bieber)", "The Kid LAROI", "Justin Bieber")))
        assertEquals("Here Comes The Sun The Beatles", TrackMatcher.query(track("Here Comes The Sun - Remastered 2009", "The Beatles")))
        assertEquals("Yellow Live in Buenos Aires Coldplay", TrackMatcher.query(track("Yellow - Live in Buenos Aires", "Coldplay")))
    }
}
