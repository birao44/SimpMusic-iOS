package com.maxrave.spotify.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures are trimmed copies of real fetchPlaylist / libraryV3 answers (shape as of 2026-11). */
class SpotifyPathfinderParserTest {
    private val playlistJson =
        """
        {"data":{"playlistV2":{"__typename":"Playlist","name":"Today’s Top Hits","uri":"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
        "images":{"items":[{"sources":[{"height":null,"url":"https://i.scdn.co/image/cover","width":null}]}]},
        "ownerV2":{"data":{"__typename":"User","name":"Spotify"}},
        "content":{"__typename":"PlaylistItemsPage","pagingInfo":{"limit":100,"offset":0},"totalCount":4,"items":[
          {"itemV2":{"__typename":"TrackResponseWrapper","data":{"__typename":"Track","name":"BbY WOW",
            "uri":"spotify:track:3h5T5JypYU7huFiVYhv1dr","trackDuration":{"totalMilliseconds":225834},
            "albumOfTrack":{"name":"NO ME ARREPIENTO"},"playability":{"playable":true,"reason":"PLAYABLE"},
            "artists":{"items":[{"profile":{"name":"KAROL G"}},{"profile":{"name":"Feid"}}]}}}},
          {"itemV2":{"__typename":"LocalTrackResponseWrapper","data":{"__typename":"LocalTrack","name":"My demo","uri":"spotify:local:a:b:My+demo:120"}}},
          {"itemV2":{"__typename":"EpisodeOrChapterResponseWrapper","data":{"__typename":"Episode","name":"Podcast ep","uri":"spotify:episode:xyz"}}},
          {"itemV2":{"__typename":"TrackResponseWrapper","data":{"__typename":"NotFound","message":"gone"}}}
        ]}}}}
        """.trimIndent()

    @Test
    fun parsesPlaylistMetadataAndTracks() {
        val (ref, page) = SpotifyPathfinderParser.parsePlaylist(playlistJson, "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")
        assertEquals("Today’s Top Hits", ref.name)
        assertEquals("Spotify", ref.owner)
        assertEquals("https://i.scdn.co/image/cover", ref.imageUrl)
        assertEquals(4, page.totalCount)
        val track = assertIs<SpotifyPlaylistEntry.Track>(page.items[0]).track
        assertEquals("BbY WOW", track.title)
        assertEquals(listOf("KAROL G", "Feid"), track.artists)
        assertEquals(225834L, track.durationMs)
        assertEquals("NO ME ARREPIENTO", track.album)
        assertEquals(SpotifySkipReason.LOCAL_FILE, assertIs<SpotifyPlaylistEntry.Skipped>(page.items[1]).reason)
        assertEquals(SpotifySkipReason.EPISODE, assertIs<SpotifyPlaylistEntry.Skipped>(page.items[2]).reason)
        assertEquals(SpotifySkipReason.UNAVAILABLE, assertIs<SpotifyPlaylistEntry.Skipped>(page.items[3]).reason)
    }

    @Test
    fun notFoundPlaylistThrows() {
        assertFailsWith<SpotifyNotFoundException> {
            SpotifyPathfinderParser.parsePlaylist("""{"data":{"playlistV2":{"__typename":"NotFound","message":"nope"}}}""", "spotify:playlist:x")
        }
    }

    @Test
    fun unauthorizedLibraryThrows() {
        assertFailsWith<SpotifyUnauthorizedException> {
            SpotifyPathfinderParser.parseLibrary(
                """{"data":{"me":null},"errors":[{"message":"Exception while fetching data (/me) : User is not authorized"}]}""",
            )
        }
    }

    @Test
    fun invalidHashIsReported() {
        val error = assertFailsWith<IllegalStateException> { SpotifyPathfinderParser.parseLibrary("\"Invalid query hash\"") }
        assertTrue(error.message!!.contains("query hash"))
    }

    @Test
    fun parsesLibraryWithLikedSongs() {
        val json =
            """
            {"data":{"me":{"libraryV3":{"__typename":"LibraryPage","totalCount":4,"pagingInfo":{"offset":0,"limit":50},"items":[
              {"item":{"__typename":"LibraryPseudoPlaylistResponseWrapper","_uri":"spotify:collection:tracks",
                "data":{"__typename":"PseudoPlaylist","name":"Liked Songs","uri":"spotify:collection:tracks","count":321,
                "image":{"sources":[{"url":"https://misc.scdn.co/liked-songs.png","width":640}]}}}},
              {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:1111111111111111111111",
                "data":{"__typename":"Playlist","name":"Road trip","uri":"spotify:playlist:1111111111111111111111",
                "ownerV2":{"data":{"name":"me"}},"images":{"items":[{"sources":[{"url":"https://i.scdn.co/small","width":60},{"url":"https://i.scdn.co/big","width":640}]}]}}}},
              {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:collection:your-episodes",
                "data":{"__typename":"Playlist","name":"Your Episodes","uri":"spotify:collection:your-episodes"}}},
              {"item":{"__typename":"LibraryFolderResponseWrapper","_uri":"spotify:user:me:folder:1","data":{"__typename":"Folder","name":"F"}}}
            ]}}}}
            """.trimIndent()
        val page = SpotifyPathfinderParser.parseLibrary(json)
        assertEquals(2, page.items.size)
        assertTrue(page.items[0].isLikedSongs)
        assertEquals(321, page.items[0].trackCount)
        assertEquals("Road trip", page.items[1].name)
        assertEquals("https://i.scdn.co/big", page.items[1].imageUrl)
        assertNull(page.items[1].trackCount)
    }

    @Test
    fun parsesLikedTracks() {
        val json =
            """
            {"data":{"me":{"library":{"tracks":{"__typename":"UserLibraryTrackPage","totalCount":1,"pagingInfo":{"offset":0,"limit":100},"items":[
              {"addedAt":{"isoString":"2026-01-01T00:00:00Z"},"track":{"_uri":"spotify:track:abc","data":{"__typename":"Track","name":"Yellow",
               "duration":{"totalMilliseconds":266773},"albumOfTrack":{"name":"Parachutes"},"artists":{"items":[{"profile":{"name":"Coldplay"}}]},
               "playability":{"playable":true}}}}
            ]}}}}}
            """.trimIndent()
        val page = SpotifyPathfinderParser.parseLikedTracks(json)
        val track = assertIs<SpotifyPlaylistEntry.Track>(page.items.single()).track
        assertEquals("spotify:track:abc", track.uri)
        assertEquals(266773L, track.durationMs)
    }

    @Test
    fun parsesPlaylistLinks() {
        val uri = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(uri, SpotifyPathfinder.playlistUriFromLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123"))
        assertEquals(uri, SpotifyPathfinder.playlistUriFromLink("Check this https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(uri, SpotifyPathfinder.playlistUriFromLink("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(uri, SpotifyPathfinder.playlistUriFromLink(" 37i9dQZF1DXcBWIGoYBM5M "))
        assertNull(SpotifyPathfinder.playlistUriFromLink("https://open.spotify.com/album/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(SpotifyPathfinder.playlistUriFromLink("hello"))
    }
}
