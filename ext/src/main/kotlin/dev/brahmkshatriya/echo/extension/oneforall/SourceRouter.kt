package dev.brahmkshatriya.echo.extension.oneforall

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Resolver failures are local to one service; cancellation always belongs to the caller. */
internal class SourceRouter(
    private val resolve: suspend (Streamable, Boolean) -> Streamable.Media,
    private val timeoutMs: Long = 6_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object {
        const val PAYLOAD = "oneforall.fallback.v1"
        const val PROVIDER = "stream_ext_id"
        private const val MAX_BYTES = 64 * 1024
        private const val MAX_CANDIDATES = 24
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val unavailableUntil = ConcurrentHashMap<String, Long>()
    fun reset() = unavailableUntil.clear()

    fun attach(streams: List<Streamable>): List<Streamable> {
        val candidates = streams.filter { it.type == Streamable.MediaType.Server }
            .map { it.copy(extras = it.extras - PAYLOAD) }
            .distinctBy { Triple(it.extras[PROVIDER], it.id, it.quality) }
            .take(MAX_CANDIDATES)
        val payload = json.encodeToString(candidates)
        // Never grow queue metadata without a strict bound. Native servers remain usable.
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return streams
        return streams.map { if (it.type == Streamable.MediaType.Server)
            it.copy(extras = it.extras + (PAYLOAD to payload)) else it }
    }

    suspend fun load(selected: Streamable, download: Boolean): Streamable.Media {
        if (selected.type != Streamable.MediaType.Server) return resolve(selected, download)
        val native = selected.copy(extras = selected.extras - PAYLOAD)
        val encoded = selected.extras[PAYLOAD]
        val alternatives = if (encoded != null && encoded.toByteArray().size <= MAX_BYTES)
            runCatching { json.decodeFromString<List<Streamable>>(encoded).take(MAX_CANDIDATES) }.getOrDefault(emptyList())
            else emptyList()
        val all = (listOf(native) + alternatives).filter { it.type == Streamable.MediaType.Server }
            .distinctBy { Triple(it.extras[PROVIDER], it.id, it.quality) }
        val groups = all.groupBy { it.extras[PROVIDER].orEmpty() }
        val failures = mutableListOf<String>()
        for ((provider, choices) in groups) {
            currentCoroutineContext().ensureActive()
            // The chosen provider is always retried. Cooling alternatives avoid repeated slow outages.
            if (provider != native.extras[PROVIDER] && (unavailableUntil[provider] ?: 0) > now()) continue
            var resolved: Streamable.Media.Server? = null
            val finished = withTimeoutOrNull(timeoutMs) {
                for (candidate in choices) {
                    try {
                        val result = resolve(candidate.copy(extras = candidate.extras - PAYLOAD), download)
                        if (result is Streamable.Media.Server && result.sources.isNotEmpty()) {
                            resolved = result
                            break
                        }
                    } catch (cancel: CancellationException) { currentCoroutineContext().ensureActive() }
                    catch (_: Exception) { /* Try another format before changing service. */ }
                }
                true
            }
            resolved?.let { server ->
                unavailableUntil.remove(provider)
                return server.copy(sources = server.sources.map { source ->
                    val title = listOf(provider, source.title).filterNot { it.isNullOrBlank() }.joinToString(" · ")
                    when (source) {
                        is Streamable.Source.Http -> source.copy(title = title)
                        is Streamable.Source.Raw -> source.copy(title = title)
                    }
                })
            }
            // A missing recording is not a service outage. Only timeout failures cool the service.
            if (finished == null) unavailableUntil[provider] = now() + 30_000
            failures += provider.ifBlank { "metadata" } + if (finished == null) " (timeout)" else " (unavailable)"
        }
        error("No playable source. Tried: ${failures.joinToString()}")
    }
}
