package com.magnusencoded.setlistcompanion.core

/**
 * A song to look up on Spotify. For covers the search artist is the original
 * artist, matching the behaviour of extract_songs in setlistfm_cli.py.
 */
data class SongEntry(
    val title: String,
    val searchArtist: String,
    val isCover: Boolean,
)

/** Extract the songs played in a setlist; blank names (segues/spacers) are skipped. */
fun extractSongs(setlist: Setlist): List<SongEntry> {
    val performingArtist = setlist.artist.name
    return setlist.sets.sections.flatMap { section ->
        section.songs.mapNotNull { song ->
            val title = song.name.trim()
            if (title.isEmpty()) return@mapNotNull null
            val coverArtist = song.cover?.name?.takeIf { it.isNotBlank() }
            SongEntry(
                title = title,
                searchArtist = coverArtist ?: performingArtist,
                isCover = song.cover != null,
            )
        }
    }
}

/** Build a playlist title like "Artist @ Venue, City (DD-MM-YYYY)". */
fun playlistTitle(setlist: Setlist): String {
    val artist = setlist.artist.name.ifBlank { "Unknown Artist" }
    val location = listOf(setlist.venue.name, setlist.venue.city.name)
        .filter { it.isNotBlank() }
        .joinToString(", ")

    return buildString {
        append(artist)
        if (location.isNotEmpty()) append(" @ ").append(location)
        if (setlist.eventDate.isNotBlank()) append(" (").append(setlist.eventDate).append(")")
    }
}

/**
 * Spotify search queries for a song, strictest first: a field-qualified query,
 * then a plain "title artist" query, then the bare title.
 */
fun searchQueries(entry: SongEntry): List<String> = buildList {
    if (entry.searchArtist.isNotBlank()) {
        add("track:${entry.title} artist:${entry.searchArtist}")
        add("${entry.title} ${entry.searchArtist}")
    }
    add(entry.title)
}
