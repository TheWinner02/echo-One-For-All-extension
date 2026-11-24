package dev.brahmkshatriya.echo.oneforall.providers.spotify.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class FetchPlaylist(
    val data: Data
) {
    @Serializable
    data class Data(
        val playlistV2: Item.Playlist
    )
}
