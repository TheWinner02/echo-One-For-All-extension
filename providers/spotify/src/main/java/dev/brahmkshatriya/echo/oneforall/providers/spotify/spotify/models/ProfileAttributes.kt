package dev.brahmkshatriya.echo.oneforall.providers.spotify.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class ProfileAttributes(
    val data: Data
) {

    @Serializable
    data class Data(
        val me: Me
    )

    @Serializable
    data class Me(
        val profile: Profile
    )
}