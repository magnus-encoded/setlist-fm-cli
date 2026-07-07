package com.magnusencoded.setlistcompanion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magnusencoded.setlistcompanion.AppContainer
import com.magnusencoded.setlistcompanion.core.Setlist
import com.magnusencoded.setlistcompanion.core.extractSongs
import com.magnusencoded.setlistcompanion.data.SetlistFmRepository
import kotlinx.coroutines.launch

class AttendedViewModel(private val repository: SetlistFmRepository) : ViewModel() {
    var setlists by mutableStateOf<List<Setlist>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(true)
        private set

    private var nextPage = 1

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val page = repository.attendedPage(nextPage)
                setlists = setlists + page.setlists
                hasMore = page.setlists.isNotEmpty() &&
                    nextPage * page.itemsPerPage < page.total
                nextPage++
            } catch (e: Exception) {
                error = e.message ?: "Failed to load attended concerts"
            } finally {
                loading = false
            }
        }
    }
}

/** The user's attended concerts, newest first as returned by setlist.fm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendedScreen(
    container: AppContainer,
    onOpenSetlist: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: AttendedViewModel = viewModel { AttendedViewModel(container.setlistFm) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Attended concerts") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            itemsIndexed(viewModel.setlists) { _, setlist ->
                AttendedRow(setlist, onClick = { onOpenSetlist(setlist.id) })
                HorizontalDivider()
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        viewModel.loading -> CircularProgressIndicator()
                        viewModel.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                viewModel.error.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = viewModel::loadMore) { Text("Retry") }
                        }
                        viewModel.hasMore -> Button(onClick = viewModel::loadMore) { Text("Load more") }
                        viewModel.setlists.isEmpty() -> Text("No attended concerts found.")
                    }
                }
            }
        }
    }
}

@Composable
private fun AttendedRow(setlist: Setlist, onClick: () -> Unit) {
    val songCount = extractSongs(setlist).size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(setlist.artist.name, style = MaterialTheme.typography.titleMedium)
        Text(
            listOf(setlist.venue.name, setlist.venue.city.name)
                .filter { it.isNotBlank() }
                .joinToString(", "),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (songCount > 0) "${setlist.eventDate} · $songCount songs"
            else "${setlist.eventDate} · no setlist data",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
