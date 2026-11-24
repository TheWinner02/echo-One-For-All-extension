package dev.brahmkshatriya.echo.oneforall.providers.spotify.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class CreatePlaylist(
    val revision: String,
    val uri: String
)