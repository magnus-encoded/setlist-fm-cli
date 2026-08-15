package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.rankTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ranking a remembered line against a catalogue (#126).
 *
 * The band and titles in the first test are the real case the issue was written from —
 * a published artist and their published song names, which is catalogue data rather
 * than anyone's concert history, and the one fixture #126 asks not to let regress.
 * Everything else here is invented.
 */
class RememberedLineTest {

    // Øyvind Holm's catalogue, as MusicBrainz returns it, trimmed to the songs that
    // score at all plus one that does not.
    private val catalogue = listOf("High and Apple Sweet", "Toothpicks and Gum", "Between Stations")

    @Test
    fun `the line that motivated this ranks its own title first`() {
        val ranked = rankTitles("All held together by toothpicks and gum", catalogue)

        assertEquals("Toothpicks and Gum", ranked.first().title)
        assertEquals(1.0, ranked.first().score, 0.001)
        assertTrue("it sits in the line as a phrase", ranked.first().contained)
        assertTrue(ranked.first().confident)
    }

    @Test
    fun `a title sharing only a joining word is not promoted to an answer`() {
        // "High and Apple Sweet" shares "and" — one word of four. Arithmetic, not a
        // match, and the whole reason confidence is separate from order.
        val ranked = rankTitles("All held together by toothpicks and gum", catalogue)
        val high = ranked.single { it.title == "High and Apple Sweet" }

        assertEquals(0.25, high.score, 0.001)
        assertFalse(high.confident)
    }

    @Test
    fun `a line sharing no words with anything leaves nothing confident`() {
        // Story 13: degrade to "nothing confident" rather than promote a bad match.
        val ranked = rankTitles("words from a song nobody here recorded", catalogue)

        assertTrue(ranked.none { it.confident })
        assertEquals(catalogue.size, ranked.size)
    }

    @Test
    fun `the whole catalogue comes back, so a low match is still reachable`() {
        // Story 5: the sheet browses everything; it does not filter to close matches.
        val ranked = rankTitles("toothpicks", catalogue)

        assertEquals(catalogue.toSet(), ranked.map { it.title }.toSet())
    }

    @Test
    fun `punctuation and case do not decide a match`() {
        val ranked = rankTitles("i dont want to miss a thing", listOf("Don't Want to Miss a Thing"))

        assertEquals(1.0, ranked.first().score, 0.001)
        assertTrue(ranked.first().confident)
    }

    @Test
    fun `a title is not found across a word boundary`() {
        // "Sand" lives inside "toothpicks AND gum" only if spacing is thrown away.
        // songKey does throw it away, which is right for equality and wrong here.
        val ranked = rankTitles("all held together by toothpicks and gum", listOf("Sand"))

        assertFalse(ranked.first().contained)
    }

    @Test
    fun `a contained title outranks a higher-scoring scattered one`() {
        // Both score 1.0 on words; only one is actually a phrase in the line.
        val ranked = rankTitles("gum and toothpicks are all it took", listOf("Toothpicks and Gum", "Gum and Toothpicks"))

        assertEquals("Gum and Toothpicks", ranked.first().title)
    }

    @Test
    fun `an empty catalogue ranks nothing rather than failing`() {
        assertEquals(emptyList<String>(), rankTitles("anything at all", emptyList()).map { it.title })
    }

    @Test
    fun `equal candidates keep the order the catalogue gave them`() {
        // Stable, so the same sheet does not reshuffle between openings.
        val tied = listOf("One", "Two", "Three")

        assertEquals(tied, rankTitles("nothing in common", tied).map { it.title })
    }
}
