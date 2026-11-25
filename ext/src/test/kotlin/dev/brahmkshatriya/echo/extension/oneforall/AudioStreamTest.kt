package dev.brahmkshatriya.echo.extension.oneforall

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.oneforall.providers.deezer.AudioStreamProvider
import dev.brahmkshatriya.echo.oneforall.providers.deezer.Utils
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AudioStreamTest {
    private val key = "0123456789abcdef"
    private val plain = ByteArray(2048 * 5 + 117) { ((it * 31 + it / 79) % 256).toByte() }
    private fun encoded(): ByteArray = plain.copyOf().also { bytes ->
        for (offset in bytes.indices step 2048) {
            if (offset + 2048 <= bytes.size && offset / 2048 % 3 == 0) {
                val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "Blowfish"),
                    IvParameterSpec(ByteArray(8) { it.toByte() }))
                cipher.doFinal(bytes.copyOfRange(offset, offset + 2048)).copyInto(bytes, offset)
            }
        }
    }
    private val stream get() = Streamable.server("https://audio.example.invalid/fixture", 1411, "fixture", mapOf("key" to key))
    private fun client(ignoreRange: Boolean = false, onRange: (String) -> Unit = {}): OkHttpClient {
        val bytes = encoded()
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val range = request.header("Range").orEmpty()
            onRange(range)
            val parts = range.removePrefix("bytes=").split('-')
            val start = parts.firstOrNull()?.toIntOrNull() ?: 0
            val end = parts.getOrNull(1)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
            val body = if (ignoreRange) bytes else bytes.copyOfRange(start, end + 1)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (ignoreRange) 200 else 206)
                .message("fixture").header("Content-Range", "bytes $start-$end/${bytes.size}")
                .body(body.toResponseBody()).build()
        }.build()
    }
    @Test fun arbitraryReadSizesAndOffsetsPreserveEveryAudioByte() = runBlocking {
        AudioStreamProvider.openStream(stream, client()).use { input ->
            val output = ByteArrayOutputStream()
            val sizes = intArrayOf(1, 7, 33, 511, 2047, 2048, 9000)
            var index = 0
            while (true) {
                val target = ByteArray(sizes[index++ % sizes.size] + 6)
                val read = input.read(target, 3, target.size - 6)
                if (read < 0) break
                assertTrue(read > 0)
                output.write(target, 3, read)
            }
            assertArrayEquals(plain, output.toByteArray())
            assertEquals(0, input.read(byteArrayOf(), 0, 0))
        }
    }
    @Test fun singleByteReadsDecodeTheSameStream() = runBlocking {
        AudioStreamProvider.openStream(stream, client()).use { input ->
            for (byte in plain) assertEquals(byte.toInt() and 255, input.read())
            assertEquals(-1, input.read())
        }
    }
    @Test fun seekingInsideAndAcrossBlocksPreservesAlignment() = runBlocking {
        for (start in listOf(1, 2037, 2048, 3073, 6144, 6201, plain.lastIndex)) {
            AudioStreamProvider.openStream(stream, client(), start.toLong()).use { input ->
                assertArrayEquals("start=$start", plain.copyOfRange(start, plain.size), input.readBytes())
            }
        }
    }
    @Test fun shortRangesFetchWholeBlocksAndExposeOnlyTheRequestedBytes() = runBlocking {
        var requestedRange = ""
        AudioStreamProvider.openStream(stream, client(onRange = { requestedRange = it }), 100, 172).use { input ->
            assertArrayEquals(plain.copyOfRange(100, 173), input.readBytes())
        }
        assertEquals("bytes=0-2047", requestedRange)
    }
    @Test fun serversIgnoringRangeStillSeekToTheCorrectAudioPosition() = runBlocking {
        AudioStreamProvider.openStream(stream, client(ignoreRange = true), 3073, 6400).use { input ->
            assertArrayEquals(plain.copyOfRange(3073, 6401), input.readBytes())
        }
    }
    @Test fun closingTwiceDoesNotReturnTheSameBufferToTwoStreams() = runBlocking {
        val network = client()
        val previous = AudioStreamProvider.openStream(stream, network)
        previous.close(); previous.close()
        AudioStreamProvider.openStream(stream, network).use { first ->
            AudioStreamProvider.openStream(stream, network, 100).use { second ->
                assertArrayEquals(plain, first.readBytes())
                assertArrayEquals(plain.copyOfRange(100, plain.size), second.readBytes())
            }
        }
    }
    @Test fun missingOrUnsupportedHeadLengthIsUnknownRatherThanZero() = runBlocking {
        for (status in listOf(200, 405, 501)) {
            val network = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                    .message("fixture").body(byteArrayOf().toResponseBody()).build()
            }.build()
            assertEquals(-1L, Utils.getContentLength("https://audio.example.invalid/fixture", network))
        }
    }
    @Test fun unavailableHeadRequestFailsBeforePublishingTheStream() = runBlocking {
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403)
                .message("fixture").body(byteArrayOf().toResponseBody()).build()
        }.build()
        assertTrue(runCatching { Utils.getContentLength("https://audio.example.invalid/fixture", network) }.isFailure)
    }
    @Test fun invalidResolvedSourcesContinueToAnotherProvider() = runBlocking {
        val streams = listOf("a", "b").map { Streamable.server(it, 1, it, mapOf(SourceRouter.PROVIDER to it)) }
        for (invalid in listOf(
            Streamable.Source.Http(dev.brahmkshatriya.echo.common.models.NetworkRequest("")),
            Streamable.Source.Raw(id = "restored-without-provider")
        )) {
            val calls = mutableListOf<String>()
            val router = SourceRouter({ candidate, _ ->
                calls += candidate.id
                Streamable.Media.Server(listOf(if (candidate.id == "a") invalid else
                    Streamable.Source.Http(dev.brahmkshatriya.echo.common.models.NetworkRequest("https://audio.example.invalid/good"))), false)
            })
            router.load(router.attach(streams).first(), false)
            assertEquals(listOf("a", "b"), calls)
        }
    }
}
