package dev.brahmkshatriya.echo.oneforall.providers.deezer

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque

object AudioStreamProvider {
    private const val BLOCK_SIZE = 2048
    private val buffers = ArrayDeque<ByteArray>()
    @Synchronized private fun acquire() = buffers.pollFirst() ?: ByteArray(BLOCK_SIZE)
    @Synchronized private fun release(buffer: ByteArray) { if (buffers.size < 10) buffers.addLast(buffer) }

    suspend fun openStream(streamable: Streamable, client: OkHttpClient, startByte: Long = 0,
                           endByte: Long = -1): InputStream = withContext(Dispatchers.IO) {
        require(startByte >= 0 && (endByte == -1L || endByte >= startByte)) { "Invalid audio range" }
        val alignedStart = startByte - startByte % BLOCK_SIZE
        // Read the whole final block before decoding, even for a short requested range.
        val alignedEnd = if (endByte < 0 || endByte > Long.MAX_VALUE - BLOCK_SIZE) -1L
            else endByte + (BLOCK_SIZE - 1 - endByte % BLOCK_SIZE)
        val range = "bytes=$alignedStart-" + if (alignedEnd < 0) "" else alignedEnd.toString()
        val response = client.newCall(Request.Builder().url(streamable.id)
            .header("Range", range).header("Cache-Control", "no-store").build()).await()
        try {
            if (!response.isSuccessful) throw IOException("Audio request failed (HTTP ${response.code})")
            val input = response.body.byteStream()
            if (response.code == 206) {
                val declaredStart = response.header("Content-Range")?.substringAfter("bytes ")
                    ?.substringBefore('-')?.toLongOrNull()
                if (declaredStart != null && declaredStart != alignedStart) throw IOException("Unexpected audio range")
            } else if (alignedStart > 0) {
                // A server may ignore Range and return the complete file.
                var remaining = alignedStart
                while (remaining > 0) {
                    val skipped = input.skip(remaining)
                    if (skipped > 0) remaining -= skipped
                    else if (input.read() < 0) throw EOFException("Audio seek exceeds stream length")
                    else remaining--
                }
            }
            val key = streamable.extras["key"] ?: ""
            BufferedInputStream(object : InputStream() {
                private val buffer = acquire()
                private var block = alignedStart / BLOCK_SIZE
                private var drop = (startByte % BLOCK_SIZE).toInt()
                private var offset = 0
                private var count = 0
                private var remaining = if (endByte < 0) -1L else endByte - startByte + 1
                private var closed = false

                private fun fill(): Boolean {
                    count = 0
                    while (count < BLOCK_SIZE) {
                        val n = input.read(buffer, count, BLOCK_SIZE - count)
                        if (n < 0) break
                        if (n == 0) {
                            val value = input.read()
                            if (value < 0) break
                            buffer[count++] = value.toByte()
                        } else count += n
                    }
                    if (count == 0) return false
                    if (count == BLOCK_SIZE && block % 3 == 0L) {
                        Utils.decryptBlowfish(buffer, key).copyInto(buffer)
                    }
                    block++
                    offset = minOf(drop, count)
                    drop = 0
                    return offset < count
                }

                override fun read(): Int {
                    checkOpen()
                    if (remaining == 0L || (offset >= count && !fill())) return -1
                    if (remaining > 0) remaining--
                    return buffer[offset++].toInt() and 255
                }

                override fun read(target: ByteArray, start: Int, length: Int): Int {
                    if (start < 0 || length < 0 || start > target.size - length) throw IndexOutOfBoundsException()
                    checkOpen()
                    if (length == 0) return 0
                    if (remaining == 0L || (offset >= count && !fill())) return -1
                    val size = minOf(length.toLong(), (count - offset).toLong(),
                        if (remaining < 0) Long.MAX_VALUE else remaining).toInt()
                    buffer.copyInto(target, start, offset, offset + size)
                    offset += size
                    if (remaining > 0) remaining -= size
                    return size
                }

                override fun available() = minOf((count - offset).toLong(),
                    if (remaining < 0) Long.MAX_VALUE else remaining).toInt()

                private fun checkOpen() { if (closed) throw IOException("Audio stream is closed") }
                override fun close() {
                    if (closed) return
                    closed = true
                    try { response.close() } finally { release(buffer) }
                }
            }, BLOCK_SIZE * 4)
        } catch (failure: Throwable) {
            response.close()
            throw failure
        }
    }
}
