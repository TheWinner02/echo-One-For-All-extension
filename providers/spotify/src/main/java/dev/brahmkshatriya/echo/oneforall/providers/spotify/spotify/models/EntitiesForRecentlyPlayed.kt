package dev.brahmkshatriya.echo.oneforall.providers.spotify.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class EntitiesForRecentlyPlayed(
    val data: Data
){
    @Serializable
    data class Data(
        val lookup: List<Item.Wrapper>
    )
}
