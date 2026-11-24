package dev.brahmkshatriya.echo.oneforall.providers.spotify.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class GetTrack(
    val data: Data
){
    @Serializable
    data class Data(
        val trackUnion: Item.Track
    )
}
