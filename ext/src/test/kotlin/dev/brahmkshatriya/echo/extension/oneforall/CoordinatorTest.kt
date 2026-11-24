package dev.brahmkshatriya.echo.extension.oneforall

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.settings.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CoordinatorTest {
    private class Memory : Settings {
        private val values = mutableMapOf<String, Any>()
        private fun write(key: String, value: Any?) { if (value == null) values.remove(key) else values[key] = value }
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String?) = write(key, value)
        override fun getBoolean(key: String) = values[key] as? Boolean
        override fun putBoolean(key: String, value: Boolean?) = write(key, value)
        override fun getInt(key: String) = values[key] as? Int
        override fun putInt(key: String, value: Int?) = write(key, value)
        @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String) = values[key] as? Set<String>
        override fun putStringSet(key: String, value: Set<String>?) = write(key, value)
    }
    private class Fake : ExtensionClient, LoginClient.CustomInput {
        lateinit var nativeSettings: Settings
        var initializations = 0
        var user: User? = null
        override fun setSettings(settings: Settings) { this.nativeSettings = settings }
        override suspend fun getSettingItems() = listOf(SettingSwitch("A", "same", defaultValue = true))
        override suspend fun onInitialize() { initializations++ }
        override val forms = listOf(LoginClient.Form("login", "Login", LoginClient.InputField.Type.Password,
            listOf(LoginClient.InputField(LoginClient.InputField.Type.Password, "password", "Password", true))))
        override suspend fun onLogin(key: String, data: Map<String, String?>) = listOf(User("native", "Native", extras = mapOf("token" to "fixture-only")))
        override fun setLoginUser(user: User?) { this.user = user }
        override suspend fun getCurrentUser() = user
    }
    private fun stream(provider: String, id: String = provider) = Streamable.server(id, 1, provider, mapOf(SourceRouter.PROVIDER to provider))
    private fun media() = Streamable.Media.Server(listOf(Streamable.Source.Http(NetworkRequest("https://example.invalid/audio"))), false)

    @Test fun serviceKeysAndNestedSettingsAreIsolated() {
        val root = Memory(); val a = ScopedSettings(root, "a."); val b = ScopedSettings(root, "b.")
        a.putString("token", "one"); b.putString("token", "two")
        assertEquals("one", a.getString("token")); assertEquals("two", b.getString("token"))
        val nested = a.expose(SettingCategory("A", "category", listOf(SettingSwitch("Same", "same", defaultValue = true)))) as SettingCategory
        assertEquals("a.same", nested.items.single().key)
    }
    @Test fun inactiveAccountsCannotReadTokens() {
        val root = Memory(); var active = true; val scoped = ScopedSettings(root, "a.") { active }
        scoped.putString("auth_token", "fixture"); scoped.putString("account.v1", "saved")
        active = false
        assertNull(scoped.getString("auth_token")); assertEquals("saved", scoped.getString("account.v1"))
        active = true; assertEquals("fixture", scoped.getString("auth_token"))
    }
    @Test fun providersInitializeLazilyAndOnlyOnce() = runBlocking {
        val root = Memory(); root.putString("metadata_extension", "a")
        val fake = Fake(); var creations = 0
        val registry = ProviderRegistry(root, listOf(ProviderSpec("a", "A") { creations++; fake }))
        assertEquals(0, creations)
        assertEquals("form:a:login", registry.forms().single().key)
        assertEquals(0, fake.initializations)
        registry.ready("a"); registry.ready("a")
        assertEquals(1, creations); assertEquals(1, fake.initializations)
    }
    @Test fun loginReturnsAnAccountWithoutServiceTokensAndRestoresSessions() = runBlocking {
        val root = Memory(); root.putString("metadata_extension", "a"); val fake = Fake()
        val registry = ProviderRegistry(root, listOf(ProviderSpec("a", "A") { fake }))
        val account = registry.login("form:a:login", mapOf("field:a:login:password" to "fixture")).single()
        assertTrue(account.extras.isEmpty()); assertEquals("one_for_all", account.id)
        assertNotNull(fake.user)
        registry.activate(false); assertNull(fake.user); assertNull(registry.publicAccount())
        registry.activate(true); assertEquals("native", fake.user?.id)
    }
    @Test fun savedAccountSurvivesRegistryRecreation() = runBlocking {
        val root = Memory(); root.putString("metadata_extension", "a")
        ProviderRegistry(root, listOf(ProviderSpec("a", "A", ::Fake))).login("form:a:login", mapOf("field:a:login:password" to "fixture"))
        val next = Fake(); val registry = ProviderRegistry(root, listOf(ProviderSpec("a", "A") { next }))
        registry.activate(true); registry.ready("a")
        assertEquals("native", next.user?.id)
    }
    @Test fun missingRequiredLoginFieldsAreRejected() = runBlocking {
        val registry = ProviderRegistry(Memory(), listOf(ProviderSpec("a", "A", ::Fake)))
        assertTrue(runCatching { registry.login("form:a:login", emptyMap()) }.isFailure)
    }
    @Test fun resolverFailureFallsThroughToAnotherService() = runBlocking {
        val calls = mutableListOf<String>()
        val router = SourceRouter({ s, _ -> calls += s.id; if (s.id == "a") error("unavailable") else media() })
        val result = router.load(router.attach(listOf(stream("a"), stream("b"))).first(), false) as Streamable.Media.Server
        assertEquals(listOf("a", "b"), calls); assertTrue(result.sources.single().title!!.startsWith("b"))
    }
    @Test fun alternativeFormatsAreTriedBeforeAnotherService() = runBlocking {
        val calls = mutableListOf<String>()
        val router = SourceRouter({ s, _ -> calls += s.id; if (s.id == "a1") error("format") else media() })
        router.load(router.attach(listOf(stream("a", "a1"), stream("a", "a2"), stream("b"))).first(), false)
        assertEquals(listOf("a1", "a2"), calls)
    }
    @Test fun emptyServerDoesNotStopFallback() = runBlocking {
        val router = SourceRouter({ s, _ -> if (s.id == "a") Streamable.Media.Server(emptyList(), false) else media() })
        val result = router.load(router.attach(listOf(stream("a"), stream("b"))).first(), false) as Streamable.Media.Server
        assertTrue(result.sources.isNotEmpty())
    }
    @Test fun timeoutContinuesToAnotherService() = runBlocking {
        val calls = mutableListOf<String>()
        val router = SourceRouter({ s, _ -> calls += s.id; if (s.id == "a") delay(5_000); media() }, timeoutMs = 30)
        router.load(router.attach(listOf(stream("a"), stream("b"))).first(), false)
        assertEquals(listOf("a", "b"), calls)
    }
    @Test fun enclosingCancellationIsNeverTurnedIntoFallback() = runBlocking {
        val calls = mutableListOf<String>()
        val router = SourceRouter({ s, _ -> calls += s.id; delay(5_000); media() })
        assertNull(withTimeoutOrNull(30) { router.load(router.attach(listOf(stream("a"), stream("b"))).first(), false) })
        assertEquals(listOf("a"), calls)
    }
    @Test fun persistedPayloadWorksAfterRouterRecreation() = runBlocking {
        val original = SourceRouter({ _, _ -> media() }).attach(listOf(stream("a"), stream("b"))).first()
        val fresh = SourceRouter({ s, _ -> if (s.id == "a") error("missing") else media() })
        assertTrue(fresh.load(original.copy(extras = original.extras.toMap()), false) is Streamable.Media.Server)
    }
    @Test fun noPayloadMeansExactServiceOnly() = runBlocking {
        val calls = mutableListOf<String>(); val router = SourceRouter({ s, _ -> calls += s.id; error("missing") })
        assertTrue(runCatching { router.load(stream("a"), false) }.isFailure)
        assertEquals(listOf("a"), calls)
    }
    @Test fun allServiceFailuresAreReportedAfterAllAlternatives() = runBlocking {
        val calls = mutableListOf<String>(); val router = SourceRouter({ s, _ -> calls += s.id; error("fixture") })
        val failure = runCatching { router.load(router.attach(listOf(stream("a"), stream("b"))).first(), false) }.exceptionOrNull()
        assertEquals(listOf("a", "b"), calls); assertTrue(failure!!.message!!.contains("b"))
    }
}
