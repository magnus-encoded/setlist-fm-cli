package com.magnusencoded.setlistcompanion.data

import com.magnusencoded.setlistcompanion.core.AddTracksRequest
import com.magnusencoded.setlistcompanion.core.AttendedPage
import com.magnusencoded.setlistcompanion.core.CreatePlaylistRequest
import com.magnusencoded.setlistcompanion.core.PlaylistResponse
import com.magnusencoded.setlistcompanion.core.SpotifyTokens
import com.magnusencoded.setlistcompanion.core.SpotifyUser
import com.magnusencoded.setlistcompanion.core.TrackSearchResponse
import retrofit2.http.Body
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** setlist.fm REST API (https://api.setlist.fm/rest/1.0/). */
interface SetlistFmService {
    @Headers("Accept: application/json")
    @GET("user/{userId}/attended")
    suspend fun attended(
        @Path("userId") userId: String,
        @Query("p") page: Int,
        @Header("x-api-key") apiKey: String,
    ): AttendedPage
}

/** Spotify accounts service (https://accounts.spotify.com/) for the PKCE token exchange. */
interface SpotifyAccountsService {
    @FormUrlEncoded
    @POST("api/token")
    suspend fun token(@FieldMap fields: Map<String, String>): SpotifyTokens
}

/** Spotify Web API (https://api.spotify.com/v1/); auth is added by an interceptor. */
interface SpotifyService {
    @GET("me")
    suspend fun me(): SpotifyUser

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String = "track",
        @Query("limit") limit: Int = 1,
    ): TrackSearchResponse

    @POST("me/playlists")
    suspend fun createPlaylist(@Body body: CreatePlaylistRequest): PlaylistResponse

    @POST("playlists/{playlistId}/tracks")
    suspend fun addTracks(@Path("playlistId") playlistId: String, @Body body: AddTracksRequest)
}
