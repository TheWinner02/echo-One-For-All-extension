package dev.brahmkshatriya.echo.extension.oneforall

import dev.brahmkshatriya.echo.common.settings.Settings

/** An unsuccessful login must not overwrite the working account's preferences. */
internal class StagedSettings(private val base: Settings) : Settings {
    private data class Change(val value: Any?, val apply: () -> Unit)
    private val changes = linkedMapOf<String, Change>()
    @Suppress("UNCHECKED_CAST")
    @Synchronized private fun <T> read(key: String, fallback: () -> T?): T? =
        if (changes.containsKey(key)) changes.getValue(key).value as T? else fallback()
    @Synchronized private fun write(key: String, value: Any?, apply: () -> Unit) { changes[key] = Change(value, apply) }
    override fun getString(key: String): String? = read(key) {
        if (key in setOf("auth_token", "userAuthToken")) null else base.getString(key)
    }
    override fun getBoolean(key: String): Boolean? = read(key) { base.getBoolean(key) }
    override fun getInt(key: String): Int? = read(key) { base.getInt(key) }
    override fun getStringSet(key: String): Set<String>? = read(key) { base.getStringSet(key) }
    override fun putString(key: String, value: String?) = write(key, value) { base.putString(key, value) }
    override fun putBoolean(key: String, value: Boolean?) = write(key, value) { base.putBoolean(key, value) }
    override fun putInt(key: String, value: Int?) = write(key, value) { base.putInt(key, value) }
    override fun putStringSet(key: String, value: Set<String>?) = write(key, value?.toSet()) { base.putStringSet(key, value?.toSet()) }
    @Synchronized fun commit() { changes.values.forEach { it.apply() }; changes.clear() }
}
