package com.magnusencoded.setlistcompanion.data

import android.net.Uri
import com.magnusencoded.setlistcompanion.core.Pkce
import com.magnusencoded.setlistcompanion.core.SpotifyTokens
import com.magnusencoded.setlistcompanion.core.SpotifyUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SpotifyStatus {
    data object Disconnected : SpotifyStatus
    data object Connecting : SpotifyStatus
    data class Connected(val displayName: String) : SpotifyStatus
    data class Error(val message: String) : SpotifyStatus
}

/**
 * Spotify Authorization Code + PKCE flow. The app never holds a client
 * secret: authorization happens in a browser tab and is proven with a code
 * verifier, and the refresh token is stored encrypted on the device.
 */
class SpotifyAuthManager(
    private val store: CredentialStore,
    private val accounts: SpotifyAccountsService,
) {
    companion object {
        const val REDIRECT_URI = "setlist-companion://callback"
        private const val SCOPE = "playlist-modify-public playlist-modify-private"
        private const val EXPIRY_MARGIN_MS = 60_000L
    }

    private val refreshMutex = Mutex()

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<SpotifyStatus> = _status.asStateFlow()

    val isConnected: Boolean get() = store.spotifyRefreshToken != null

    private fun initialStatus(): SpotifyStatus =
        if (store.spotifyRefreshToken != null) {
            SpotifyStatus.Connected(store.spotifyDisplayName ?: store.spotifyUserId ?: "Spotify user")
        } else {
            SpotifyStatus.Disconnected
        }

    /** Build the authorization URL and persist the PKCE state for the round-trip. */
    fun beginAuthorization(clientId: String): Uri {
        val verifier = Pkce.generateCodeVerifier()
        val state = Pkce.generateState()
        store.spotifyClientId = clientId
        store.pendingCodeVerifier = verifier
        store.pendingState = state
        _status.value = SpotifyStatus.Connecting
        return Uri.Builder()
            .scheme("https")
            .authority("accounts.spotify.com")
            .path("authorize")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", Pkce.codeChallenge(verifier))
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
            .build()
    }

    /** Exchange the redirect's authorization code for tokens. Returns true on success. */
    suspend fun handleRedirect(redirect: Uri): Boolean {
        redirect.getQueryParameter("error")?.let {
            _status.value = SpotifyStatus.Error("Spotify authorization failed: $it")
            return false
        }
        val code = redirect.getQueryParameter("code")
        val verifier = store.pendingCodeVerifier
        val clientId = store.spotifyClientId
        if (code == null || verifier == null || clientId == null ||
            redirect.getQueryParameter("state") != store.pendingState
        ) {
            _status.value = SpotifyStatus.Error("Authorization state mismatch; please try again")
            return false
        }

        return try {
            val tokens = accounts.token(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                )
            )
            storeTokens(tokens)
            store.pendingCodeVerifier = null
            store.pendingState = null
            _status.value = SpotifyStatus.Connected(store.spotifyDisplayName ?: "Spotify user")
            true
        } catch (e: Exception) {
            _status.value = SpotifyStatus.Error("Token exchange failed: ${e.message}")
            false
        }
    }

    fun onProfileLoaded(user: SpotifyUser) {
        store.spotifyUserId = user.id
        store.spotifyDisplayName = user.displayName
        _status.value = SpotifyStatus.Connected(user.displayName ?: user.id)
    }

    /** Return a live access token, refreshing through the mutex when close to expiry. */
    suspend fun validAccessToken(): String? {
        refreshMutex.withLock {
            val current = store.spotifyAccessToken
            if (current != null && System.currentTimeMillis() < store.spotifyTokenExpiresAt - EXPIRY_MARGIN_MS) {
                return current
            }
            val refreshToken = store.spotifyRefreshToken ?: return null
            val clientId = store.spotifyClientId ?: return null
            return try {
                val tokens = accounts.token(
                    mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken,
                        "client_id" to clientId,
                    )
                )
                storeTokens(tokens)
                tokens.accessToken
            } catch (e: Exception) {
                null
            }
        }
    }

    fun disconnect() {
        store.clearSpotifySession()
        _status.value = SpotifyStatus.Disconnected
    }

    private fun storeTokens(tokens: SpotifyTokens) {
        store.spotifyAccessToken = tokens.accessToken
        // PKCE token responses may rotate the refresh token; keep the old one otherwise.
        tokens.refreshToken?.let { store.spotifyRefreshToken = it }
        store.spotifyTokenExpiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
    }
}
