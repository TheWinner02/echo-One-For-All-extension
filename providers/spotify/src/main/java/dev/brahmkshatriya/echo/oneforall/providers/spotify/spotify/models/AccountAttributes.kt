package dev.brahmkshatriya.echo.oneforall.providers.spotify.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class AccountAttributes (
    val data: Data
) {

    @Serializable
    data class Data(
        val me: Me
    )

    @Serializable
    data class Me(
        val account: Account
    )

    @Serializable
    data class Account(
        val country: String? = null,
        val product: String? = null
    )
}