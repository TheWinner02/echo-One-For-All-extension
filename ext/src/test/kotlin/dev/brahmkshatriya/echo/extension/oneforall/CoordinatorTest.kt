package dev.brahmkshatriya.echo.extension.oneforall

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
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
    @Test fun disconnectingOneServicePreservesTheOtherAcrossRestart() = runBlocking {
        val root = Memory()
        val specs = listOf(ProviderSpec("a", "A", ::Fake), ProviderSpec("b", "B", ::Fake))
        val registry = ProviderRegistry(root, specs)
        registry.login("form:a:login", mapOf("field:a:login:password" to "fixture"))
        registry.login("form:b:login", mapOf("field:b:login:password" to "fixture"))
        val section = registry.settings().filterIsInstance<SettingCategory>().first { it.key == "section.a" }
        section.items.filterIsInstance<SettingOnClick>().single { it.key == "disconnect.a" }.onClick()
        assertNull(root.getString("provider.a.account.v1"))
        assertNotNull(root.getString("provider.b.account.v1"))
        assertEquals("B", registry.publicAccount()?.subtitle)
        val restored = ProviderRegistry(root, specs)
        restored.activate(true)
        assertNull((restored.ready("a") as Fake).user)
        assertNotNull((restored.ready("b") as Fake).user)
    }

    @Test fun matchedProvidersReceiveTheDownloadFlag() = runBlocking {
        val root = Memory().apply {
            putString("metadata_extension", "a")
            putString("stream_extension", "b")
        }
        val requests = mutableListOf<Pair<String, Boolean>>()
        fun provider(id: String) = object : ExtensionClient, TrackClient, SearchFeedClient {
            override fun setSettings(settings: Settings) = Unit
            override suspend fun getSettingItems() = emptyList<Setting>()
            override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
                requests += id to isDownload
                return track.copy(streamables = listOf(Streamable.server(id, 1, id)))
            }
            override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean) = media()
            override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
            override suspend fun loadSearchFeed(query: String): Feed<Shelf> =
                listOf<Shelf>(Shelf.Item(Track("matched", "Fixture", isrc = "fixture-isrc"))).toFeed()
        }
        val registry = ProviderRegistry(root, listOf(ProviderSpec("a", "A") { provider("a") }, ProviderSpec("b", "B") { provider("b") }))
        val engine = dev.brahmkshatriya.echo.oneforall.engine.CombineEngine()
        engine.setSettings(root)
        engine.setMusicExtensions(registry.extensions())
        engine.loadTrack(Track("original", "Fixture", isrc = "fixture-isrc"), true)
        assertTrue(requests.contains("a" to true))
        assertTrue(requests.contains("b" to true))
        assertFalse(requests.any { !it.second })
    }

    @Test fun tidalAudioKeepsItsNativeIdAndDownloadModeThroughTheRouter() = runBlocking {
        val selected = Streamable.server("581022787", 1411, "Tidal FLAC",
            mapOf(SourceRouter.PROVIDER to "tidal", "id" to "581022787"))
        val router = SourceRouter({ stream, download ->
            assertEquals("581022787", stream.id)
            assertEquals("581022787", stream.extras["id"])
            assertEquals("tidal", stream.extras[SourceRouter.PROVIDER])
            assertTrue(download)
            assertFalse(stream.extras.containsKey(SourceRouter.PAYLOAD))
            media()
        })
        assertTrue(router.load(router.attach(listOf(selected)).single(), true) is Streamable.Media.Server)
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
