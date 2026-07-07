package com.magnusencoded.setlistcompanion.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

@Serializable
data class SpotifyUser(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class TrackSearchResponse(val tracks: TrackPage = TrackPage())

@Serializable
data class TrackPage(val items: List<Track> = emptyList())

@Serializable
data class Track(
    val uri: String = "",
    val name: String = "",
    val artists: List<TrackArtist> = emptyList(),
)

@Serializable
data class TrackArtist(val name: String = "")

@Serializable
data class CreatePlaylistRequest(
    val name: String,
    val public: Boolean,
    val description: String = "",
)

@Serializable
data class PlaylistResponse(
    val id: String = "",
    @SerialName("external_urls") val externalUrls: Map<String, String> = emptyMap(),
)

@Serializable
data class AddTracksRequest(val uris: List<String>)
