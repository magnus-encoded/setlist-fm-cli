package io.github.magnusencoded.stationtostation.data

/**
 * Ranking a **Log** entry's written words against an artist's own catalogue (#126).
 *
 * A **Log** is typed in the dark while the band is still playing, so what lands in an
 * entry is sometimes the song's title and sometimes the only words the owner could
 * catch. Nothing in the two strings tells them apart, and no rule can: real titles are
 * often whole sentences, and remembered lines usually contain the title. So this does
 * not classify anything. It ranks, and a person taps.
 *
 * Pure arithmetic over two strings, which is why it lives here and not in the sheet —
 * ADR-0001, and the same function ports to iOS with these fixtures rather than being
 * re-derived from the description.
 */

/**
 * One catalogue title, scored against the line someone wrote.
 *
 * [score] is the fraction of the **title's** own words that appear in the line, which
 * is the direction that matters: a remembered line is longer than a title and drags
 * every score down if measured the other way. On the case that motivated #126 —
 * *"All held together by toothpicks and gum"* — this puts *Toothpicks and Gum* at 1.0
 * and *High and Apple Sweet* at 0.25, on the strength of the word "and" alone. That
 * 0.25 is exactly why [confident] exists.
 */
data class TitleCandidate(
    val title: String,
    val score: Double,
    /** The title appears in the line as a contiguous phrase, not scattered words. */
    val contained: Boolean,
) {
    /**
     * Worth showing as an answer rather than as something to scroll past.
     *
     * Containment, or most of the title's words. A title sharing only "and" with the
     * line is arithmetic, not a match, and promoting it to first place would be the
     * app guessing — which is the one thing #126 says it must not do.
     */
    val confident: Boolean get() = contained || score >= 0.5
}

/**
 * The catalogue, best first. Every title is returned, never only the close ones:
 * a low-ranking answer must still be reachable by scrolling (#126, story 5), and an
 * empty result would read as "this artist has no songs" rather than "no good match".
 *
 * Order: contained first, then by score, then the order the catalogue arrived in — a
 * stable sort, so two equally-scored titles do not swap places between openings of the
 * same sheet.
 *
 * Containment outranks score outright because it is a different quality of evidence.
 * *Toothpicks and Gum* sits inside that line as a phrase; three words scattered across
 * a sentence is a coincidence that a long enough line makes inevitable.
 */
fun rankTitles(rememberedLine: String, catalogue: List<String>): List<TitleCandidate> {
    val lineWords = rememberedLine.titleWords()
    val linePhrase = rememberedLine.phrase()
    return catalogue
        .map { title ->
            val words = title.titleWords()
            TitleCandidate(
                title = title,
                score = if (words.isEmpty()) 0.0 else words.count { it in lineWords }.toDouble() / words.size,
                contained = title.phrase().let { it.isNotEmpty() && linePhrase.contains(it) },
            )
        }
        .sortedWith(compareByDescending<TitleCandidate> { it.contained }.thenByDescending { it.score })
}

/**
 * Lowercased, punctuation to spaces, padded with one space at each end.
 *
 * The padding is the point: containment is tested on this, and without word boundaries
 * the title *Sand* would be "contained" in "toothpicks **and** gum" — a false match
 * manufactured by throwing spacing away. [songKey] discards spacing deliberately and is
 * right to for equality; it is exactly wrong for substring search.
 */
private fun String.phrase(): String = " " + normalisedWords().joinToString(" ") + " "

/** The words of a title, normalised the way [songKey] normalises, so "Don't" meets "Dont". */
private fun String.titleWords(): Set<String> = normalisedWords().toSet()

/**
 * Lowercased words with punctuation gone, apostrophes closed rather than split.
 *
 * Splitting on every non-alphanumeric would make "Don't" into "don" and "t", so it
 * would never meet the "dont" someone typed — which is the one case [songKey] exists
 * to handle. Apostrophes are removed first so the contraction stays one word; every
 * other punctuation mark is a word boundary, because that is what it is.
 */
private fun String.normalisedWords(): List<String> =
    replace("'", "").replace("’", "")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }
        .map { it.songKey() }
