package com.magnusencoded.setlistcompanion.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magnusencoded.setlistcompanion.AppContainer
import com.magnusencoded.setlistcompanion.core.SongEntry
import com.magnusencoded.setlistcompanion.core.extractSongs
import com.magnusencoded.setlistcompanion.core.playlistTitle
import com.magnusencoded.setlistcompanion.data.SetlistFmRepository
import com.magnusencoded.setlistcompanion.data.SpotifyRepository
import com.magnusencoded.setlistcompanion.data.SpotifyStatus
import kotlinx.coroutines.launch

class SetlistDetailViewModel(
    setlistFm: SetlistFmRepository,
    private val spotify: SpotifyRepository,
    setlistId: String,
) : ViewModel() {

    val setlist = setlistFm.setlistById(setlistId)
    val songs: List<SongEntry> = setlist?.let(::extractSongs) ?: emptyList()

    /** Which songs stay in the playlist; everything starts selected for review. */
    val selected = mutableStateListOf<Boolean>().apply { addAll(List(songs.size) { true }) }

    var playlistName by mutableStateOf(setlist?.let(::playlistTitle).orEmpty())
    var isPublic by mutableStateOf(false)
    var creating by mutableStateOf(false)
        private set
    var result by mutableStateOf<CreationResult?>(null)
        private set

    data class CreationResult(
        val playlistUrl: String?,
        val addedCount: Int,
        val missing: List<SongEntry>,
        val error: String? = null,
    )

    fun toggleSong(index: Int) {
        selected[index] = !selected[index]
        result = null
    }

    fun createPlaylist() {
        val current = setlist ?: return
        if (creating) return
        creating = true
        result = null
        viewModelScope.launch {
            try {
                val chosen = songs.filterIndexed { index, _ -> selected[index] }
                val uris = LinkedHashSet<String>()
                val missing = mutableListOf<SongEntry>()
                for (entry in chosen) {
                    val track = spotify.resolveTrack(entry)
                    if (track != null) uris.add(track.uri) else missing.add(entry)
                }
                result = if (uris.isEmpty()) {
                    CreationResult(null, 0, missing, error = "None of the selected songs were found on Spotify.")
                } else {
                    val url = spotify.createPlaylist(
                        name = playlistName.ifBlank { playlistTitle(current) },
                        public = isPublic,
                        description = current.url.ifBlank { "Created from a setlist.fm attended setlist." },
                        trackUris = uris.toList(),
                    )
                    CreationResult(url, uris.size, missing)
                }
            } catch (e: Exception) {
                result = CreationResult(null, 0, emptyList(), error = e.message ?: "Failed to create playlist")
            } finally {
                creating = false
            }
        }
    }
}

/**
 * Review a setlist before it goes to Spotify: rename the playlist, untick
 * songs you don't want, choose visibility, then create it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetlistDetailScreen(
    container: AppContainer,
    setlistId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SetlistDetailViewModel = viewModel(key = setlistId) {
        SetlistDetailViewModel(container.setlistFm, container.spotify, setlistId)
    }
    val spotifyStatus by container.spotifyAuth.status.collectAsState()
    val spotifyConnected = spotifyStatus is SpotifyStatus.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.setlist?.artist?.name ?: "Setlist") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val setlist = viewModel.setlist
        if (setlist == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Setlist not loaded - go back and try again.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        listOf(setlist.venue.name, setlist.venue.city.name, setlist.eventDate)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = viewModel.playlistName,
                        onValueChange = { viewModel.playlistName = it },
                        label = { Text("Playlist name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = viewModel.isPublic, onCheckedChange = { viewModel.isPublic = it })
                        Text("Public playlist", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        "${viewModel.selected.count { it }} of ${viewModel.songs.size} songs selected",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            itemsIndexed(viewModel.songs) { index, song ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleSong(index) },
                ) {
                    Checkbox(
                        checked = viewModel.selected[index],
                        onCheckedChange = { viewModel.toggleSong(index) },
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(song.title, style = MaterialTheme.typography.bodyLarge)
                        if (song.isCover) {
                            Text(
                                "cover of ${song.searchArtist}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (viewModel.songs.isEmpty()) {
                item { Text("setlist.fm has no songs recorded for this concert.") }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!spotifyConnected) {
                        Text(
                            "Connect Spotify to add this setlist to your library.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = onOpenSettings) { Text("Open settings") }
                    }
                    Button(
                        enabled = spotifyConnected && !viewModel.creating &&
                            viewModel.selected.any { it },
                        onClick = viewModel::createPlaylist,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (viewModel.creating) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("Matching songs on Spotify…")
                        } else {
                            Text("Add to Spotify library")
                        }
                    }

                    viewModel.result?.let { result ->
                        Card {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                when {
                                    result.error != null -> Text(
                                        result.error,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    else -> Text("Playlist created with ${result.addedCount} tracks.")
                                }
                                if (result.missing.isNotEmpty()) {
                                    Text(
                                        "Not found on Spotify:",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    result.missing.forEach { missing ->
                                        Text(
                                            "· ${missing.searchArtist} - ${missing.title}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                result.playlistUrl?.let { url ->
                                    OutlinedButton(onClick = {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }) {
                                        Text("Open in Spotify")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
