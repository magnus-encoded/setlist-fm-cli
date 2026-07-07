package com.magnusencoded.setlistcompanion

import android.app.Application
import android.content.Context
import com.magnusencoded.setlistcompanion.data.CredentialStore
import com.magnusencoded.setlistcompanion.data.SetlistFmRepository
import com.magnusencoded.setlistcompanion.data.SetlistFmService
import com.magnusencoded.setlistcompanion.data.SpotifyAccountsService
import com.magnusencoded.setlistcompanion.data.SpotifyAuthManager
import com.magnusencoded.setlistcompanion.data.SpotifyRepository
import com.magnusencoded.setlistcompanion.data.SpotifyService
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class SetlistCompanionApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Plain manual dependency wiring; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val credentials = CredentialStore(context)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val jsonMediaType = "application/json".toMediaType()
    private val baseClient = OkHttpClient()

    private fun retrofit(baseUrl: String, client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory(jsonMediaType))
        .build()

    private val accountsService: SpotifyAccountsService =
        retrofit("https://accounts.spotify.com/", baseClient)
            .create(SpotifyAccountsService::class.java)

    val spotifyAuth = SpotifyAuthManager(credentials, accountsService)

    private val spotifyClient = baseClient.newBuilder()
        .addInterceptor { chain ->
            // Interceptors run on OkHttp's dispatcher threads, never the main
            // thread, so a blocking token refresh here is safe.
            val token = runBlocking { spotifyAuth.validAccessToken() }
                ?: throw IOException("Not connected to Spotify")
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            )
        }
        .build()

    private val spotifyService: SpotifyService =
        retrofit("https://api.spotify.com/v1/", spotifyClient)
            .create(SpotifyService::class.java)

    private val setlistFmService: SetlistFmService =
        retrofit("https://api.setlist.fm/rest/1.0/", baseClient)
            .create(SetlistFmService::class.java)

    val setlistFm = SetlistFmRepository(setlistFmService, credentials)
    val spotify = SpotifyRepository(spotifyService, spotifyAuth)
}
