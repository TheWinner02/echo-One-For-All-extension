package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.WebViewClient
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.providers.*
import dev.brahmkshatriya.echo.common.settings.*
import dev.brahmkshatriya.echo.extension.oneforall.*
import dev.brahmkshatriya.echo.oneforall.engine.CombineEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout

/** Standalone entry point. All providers are packaged here, never taken from Echo's installed list. */
class OneForAllExtension : CombineEngine(), LoginClient.CustomInput, WebViewClientProvider,
    MessageFlowProvider, NetworkConnectionProvider, SettingsChangeListenerClient {
    private lateinit var registry: ProviderRegistry
    private lateinit var root: Settings
    private var global: Settings? = null
    private var web: WebViewClient? = null
    private var messages: MutableSharedFlow<Message>? = null
    private var network: NetworkConnection? = null
    private val router = SourceRouter({ stream, download -> super.loadStreamableMedia(stream, download) })

    override fun setSettings(settings: Settings) {
        root = settings
        val defaults = EngineSettings(settings)
        listOf(KEY_METADATA_EXT, KEY_STREAM_EXT, "stream_extension_1", "stream_extension_2", "stream_extension_3", "stream_extension_4", "stream_extension_5")
            .forEach { key -> if (settings.getString(key) == null) settings.putString(key, defaults.getString(key)) }
        registry = ProviderRegistry(settings).also { r ->
            r.global = global; r.web = web; r.messages = messages; r.network = network
        }
        super.setSettings(EngineSettings(settings))
        syncProviders()
    }
    private fun syncProviders() { if (::registry.isInitialized) super.setMusicExtensions(registry.extensions()) }
    override fun setMusicExtensions(extensions: List<MusicExtension>) { syncProviders() }
    override fun setGlobalSettings(globalSettings: Settings) {
        global = globalSettings; super.setGlobalSettings(globalSettings)
        if (::registry.isInitialized) registry.global = globalSettings
    }
    override fun setWebViewClient(webViewClient: WebViewClient) {
        web = webViewClient; if (::registry.isInitialized) registry.web = webViewClient
    }
    override fun setMessageFlow(messageFlow: MutableSharedFlow<Message>) {
        messages = messageFlow; if (::registry.isInitialized) registry.messages = messageFlow
    }
    override fun setNetworkConnection(networkConnection: NetworkConnection) {
        network = networkConnection; if (::registry.isInitialized) registry.network = networkConnection
    }
    override val forms get() = registry.forms()
    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        val users = registry.login(key, data)
        syncProviders()
        return users
    }
    override fun setLoginUser(user: User?) { if (::registry.isInitialized) registry.activate(user?.id == ProviderRegistry.ACCOUNT_ID) }
    override suspend fun getCurrentUser() = registry.publicAccount()
    override suspend fun onSettingsChanged(settings: Settings, key: String?) {
        registry.changed(key); syncProviders(); router.reset()
    }
    override suspend fun getSettingItems(): List<Setting> {
        val defaults = ProviderRegistry.defaults()
        val allNames = defaults.map { it.name }
        val allIds = defaults.map { it.id }
        val altNames = listOf("None") + allNames
        val altIds = listOf("none") + allIds
        val coordinator = super.getSettingItems().filterNot { it.key == KEY_FIRST_AVAILABLE || it.key == "manage_hidden_tracks" }
            .map { item ->
                if (item is SettingList) {
                    when {
                        item.key == KEY_METADATA_EXT -> item.copy(entryTitles = allNames, entryValues = allIds)
                        item.key == KEY_STREAM_EXT -> item.copy(entryTitles = allNames, entryValues = allIds)
                        item.key.startsWith(KEY_STREAM_EXT) -> item.copy(entryTitles = altNames, entryValues = altIds)
                        else -> item
                    }
                } else item
            }
        return listOf(SettingCategory("One For All", "oneforall.coordinator", coordinator +
            SettingSwitch("Automatic source fallback", "oneforall.fallback", "Try the other matched services if a resolver fails. A saved recording choice stays exact.", true) +
            SettingOnClick("Retry unavailable services", "oneforall.reset", "Clear the temporary service cooldown.") { router.reset() })) + registry.settings()
    }
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val loaded = withTimeout(40_000) { super.loadTrack(track, isDownload) }
        val exact = track.extras.keys.any { it.startsWith("echo.source.v1.preferred.") }
        return if (root.getBoolean("oneforall.fallback") != false && !exact)
            loaded.copy(streamables = router.attach(loaded.streamables)) else loaded
    }
    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media =
        router.load(streamable, isDownload)
}
