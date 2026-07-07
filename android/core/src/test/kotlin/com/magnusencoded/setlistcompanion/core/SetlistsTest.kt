package com.magnusencoded.setlistcompanion.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetlistsTest {

    @Test
    fun `songs are extracted across sets, covers use the original artist, blanks skipped`() {
        val setlist = Setlist(
            artist = ArtistRef("Test Band"),
            sets = Sets(
                sections = listOf(
                    SetSection(
                        songs = listOf(
                            Song("Opener"),
                            Song(""), // spacer / segue -> skipped
                            Song("A Cover", cover = ArtistRef("Original Artist")),
                        )
                    ),
                    SetSection(encore = 1, songs = listOf(Song("Encore Song"))),
                )
            ),
        )

        assertEquals(
            listOf(
                SongEntry("Opener", "Test Band", isCover = false),
                SongEntry("A Cover", "Original Artist", isCover = true),
                SongEntry("Encore Song", "Test Band", isCover = false),
            ),
            extractSongs(setlist),
        )
    }

    @Test
    fun `a setlist with no sets yields no songs`() {
        assertEquals(emptyList<SongEntry>(), extractSongs(Setlist(artist = ArtistRef("X"))))
    }

    @Test
    fun `playlist title combines artist, venue, city and date`() {
        val setlist = Setlist(
            artist = ArtistRef("Test Band"),
            venue = Venue("The Venue", City("Testville")),
            eventDate = "21-09-2025",
        )
        assertEquals("Test Band @ The Venue, Testville (21-09-2025)", playlistTitle(setlist))
    }

    @Test
    fun `search queries go from strict to loose`() {
        val entry = SongEntry("One", "Metallica", isCover = false)
        assertEquals(
            listOf("track:One artist:Metallica", "One Metallica", "One"),
            searchQueries(entry),
        )
    }

    @Test
    fun `search queries for an unknown artist fall back to the bare title`() {
        assertEquals(listOf("One"), searchQueries(SongEntry("One", "", isCover = false)))
    }

    @Test
    fun `attended page json from the setlist fm api parses`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val page = json.decodeFromString<AttendedPage>(
            """
            {
              "type": "setlists",
              "itemsPerPage": 20,
              "page": 1,
              "total": 1,
              "setlist": [
                {
                  "id": "abc123",
                  "eventDate": "21-09-2025",
                  "url": "https://www.setlist.fm/setlist/test.html",
                  "artist": {"mbid": "x", "name": "Test Band"},
                  "venue": {"name": "The Venue", "city": {"name": "Testville", "country": {"code": "TL", "name": "Testland"}}},
                  "sets": {"set": [{"song": [{"name": "Song One"}, {"name": "Song Two", "cover": {"name": "Someone Else"}}]}]}
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, page.setlists.size)
        val setlist = page.setlists.single()
        assertEquals("Test Band", setlist.artist.name)
        val songs = extractSongs(setlist)
        assertEquals(2, songs.size)
        assertEquals("Someone Else", songs[1].searchArtist)
        assertTrue(songs[1].isCover)
    }
}
