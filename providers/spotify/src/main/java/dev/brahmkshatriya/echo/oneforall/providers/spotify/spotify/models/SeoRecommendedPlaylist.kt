package dev.brahmkshatriya.echo.oneforall.providers.spotify.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class SeoRecommendedPlaylist(
    val data : Data
) {

    @Serializable
    data class Data(
        val seoRecommendedPlaylist: SeoRecommendedPlaylist
    )

    @Serializable
    data class SeoRecommendedPlaylist(
        val items: List<Item.Wrapper>
    )
}
