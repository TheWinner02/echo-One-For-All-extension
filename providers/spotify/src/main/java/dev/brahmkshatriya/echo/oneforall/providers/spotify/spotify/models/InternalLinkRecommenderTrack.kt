package dev.brahmkshatriya.echo.oneforall.providers.spotify.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class InternalLinkRecommenderTrack(
    val data: Data
){
    @Serializable
    data class Data(
        val seoRecommendedTrack: SeoRecommendedTrack
    )

    @Serializable
    data class SeoRecommendedTrack(
        val items: List<Item.TrackWrapper>
    )
}
