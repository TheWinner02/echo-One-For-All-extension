package dev.brahmkshatriya.echo.extension.oneforall

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.*
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.providers.*
import dev.brahmkshatriya.echo.common.settings.*
import dev.brahmkshatriya.echo.oneforall.providers.spotify.SpotifyExtension
import dev.brahmkshatriya.echo.oneforall.providers.deezer.DeezerExtension
import dev.brahmkshatriya.echo.oneforall.providers.youtube_music.YoutubeExtension
import dev.brahmkshatriya.echo.oneforall.providers.soundcloud.SoundCloudExtension
import dev.brahmkshatriya.echo.oneforall.providers.qobuz.QobuzExtension
import dev.brahmkshatriya.echo.oneforall.providers.tidal.TidalExtension
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProviderSpec(val id: String, val name: String, val create: () -> ExtensionClient)

internal class ProviderRegistry(private val root: Settings, specs: List<ProviderSpec> = defaults()) {
    companion object {
        const val ACCOUNT_ID = "one_for_all"
        const val LOGIN_TIMEOUT_MS = 10 * 60_000L
        fun defaults() = listOf(
            ProviderSpec("spotify", "Spotify", ::SpotifyExtension),
            ProviderSpec("deezer", "Deezer", ::DeezerExtension),
            ProviderSpec("youtube_music", "YouTube Music", ::YoutubeExtension),
            ProviderSpec("soundcloud", "SoundCloud", ::SoundCloudExtension),
            ProviderSpec("qobuz", "Qobuz", ::QobuzExtension),
            ProviderSpec("tidal", "Tidal", ::TidalExtension))
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val loginMutex = Mutex()
    private val providers = specs.associate { it.id to Native(it) }
    var global: Settings? = null
        set(value) { field = value; providers.values.forEach { it.peek()?.let { c -> if (c is GlobalSettingsProvider && value != null) c.setGlobalSettings(value) } } }
    var web: WebViewClient? = null
        set(value) { field = value; providers.values.forEach { it.peek()?.let { c -> if (c is WebViewClientProvider && value != null) c.setWebViewClient(value) } } }
    var messages: MutableSharedFlow<Message>? = null
        set(value) { field = value; providers.values.forEach { it.peek()?.let { c -> if (c is MessageFlowProvider && value != null) c.setMessageFlow(value) } } }
    var network: NetworkConnection? = null
        set(value) { field = value; providers.values.forEach { p -> p.peek()?.let { c -> if (c is NetworkConnectionProvider) c.setNetworkConnection(if (enabled(p.spec.id)) value ?: NetworkConnection.NotConnected else NetworkConnection.NotConnected) } } }
    private var authenticated = false
    val metadataId get() = root.getString("metadata_extension") ?: "spotify"
    private fun fieldKey(provider: String, form: String, field: String) = "field:$provider:$form:$field"

    private fun configure(client: ExtensionClient, settings: Settings, user: User?): ExtensionClient {
        client.setSettings(settings)
        if (client is GlobalSettingsProvider) global?.let(client::setGlobalSettings)
        if (client is WebViewClientProvider) web?.let(client::setWebViewClient)
        if (client is MessageFlowProvider) messages?.let(client::setMessageFlow)
        if (client is NetworkConnectionProvider) client.setNetworkConnection(network ?: NetworkConnection.NotConnected)
        if (client is LoginClient) client.setLoginUser(user)
        return client
    }

    inner class Native(val spec: ProviderSpec) {
        val settings = ScopedSettings(root, "provider.${spec.id}.") { authenticated }
        private var native: ExtensionClient? = null
        fun peek() = native
        @Synchronized fun client(): ExtensionClient = native ?: spec.create().also { client ->
            client.setSettings(settings)
            if (client is GlobalSettingsProvider) global?.let(client::setGlobalSettings)
            if (client is WebViewClientProvider) web?.let(client::setWebViewClient)
            if (client is MessageFlowProvider) messages?.let(client::setMessageFlow)
            if (client is NetworkConnectionProvider) client.setNetworkConnection(if (enabled(spec.id)) network ?: NetworkConnection.NotConnected else NetworkConnection.NotConnected)
            if (client is LoginClient) client.setLoginUser(if (authenticated) savedAccount() else null)
            native = client
        }
        fun savedAccount(): User? = settings.getString("account.v1")?.let { runCatching { json.decodeFromString<User>(it) }.getOrNull() }
        val extension = MusicExtension(
            Metadata("oneforall.${spec.id}", "embedded:${spec.id}", ImportType.File, ExtensionType.MUSIC,
                spec.id, spec.name, "0.1.0", "Embedded ${spec.name} provider", "TheWinner02"),
            Injectable({ client() }, listOf({ withTimeout(6_000) { onInitialize(); onExtensionSelected() } })))
    }
    fun enabled(id: String) = id == metadataId || (root.getBoolean("provider.$id.enabled") ?: true)
    fun extensions(): List<MusicExtension> {
        network = network
        return providers.values.filter { enabled(it.spec.id) }.map { it.extension }
    }
    suspend fun ready(id: String): ExtensionClient = requireNotNull(providers[id]) { "Unknown provider" }.extension.instance.value().getOrThrow()
    fun activate(active: Boolean) {
        authenticated = active
        providers.values.forEach { p ->
            p.peek()?.let { c ->
                c.setSettings(p.settings)
                if (c is LoginClient) c.setLoginUser(if (active) p.savedAccount() else null)
            }
        }
    }
    private fun save(id: String, users: List<User>): User {
        val provider = requireNotNull(providers[id])
        val user = users.firstOrNull()?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
            ?: error("$id did not return a valid account")
        provider.settings.putString("account.v1", json.encodeToString(user))
        root.putBoolean("provider.$id.enabled", true)
        provider.client()
        activate(true)
        return publicAccount() ?: error("Account was not saved")
    }
    fun publicAccount(): User? {
        if (!authenticated) return null
        val names = providers.values.mapNotNull { p -> p.savedAccount()?.let { p.spec.name } }
        return if (names.isEmpty()) null else User(ACCOUNT_ID, "One For All", subtitle = names.joinToString(" · "))
    }
    fun forms(): List<LoginClient.Form> = providers.values.flatMap { p ->
        val c = p.client()
        val browser = if (c is LoginClient.WebView) listOf(LoginClient.Form("browser:${p.spec.id}", "${p.spec.name} · Browser", LoginClient.InputField.Type.Misc, emptyList())) else emptyList()
        browser + if (c is LoginClient.CustomInput) c.forms.map { form ->
            form.copy(key = "form:${p.spec.id}:${form.key}", label = "${p.spec.name} · ${form.label}",
                inputFields = form.inputFields.map { it.copy(key = fieldKey(p.spec.id, form.key, it.key)) })
        } else emptyList()
    }
    suspend fun login(key: String, data: Map<String, String?>): List<User> = loginMutex.withLock {
        val parts = key.split(':', limit = 3)
        require(parts.size >= 2)
        val id = parts[1]
        val provider = requireNotNull(providers[id]) { "Unknown login service" }
        require(parts[0] == "form" || parts[0] == "browser") { "Unknown login method" }
        val staged = StagedSettings(provider.settings)
        // A fresh client keeps cancelled/failed attempts away from the account used for playback.
        val c = configure(provider.spec.create(), staged, null)
        val users = if (parts[0] == "form") {
            val formClient = c as? LoginClient.CustomInput ?: error("Login form unavailable")
            val form = formClient.forms.firstOrNull { it.key == parts.getOrNull(2) } ?: error("Unknown login form")
            val fields = form.inputFields.associate { it.key to data[fieldKey(id, form.key, it.key)] }
            form.inputFields.forEach { field ->
                val value = fields[field.key]
                require(!field.isRequired || !value.isNullOrBlank()) { "${field.label} is required" }
                require(value.isNullOrBlank() || field.regex?.matches(value) != false) { "Invalid ${field.label}" }
            }
            withTimeout(60_000) { formClient.onLogin(form.key, fields) }
        } else {
            val request = (c as? LoginClient.WebView)?.webViewRequest ?: error("Browser login unavailable")
            if (id == "soundcloud") {
                // Refresh the public client id when possible, without making it a login prerequisite.
                try { withTimeoutOrNull(6_000) { c.onInitialize() } }
                catch (cancel: CancellationException) { currentCoroutineContext().ensureActive() }
                catch (_: Exception) { }
            }
            var result: List<User>? = null
            val bridge = request.mapResult { found -> result = found; "completed" }
            val completed = (web ?: error("Echo WebView provider unavailable"))
                .await(true, "One For All · ${provider.spec.name}", bridge).getOrThrow()
            if (completed != "completed") throw CancellationException("Login cancelled")
            result ?: error("The service did not return an account")
        }
        require(users.firstOrNull()?.let { it.id.isNotBlank() && it.name.isNotBlank() } == true) { "The service did not return a valid account" }
        currentCoroutineContext().ensureActive()
        staged.commit()
        listOf(save(id, users))
    }
    suspend fun settings(): List<Setting> = providers.values.map { p ->
        val c = p.client()
        SettingCategory(p.spec.name, "section.${p.spec.id}", listOf(
            SettingSwitch("Enable ${p.spec.name}", "provider.${p.spec.id}.enabled", "The selected metadata service remains available.", true),
        ) + (if (c is LoginClient) listOf(
            SettingItem("Account", "account.${p.spec.id}", p.savedAccount()?.name ?: "Not connected"),
            SettingOnClick("Disconnect ${p.spec.name}", "disconnect.${p.spec.id}", "Remove this service's saved account.") {
                loginMutex.withLock {
                    p.settings.putString("account.v1", null)
                    p.settings.putString("auth_token", null)
                    p.settings.putString("userAuthToken", null)
                    c.setLoginUser(null)
                    if (providers.values.none { it.savedAccount() != null }) authenticated = false
                }
            }
        ) else emptyList()) + c.getSettingItems().filterNot { it is SettingTextInput && it.key in setOf("auth_token", "userAuthToken") }.map(p.settings::expose))
    }
    suspend fun changed(key: String?) {
        providers.values.forEach { p ->
            if (key == null || key.startsWith(p.settings.prefix)) {
                val client = p.peek()
                if (client is SettingsChangeListenerClient) client.onSettingsChanged(p.settings, key?.removePrefix(p.settings.prefix))
            }
        }
    }
}

/** Preserve the native login request; Echo supplies the browser, no credentials are logged. */
internal fun <T> WebViewRequest<T>.mapResult(map: suspend (T) -> String): WebViewRequest<String> {
    val original = this
    return when (original) {
        is WebViewRequest.Headers<T> -> object : WebViewRequest.Headers<String> {
            override val initialUrl = original.initialUrl
            override val stopUrlRegex = original.stopUrlRegex
            override val maxTimeout = maxOf(original.maxTimeout, ProviderRegistry.LOGIN_TIMEOUT_MS)
            override val dontCache = true
            override val interceptUrlRegex = original.interceptUrlRegex
            override suspend fun onStop(requests: List<NetworkRequest>) = withTimeout(30_000) { original.onStop(requests)?.let { map(it) } }
        }
        is WebViewRequest.Cookie<T> -> object : WebViewRequest.Cookie<String> {
            override val initialUrl = original.initialUrl
            override val stopUrlRegex = original.stopUrlRegex
            override val maxTimeout = maxOf(original.maxTimeout, ProviderRegistry.LOGIN_TIMEOUT_MS)
            override val dontCache = true
            override suspend fun onStop(url: NetworkRequest, cookie: String) = withTimeout(30_000) { original.onStop(url, cookie)?.let { map(it) } }
        }
        is WebViewRequest.Evaluate<T> -> object : WebViewRequest.Evaluate<String> {
            override val initialUrl = original.initialUrl
            override val stopUrlRegex = original.stopUrlRegex
            override val maxTimeout = maxOf(original.maxTimeout, ProviderRegistry.LOGIN_TIMEOUT_MS)
            override val dontCache = true
            override val javascriptToEvaluate = original.javascriptToEvaluate
            override val javascriptToEvaluateOnPageStart = original.javascriptToEvaluateOnPageStart
            override suspend fun onStop(url: NetworkRequest, data: String?) = withTimeout(30_000) { original.onStop(url, data)?.let { map(it) } }
        }
    }
}
