package com.magnusencoded.setlistcompanion.data

import android.net.Uri
import com.magnusencoded.setlistcompanion.core.AddTracksRequest
import com.magnusencoded.setlistcompanion.core.AttendedPage
import com.magnusencoded.setlistcompanion.core.CreatePlaylistRequest
import com.magnusencoded.setlistcompanion.core.Setlist
import com.magnusencoded.setlistcompanion.core.SongEntry
import com.magnusencoded.setlistcompanion.core.Track
import com.magnusencoded.setlistcompanion.core.searchQueries

class SetlistFmRepository(
    private val service: SetlistFmService,
    private val store: CredentialStore,
) {
    // Detail screens look setlists up by id from the pages already fetched.
    private val cache = LinkedHashMap<String, Setlist>()

    val isConfigured: Boolean
        get() = !store.setlistFmApiKey.isNullOrBlank() && !store.setlistFmUserId.isNullOrBlank()

    suspend fun attendedPage(page: Int): AttendedPage {
        val apiKey = checkNotNull(store.setlistFmApiKey) { "setlist.fm API key not configured" }
        val userId = checkNotNull(store.setlistFmUserId) { "setlist.fm username not configured" }
        val result = service.attended(userId, page, apiKey)
        result.setlists.forEach { if (it.id.isNotBlank()) cache[it.id] = it }
        return result
    }

    fun setlistById(id: String): Setlist? = cache[id]
}

class SpotifyRepository(
    private val service: SpotifyService,
    private val auth: SpotifyAuthManager,
) {
    /** Finish the OAuth round-trip started in the browser, then load the profile. */
    suspend fun completeAuth(redirect: Uri): Boolean {
        if (!auth.handleRedirect(redirect)) return false
        // Best effort: the tokens are already stored, so a failed profile
        // fetch (e.g. flaky network) still leaves us connected.
        runCatching { service.me() }.onSuccess(auth::onProfileLoaded)
        return true
    }

    /** Resolve a song to a Spotify track, trying strict-to-loose queries. */
    suspend fun resolveTrack(entry: SongEntry): Track? {
        for (query in searchQueries(entry)) {
            val items = try {
                service.search(query).tracks.items
            } catch (e: Exception) {
                return null
            }
            if (items.isNotEmpty()) return items.first()
        }
        return null
    }

    /** Create a playlist and add the tracks, chunked to Spotify's 100-item limit. */
    suspend fun createPlaylist(
        name: String,
        public: Boolean,
        description: String,
        trackUris: List<String>,
    ): String {
        val playlist = service.createPlaylist(CreatePlaylistRequest(name, public, description))
        trackUris.chunked(100).forEach { chunk ->
            service.addTracks(playlist.id, AddTracksRequest(chunk))
        }
        return playlist.externalUrls["spotify"]
            ?: "https://open.spotify.com/playlist/${playlist.id}"
    }
}
