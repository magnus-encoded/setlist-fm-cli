package com.magnusencoded.setlistcompanion.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest storage for everything sensitive: the setlist.fm API key
 * and the Spotify OAuth client id, tokens, and in-flight PKCE state.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "setlist_companion_secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var setlistFmApiKey: String? by string("setlistfm_api_key")
    var setlistFmUserId: String? by string("setlistfm_user_id")

    var spotifyClientId: String? by string("spotify_client_id")
    var spotifyAccessToken: String? by string("spotify_access_token")
    var spotifyRefreshToken: String? by string("spotify_refresh_token")
    var spotifyUserId: String? by string("spotify_user_id")
    var spotifyDisplayName: String? by string("spotify_display_name")

    // PKCE state for the authorization round-trip currently in flight.
    var pendingCodeVerifier: String? by string("spotify_pending_verifier")
    var pendingState: String? by string("spotify_pending_state")

    var spotifyTokenExpiresAt: Long
        get() = prefs.getLong("spotify_token_expires_at", 0L)
        set(value) = prefs.edit { putLong("spotify_token_expires_at", value) }

    fun clearSpotifySession() {
        prefs.edit {
            remove("spotify_access_token")
            remove("spotify_refresh_token")
            remove("spotify_token_expires_at")
            remove("spotify_user_id")
            remove("spotify_display_name")
            remove("spotify_pending_verifier")
            remove("spotify_pending_state")
        }
    }

    private fun string(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String? =
            prefs.getString(key, null)

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) =
            prefs.edit { if (value == null) remove(key) else putString(key, value) }
    }
}
