package com.magnusencoded.setlistcompanion.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttendedPage(
    @SerialName("setlist") val setlists: List<Setlist> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val itemsPerPage: Int = 20,
)

@Serializable
data class Setlist(
    val id: String = "",
    val eventDate: String = "",
    val url: String = "",
    val artist: ArtistRef = ArtistRef(),
    val venue: Venue = Venue(),
    val sets: Sets = Sets(),
)

@Serializable
data class ArtistRef(val name: String = "")

@Serializable
data class Venue(val name: String = "", val city: City = City())

@Serializable
data class City(val name: String = "", val country: Country = Country())

@Serializable
data class Country(val name: String = "")

@Serializable
data class Sets(@SerialName("set") val sections: List<SetSection> = emptyList())

@Serializable
data class SetSection(
    val name: String = "",
    val encore: Int = 0,
    @SerialName("song") val songs: List<Song> = emptyList(),
)

@Serializable
data class Song(val name: String = "", val cover: ArtistRef? = null)
