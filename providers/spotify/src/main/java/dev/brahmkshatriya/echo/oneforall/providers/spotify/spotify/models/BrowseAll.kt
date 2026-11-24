package dev.brahmkshatriya.echo.oneforall.providers.spotify.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class BrowseAll (
    val data: Data,
) {

    @Serializable
    data class Data(
        val browseStart: Sections.Container
    )
}
