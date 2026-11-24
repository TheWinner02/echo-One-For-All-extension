package dev.brahmkshatriya.echo.extension.oneforall

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.*
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.settings.*
import dev.brahmkshatriya.echo.oneforall.providers.deezer.DeezerExtension
import dev.brahmkshatriya.echo.oneforall.providers.qobuz.QobuzExtension
import dev.brahmkshatriya.echo.oneforall.providers.spotify.SpotifyExtension
import dev.brahmkshatriya.echo.oneforall.providers.soundcloud.SoundCloudExtension
import dev.brahmkshatriya.echo.oneforall.providers.youtube_music.YoutubeExtension
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LoginTest {
    private class Memory : Settings {
        private val data = mutableMapOf<String, Any>()
        private fun write(key: String, value: Any?) { if (value == null) data.remove(key) else data[key] = value }
        override fun getString(key: String) = data[key] as? String
        override fun getBoolean(key: String) = data[key] as? Boolean
        override fun getInt(key: String) = data[key] as? Int
        @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String) = data[key] as? Set<String>
        override fun putString(key: String, value: String?) = write(key, value)
        override fun putBoolean(key: String, value: Boolean?) = write(key, value)
        override fun putInt(key: String, value: Int?) = write(key, value)
        override fun putStringSet(key: String, value: Set<String>?) = write(key, value)
    }
    private val user get() = User("fixture-user", "Fixture", extras = mapOf("auth_token" to "fixture-token"))
    private class FormClient(private val login: suspend (Settings, String, Map<String, String?>) -> List<User>) : ExtensionClient, LoginClient.CustomInput {
        private lateinit var prefs: Settings
        var current: User? = null
        override fun setSettings(settings: Settings) { prefs = settings }
        override suspend fun getSettingItems() = listOf(SettingSwitch("Example", "example", defaultValue = true), SettingTextInput("Token", "auth_token"))
        override val forms = listOf(
            LoginClient.Form("credentials", "Credentials", LoginClient.InputField.Type.Email, listOf(
                LoginClient.InputField(LoginClient.InputField.Type.Email, "email", "Email", true, Regex(".+@.+")),
                LoginClient.InputField(LoginClient.InputField.Type.Password, "password", "Password", true))),
            LoginClient.Form("token", "Token", LoginClient.InputField.Type.Password, listOf(
                LoginClient.InputField(LoginClient.InputField.Type.Password, "token", "Token", true))))
        override suspend fun onLogin(key: String, data: Map<String, String?>) = login(prefs, key, data)
        override fun setLoginUser(user: User?) { current = user }
        override suspend fun getCurrentUser() = current
    }
    private class BrowserClient(override val webViewRequest: WebViewRequest<List<User>>) : ExtensionClient, LoginClient.WebView {
        override fun setSettings(settings: Settings) = Unit
        override suspend fun getSettingItems() = emptyList<Setting>()
        override fun setLoginUser(user: User?) = Unit
        override suspend fun getCurrentUser(): User? = null
    }
    private fun cookieRequest(result: List<User>? = listOf(user)) = object : WebViewRequest.Cookie<List<User>> {
        override val initialUrl = NetworkRequest("https://example.invalid/login")
        override val stopUrlRegex = Regex("^https://example.invalid/done$")
        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User>? {
            assertEquals("fixture-cookie", cookie); return result
        }
    }
    private fun web(block: suspend (WebViewRequest<String>) -> String?) = object : WebViewClient {
        override suspend fun await(showWebView: Boolean, reason: String, request: WebViewRequest<String>): Result<String?> {
            assertTrue(showWebView)
            return Result.success(block(request))
        }
    }
    private suspend fun complete(request: WebViewRequest<String>) =
        (request as WebViewRequest.Cookie<String>).onStop(NetworkRequest("https://example.invalid/done"), "fixture-cookie")

    @Test fun formFieldsCannotLeakIntoAnotherLoginMethod() = runBlocking {
        var submitted = emptyMap<String, String?>()
        val registry = ProviderRegistry(Memory(), listOf(ProviderSpec("a", "A") { FormClient { _, _, data -> submitted = data; listOf(user) } }))
        registry.login("form:a:token", mapOf("field:a:token:token" to "new", "field:a:token:email" to "old@example.invalid", "field:a:token:password" to "old"))
        assertEquals(mapOf("token" to "new"), submitted)
    }
    @Test fun invalidRequiredFieldsAndRegexAreRejectedBeforeSending() = runBlocking {
        var calls = 0
        val registry = ProviderRegistry(Memory(), listOf(ProviderSpec("a", "A") { FormClient { _, _, _ -> calls++; listOf(user) } }))
        assertTrue(runCatching { registry.login("form:a:credentials", mapOf("field:a:credentials:email" to "bad", "field:a:credentials:password" to "ok")) }.isFailure)
        assertTrue(runCatching { registry.login("form:a:token", mapOf("field:a:token:token" to "  ")) }.isFailure)
        assertEquals(0, calls)
    }
    @Test fun failingLoginCannotReplaceExistingCredentials() = runBlocking {
        val prefs = Memory()
        val registry = ProviderRegistry(prefs, listOf(ProviderSpec("a", "A") { FormClient { settings, _, fields ->
            settings.putString("auth_token", fields["token"])
            if (fields["token"] == "bad") error("rejected")
            listOf(user)
        } }))
        registry.login("form:a:token", mapOf("field:a:token:token" to "good"))
        val account = prefs.getString("provider.a.account.v1")
        assertTrue(runCatching { registry.login("form:a:token", mapOf("field:a:token:token" to "bad")) }.isFailure)
        assertEquals("good", prefs.getString("provider.a.auth_token"))
        assertEquals(account, prefs.getString("provider.a.account.v1"))
        assertNotNull(registry.publicAccount())
    }
    @Test fun cancelledLoginDiscardsStagedCredentials() = runBlocking {
        val prefs = Memory()
        val registry = ProviderRegistry(prefs, listOf(ProviderSpec("a", "A") { FormClient { settings, _, _ ->
            settings.putString("auth_token", "temporary"); throw CancellationException("cancelled")
        } }))
        assertTrue(runCatching { registry.login("form:a:token", mapOf("field:a:token:token" to "fixture")) }.exceptionOrNull() is CancellationException)
        assertNull(prefs.getString("provider.a.auth_token")); assertNull(registry.publicAccount())
    }
    @Test fun emptyOrInvalidUsersAreNotPersisted() = runBlocking {
        for (users in listOf(emptyList(), listOf(User("", "Invalid")))) {
            val prefs = Memory()
            val registry = ProviderRegistry(prefs, listOf(ProviderSpec("a", "A") { FormClient { settings, _, _ -> settings.putString("auth_token", "temporary"); users } }))
            assertTrue(runCatching { registry.login("form:a:token", mapOf("field:a:token:token" to "fixture")) }.isFailure)
            assertNull(prefs.getString("provider.a.auth_token")); assertNull(prefs.getString("provider.a.account.v1"))
        }
    }
    @Test fun successfulLoginEnablesServiceAndPreservesOtherAccounts() = runBlocking {
        val prefs = Memory()
        val registry = ProviderRegistry(prefs, listOf("a", "b").map { id -> ProviderSpec(id, id.uppercase()) { FormClient { _, _, _ -> listOf(user.copy(id = id)) } } })
        registry.login("form:a:token", mapOf("field:a:token:token" to "fixture")); registry.login("form:b:token", mapOf("field:b:token:token" to "fixture"))
        assertEquals(true, prefs.getBoolean("provider.b.enabled")); assertEquals(2, registry.extensions().size)
        assertTrue(registry.publicAccount()!!.subtitle!!.contains("A")); assertTrue(registry.publicAccount()!!.subtitle!!.contains("B"))
    }
    @Test fun browserLoginHasEnoughTimeAndPersistsReturnedAccount() = runBlocking {
        val prefs = Memory()
        val registry = ProviderRegistry(prefs, listOf(ProviderSpec("a", "A") { BrowserClient(cookieRequest()) }))
        registry.web = web { request ->
            assertTrue(request.maxTimeout >= 600_000); assertTrue(request.dontCache)
            assertEquals("https://example.invalid/login", request.initialUrl.url)
            complete(request)
        }
        assertEquals("one_for_all", registry.login("browser:a", emptyMap()).single().id)
        assertNotNull(prefs.getString("provider.a.account.v1"))
    }
    @Test fun browserDismissalDoesNotCreateAccount() = runBlocking {
        val prefs = Memory()
        val registry = ProviderRegistry(prefs, listOf(ProviderSpec("a", "A") { BrowserClient(cookieRequest()) }))
        registry.web = web { null }
        assertTrue(runCatching { registry.login("browser:a", emptyMap()) }.exceptionOrNull() is CancellationException)
        assertNull(prefs.getString("provider.a.account.v1"))
    }
    @Test fun browserCompletionWithoutCallbackIsRejected() = runBlocking {
        val registry = ProviderRegistry(Memory(), listOf(ProviderSpec("a", "A") { BrowserClient(cookieRequest()) }))
        registry.web = web { "completed" }
        assertTrue(runCatching { registry.login("browser:a", emptyMap()) }.isFailure)
        assertNull(registry.publicAccount())
    }
    @Test fun browserErrorPropagatesWithoutSaving() = runBlocking {
        val registry = ProviderRegistry(Memory(), listOf(ProviderSpec("a", "A") { BrowserClient(cookieRequest()) }))
        registry.web = object : WebViewClient {
            override suspend fun await(showWebView: Boolean, reason: String, request: WebViewRequest<String>) = Result.failure<String?>(IllegalStateException("browser unavailable"))
        }
        assertTrue(runCatching { registry.login("browser:a", emptyMap()) }.isFailure); assertNull(registry.publicAccount())
    }
    @Test fun headerBridgePreservesCapturedRequestsAndInterceptionPattern() = runBlocking {
        val request = object : WebViewRequest.Headers<List<User>> {
            override val initialUrl = NetworkRequest("https://example.invalid/login")
            override val stopUrlRegex = Regex("done")
            override val interceptUrlRegex = Regex("captured")
            override suspend fun onStop(requests: List<NetworkRequest>): List<User> { assertEquals("fixture", requests.single().headers["Cookie"]); return listOf(user) }
        }
        val mapped = request.mapResult { it.single().id } as WebViewRequest.Headers<String>
        assertEquals("captured", mapped.interceptUrlRegex.pattern)
        assertEquals(user.id, mapped.onStop(listOf(NetworkRequest("captured", mapOf("Cookie" to "fixture")))))
        assertTrue(mapped.maxTimeout >= 600_000)
    }
    @Test fun javascriptBridgePreservesScriptAndCallback() = runBlocking {
        val request = object : WebViewRequest.Evaluate<List<User>> {
            override val initialUrl = NetworkRequest("https://example.invalid/login")
            override val stopUrlRegex = Regex("done")
            override val javascriptToEvaluate = "function() { return 'fixture'; }"
            override val javascriptToEvaluateOnPageStart = "function() { return true; }"
            override suspend fun onStop(url: NetworkRequest, data: String?): List<User> { assertEquals("fixture", data); return listOf(user) }
        }
        val mapped = request.mapResult { it.single().id } as WebViewRequest.Evaluate<String>
        assertEquals(request.javascriptToEvaluate, mapped.javascriptToEvaluate)
        assertEquals(request.javascriptToEvaluateOnPageStart, mapped.javascriptToEvaluateOnPageStart)
        assertEquals(user.id, mapped.onStop(NetworkRequest("done"), "fixture"))
    }
    @Test fun disconnectRemovesOnlyThatServiceAndKeepsItsSettings() = runBlocking {
        val prefs = Memory()
        val registry = ProviderRegistry(prefs, listOf("a", "b").map { id -> ProviderSpec(id, id) { FormClient { settings, _, _ -> settings.putString("auth_token", id); listOf(user.copy(id = id)) } } })
        registry.login("form:a:token", mapOf("field:a:token:token" to "fixture")); registry.login("form:b:token", mapOf("field:b:token:token" to "fixture"))
        val category = registry.settings().first() as SettingCategory
        assertTrue(category.items.any { it.key == "provider.a.example" })
        assertFalse(category.items.any { it.key == "provider.a.auth_token" })
        (category.items.first { it.key == "disconnect.a" } as SettingOnClick).onClick()
        assertNull(prefs.getString("provider.a.account.v1")); assertNull(prefs.getString("provider.a.auth_token"))
        assertNotNull(prefs.getString("provider.b.account.v1"))
    }
    @Test fun allNativeAccountMethodsAreExposed() {
        val registry = ProviderRegistry(Memory())
        val keys = registry.forms().map { it.key }.toSet()
        assertEquals(setOf("browser:spotify", "form:spotify:cookie", "browser:deezer", "form:deezer:userPass", "form:deezer:manual", "browser:youtube_music", "browser:soundcloud", "form:qobuz:credentials", "form:qobuz:token"), keys)
    }
    @Test fun inputFieldsHaveUniqueKeysAcrossServicesAndForms() {
        val registry = ProviderRegistry(Memory(), listOf("a", "b").map { id -> ProviderSpec(id, id) { FormClient { _, _, _ -> listOf(user) } } })
        val keys = registry.forms().flatMap { it.inputFields }.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("field:") })
    }
    @Test fun spotifyLoginOptsIntoTheHostBrowserAndPreservesItsUserAgent() {
        val request = SpotifyExtension().webViewRequest.initialUrl
        assertEquals("spotify-login-v1", request.headers["X-Echo-WebView-Profile"])
        assertTrue(request.url.contains("continue=https%3A%2F%2Fopen.spotify.com%2F"))
        assertEquals("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36", request.headers["User-Agent"])
    }
    @Test fun nativeBrowserPatternsMatchOnlyTheirOwnOrigins() {
        val examples = listOf(
            SpotifyExtension().webViewRequest to "https://open.spotify.com/",
            DeezerExtension().webViewRequest to "https://www.deezer.com/account/",
            SoundCloudExtension().webViewRequest to "https://soundcloud.com/feed")
        examples.forEach { (request, url) ->
            assertTrue(request.stopUrlRegex.containsMatchIn(url))
            assertFalse(request.stopUrlRegex.containsMatchIn("https://example.invalid/?redirect=$url"))
            assertFalse(request.stopUrlRegex.containsMatchIn("https://example.invalid/feed"))
        }
    }
    @Test fun deezerArlIsRequiredAndMasked() {
        val field = DeezerExtension().forms.first { it.key == "manual" }.inputFields.single()
        assertTrue(field.isRequired); assertEquals(LoginClient.InputField.Type.Password, field.type)
    }
    @Test fun spotifyCookieIsRequiredAndMasked() {
        val field = SpotifyExtension().forms.first { it.key == "cookie" }.inputFields.single()
        assertTrue(field.isRequired); assertEquals(LoginClient.InputField.Type.Password, field.type)
    }
    private fun qobuzClient(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    @Test fun qobuzRejectedTokenDoesNotReplaceWorkingToken() = runBlocking {
        val prefs = Memory(); prefs.putString("userAuthToken", "old")
        val c = QobuzExtension(qobuzClient { 401 to "{}" }); c.setSettings(prefs)
        assertTrue(runCatching { c.onLogin("token", mapOf("userAuthToken" to "invalid")) }.isFailure)
        assertEquals("old", prefs.getString("userAuthToken"))
    }
    @Test fun qobuzTokenMustReturnAnIdentifiableAccount() = runBlocking {
        val prefs = Memory(); val c = QobuzExtension(qobuzClient { 200 to "{}" }); c.setSettings(prefs)
        assertTrue(runCatching { c.onLogin("token", mapOf("userAuthToken" to "invalid")) }.isFailure)
        assertNull(prefs.getString("userAuthToken"))
    }
    @Test fun qobuzValidTokenIsCheckedBeforeSaving() = runBlocking {
        val prefs = Memory(); val c = QobuzExtension(qobuzClient { request ->
            assertEquals("fixture-token", request.header("X-User-Auth-Token")); assertNull(prefs.getString("userAuthToken"))
            200 to """{"id":42,"display_name":"Fixture"}"""
        }); c.setSettings(prefs)
        assertEquals("qobuz:42", c.onLogin("token", mapOf("userAuthToken" to "fixture-token")).single().id)
        assertEquals("fixture-token", prefs.getString("userAuthToken"))
    }
    @Test fun qobuzCredentialsFlowValidatesTheReturnedToken() = runBlocking {
        val paths = mutableListOf<String>()
        val c = QobuzExtension(qobuzClient { request ->
            paths += request.url.encodedPath.substringAfterLast('/')
            when (paths.last()) {
                "login" -> 200 to """{"user_auth_token":"fixture-token","user":{"id":42}}"""
                "get" -> 200 to """{"id":42,"display_name":"Fixture"}"""
                else -> error("Unexpected request")
            }
        }); c.setSettings(Memory())
        assertEquals("qobuz:42", c.onLogin("credentials", mapOf("email" to "fixture@example.invalid", "password" to "fixture")).single().id)
        assertEquals(listOf("login", "get"), paths)
    }
    @Test fun youtubeRestorationRegeneratesAuthenticationHeader() {
        val c = YoutubeExtension()
        try {
            c.setLoginUser(User("fixture", "Fixture", extras = mapOf("cookie" to "SAPISID=fixture", "auth" to "expired")))
            val authorization = c.api.user_auth_state?.headers?.get("authorization")
            assertTrue(authorization!!.startsWith("SAPISIDHASH ")); assertNotEquals("expired", authorization)
        } finally { c.api.client.close() }
    }
}
