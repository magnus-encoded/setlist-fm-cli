package com.magnusencoded.setlistcompanion.ui

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.magnusencoded.setlistcompanion.AppContainer
import com.magnusencoded.setlistcompanion.data.SpotifyAuthManager
import com.magnusencoded.setlistcompanion.data.SpotifyStatus

/**
 * Login/setup for both services: setlist.fm API key + username, and the
 * Spotify OAuth connection (client id + browser authorization).
 */
@Composable
fun ConnectScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = container.credentials

    var apiKey by remember { mutableStateOf(store.setlistFmApiKey.orEmpty()) }
    var userId by remember { mutableStateOf(store.setlistFmUserId.orEmpty()) }
    var clientId by remember { mutableStateOf(store.spotifyClientId.orEmpty()) }
    val spotifyStatus by container.spotifyAuth.status.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Setlist Companion", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Turn the concerts you've attended on setlist.fm into Spotify playlists.",
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()
        Text("setlist.fm", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = userId,
            onValueChange = { userId = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Request an API key at setlist.fm/settings/api.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("Spotify", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val status = spotifyStatus) {
            is SpotifyStatus.Connected ->
                Text("Connected as ${status.displayName}", color = MaterialTheme.colorScheme.primary)
            is SpotifyStatus.Error ->
                Text(status.message, color = MaterialTheme.colorScheme.error)
            SpotifyStatus.Connecting ->
                Text("Waiting for Spotify authorization…")
            SpotifyStatus.Disconnected ->
                Text("Not connected")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = clientId.isNotBlank(),
                onClick = {
                    val authUri = container.spotifyAuth.beginAuthorization(clientId.trim())
                    CustomTabsIntent.Builder().build().launchUrl(context, authUri)
                },
            ) {
                Text(if (spotifyStatus is SpotifyStatus.Connected) "Reconnect" else "Connect Spotify")
            }
            if (spotifyStatus is SpotifyStatus.Connected) {
                OutlinedButton(onClick = { container.spotifyAuth.disconnect() }) {
                    Text("Disconnect")
                }
            }
        }
        Text(
            "Create an app at developer.spotify.com/dashboard and add " +
                "${SpotifyAuthManager.REDIRECT_URI} as a redirect URI. " +
                "No client secret is needed - the app uses the PKCE flow.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Button(
            enabled = apiKey.isNotBlank() && userId.isNotBlank(),
            onClick = {
                store.setlistFmApiKey = apiKey.trim()
                store.setlistFmUserId = userId.trim()
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Browse attended concerts")
        }
    }
}
