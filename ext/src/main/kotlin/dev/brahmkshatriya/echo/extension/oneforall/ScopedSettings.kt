package dev.brahmkshatriya.echo.extension.oneforall

import dev.brahmkshatriya.echo.common.settings.*

/** Service keys cannot overwrite another service's account, preferences or cache. */
internal class ScopedSettings(private val root: Settings, val prefix: String, private val authenticated: () -> Boolean = { true }) : Settings {
    override fun getString(key: String): String? =
        if (!authenticated() && key in setOf("auth_token", "userAuthToken")) null else root.getString(prefix + key)
    override fun getBoolean(key: String) = root.getBoolean(prefix + key)
    override fun getInt(key: String) = root.getInt(prefix + key)
    override fun getStringSet(key: String) = root.getStringSet(prefix + key)
    override fun putString(key: String, value: String?) = root.putString(prefix + key, value)
    override fun putBoolean(key: String, value: Boolean?) = root.putBoolean(prefix + key, value)
    override fun putInt(key: String, value: Int?) = root.putInt(prefix + key, value)
    override fun putStringSet(key: String, value: Set<String>?) = root.putStringSet(prefix + key, value)

    fun expose(item: Setting): Setting = when (item) {
        is SettingCategory -> item.copy(key = prefix + item.key, items = item.items.map(::expose))
        is SettingSwitch -> item.copy(key = prefix + item.key)
        is SettingList -> item.copy(key = prefix + item.key)
        is SettingMultipleChoice -> item.copy(key = prefix + item.key)
        is SettingSlider -> item.copy(key = prefix + item.key)
        is SettingTextInput -> item.copy(key = prefix + item.key)
        is SettingOnClick -> item.copy(key = prefix + item.key)
        is SettingItem -> item.copy(key = prefix + item.key)
    }
}

internal class EngineSettings(private val root: Settings) : Settings by root {
    override fun getString(key: String) = root.getString(key) ?: when (key) {
        "metadata_extension" -> "spotify"
        "stream_extension" -> "deezer"
        "stream_extension_1" -> "youtube_music"
        "stream_extension_2" -> "soundcloud"
        "stream_extension_3" -> "qobuz"
        "stream_extension_4" -> "tidal"
        "stream_extension_5" -> "none"
        else -> null
    }
}
