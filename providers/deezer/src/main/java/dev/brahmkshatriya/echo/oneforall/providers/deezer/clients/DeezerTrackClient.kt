package dev.brahmkshatriya.echo.oneforall.providers.deezer.clients

import dev.brahmkshatriya.echo.oneforall.providers.deezer.ExtensionText

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.oneforall.providers.deezer.AudioStreamProvider
import dev.brahmkshatriya.echo.oneforall.providers.deezer.DeezerApi
import dev.brahmkshatriya.echo.oneforall.providers.deezer.DeezerExtension
import dev.brahmkshatriya.echo.oneforall.providers.deezer.DeezerParser
import dev.brahmkshatriya.echo.oneforall.providers.deezer.Utils
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DeezerTrackClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    private val client by lazy { OkHttpClient() }

    private fun extractUrlFromJson(json: JsonObject): String? {
        val data = json["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val media = data["media"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val source = media["sources"]?.jsonArray?.getOrNull(1)?.jsonObject
            ?: media["sources"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return null
        return source["url"]?.jsonPrimitive?.content
    }

    private suspend fun createStreamableForQuality(track: Track, quality: String): Streamable {
        return try {
            val currentTrackId = track.id
            val mediaJson =
                if (quality != "128" && quality != "mp3") api.getMediaUrl(track, quality)
                else {
                    if (deezerExtension.session.credentials.arl.isNotEmpty()) {
                        try {
                            api.getMP3MediaUrl(track, quality == "128")
                        } catch (_: Exception) {
                            api.getMediaUrl(track, "128")
                        }
                    } else {
                        api.getMediaUrl(track, "128")
                    }
                }
            val mjString = mediaJson.toString()
            if (mjString.contains("License token has no sufficient rights on requested media")) {
                check(quality != "128") { "Requested audio quality is unavailable" }
                return createStreamableForQuality(track, "128")
            }
            val trackJsonData = mediaJson["data"]?.jsonArray?.firstOrNull()?.jsonObject
            val mediaIsEmpty = trackJsonData?.get("media")?.jsonArray?.isEmpty() == true

            val (finalUrl, fallbackTrack) = when {
                mjString.contains("Track token has no sufficient rights on requested media") || mediaIsEmpty -> {
                    val fallBackId = track.extras["FALLBACK_ID"].orEmpty()
                    if (quality == "128") {
                        val fallbackObject = try { api.track(fallBackId) } catch (_: Exception) { null }
                        val resultOj = fallbackObject?.get("results")?.jsonObject
                        val fallBackTrack = resultOj?.let { parser.run { it.toTrack() } }
                        val fbMediaJson = if (fallBackTrack != null && deezerExtension.session.credentials.arl.isNotEmpty()) {
                            try { api.getMP3MediaUrl(fallBackTrack, true) } catch (_: Exception) { null }
                        } else null
                        val url = fbMediaJson?.let { extractUrlFromJson(it) }
                            ?: extractUrlFromJson(api.getMediaUrl(track.copy(id = fallBackId), "128"))!!
                        url to (fallBackTrack ?: track.copy(id = fallBackId))
                    } else {
                        val fallbackParsed = track.copy(id = fallBackId)
                        val fallbackMediaJson = api.getMediaUrl(fallbackParsed, quality)
                        val url = extractUrlFromJson(fallbackMediaJson)!!
                        url to fallbackParsed
                    }
                }

                mjString.contains("An error occurred while decoding track token") -> {
                    val fallbackObject = api.track(currentTrackId)
                    val resultOj = fallbackObject["results"]?.jsonObject!!
                    val fallBackTrack = parser.run { resultOj.toTrack() }
                    val fbMediaJson = if (deezerExtension.session.credentials.arl.isNotEmpty()) {
                        try { api.getMP3MediaUrl(fallBackTrack, true) } catch (_: Exception) { null }
                    } else null
                    val url = fbMediaJson?.let { extractUrlFromJson(it) }
                        ?: extractUrlFromJson(api.getMediaUrl(fallBackTrack, "128"))!!
                    url to fallBackTrack
                }

                else -> {
                    val url = extractUrlFromJson(mediaJson)!!
                    url to null
                }
            }

            val qualityValue = when (quality) {
                "flac" -> 1411
                "320" -> 320
                "128" -> 128
                else -> 0
            }
            val qualityTitle = when (quality) {
                "flac" -> "FLAC"
                "320" -> "320kbps"
                "128" -> "128kbps"
                else -> "UNKNOWN"
            }
            val keySourceId = fallbackTrack?.id ?: currentTrackId

            Streamable.server(
                id = finalUrl,
                quality = qualityValue,
                title = qualityTitle,
                extras = mapOf("key" to Utils.createBlowfishKey(keySourceId))
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            when (quality) {
                "flac" -> createStreamableForQuality(track, "320")
                "320" -> createStreamableForQuality(track, "128")
                "128" -> throw Exception(ExtensionText.get("providers.song_not_available_a6a5d0", "Song not available"))
                else -> throw Exception(ExtensionText.get("providers.song_not_available_a6a5d0", "Song not available"))
            }
        }
    }

    suspend fun loadStreamableMedia(streamable: Streamable): Streamable.Media {
        if (deezerExtension.session.credentials.arl.isNotEmpty()) {
            try { deezerExtension.handleArlExpiration() } catch (_: Throwable) {}
        }
        val resolvedStreamable = if (streamable.id.startsWith(placeholderPrefix)) {
            val info = streamable.id.removePrefix(placeholderPrefix).split(":")
            val trackId = info[0]
            val quality = info.getOrNull(1) ?: "128"
            if (trackId.isBlank()) {
                throw IllegalStateException("Track ID is empty in streamable: ${streamable.id}")
            }
            val newTrack = Track(
                id = trackId,
                title = quality,
                extras = mapOf(
                    "TRACK_TOKEN" to streamable.extras["TRACK_TOKEN"].orEmpty(),
                    "FALLBACK_ID" to streamable.extras["FALLBACK_ID"].orEmpty()
                )
            )
            createStreamableForQuality(newTrack, quality)
        } else {
            streamable
        }

        val title = resolvedStreamable.title ?: streamable.title
        return if (resolvedStreamable.quality == 12) {
            resolvedStreamable.id.toSource().copy(title = title).toMedia()
        } else {
            val contentLength = Utils.getContentLength(resolvedStreamable.id, client)
            Streamable.InputProvider { start, length ->
                require(start >= 0 && length >= -1) { "Invalid audio range" }
                val available = if (contentLength >= 0) (contentLength - start).coerceAtLeast(0) else -1L
                val requested = when {
                    length < 0 -> available
                    available < 0 -> length
                    else -> minOf(length, available)
                }
                if (requested == 0L) Pair(java.io.ByteArrayInputStream(byteArrayOf()), 0L)
                else Pair(AudioStreamProvider.openStream(resolvedStreamable, client, start,
                    if (requested < 0) -1 else Math.addExact(start, requested - 1)), requested)
            }.toSource(id = resolvedStreamable.id).copy(title = title).toMedia()
        }
    }

    private val qualityOptions = listOf("flac", "320", "128")

    suspend fun loadTrack(track: Track): Track {
        if (deezerExtension.session.credentials.arl.isNotEmpty()) {
            try { deezerExtension.handleArlExpiration() } catch (_: Throwable) {}
        }

        if (track.type == Track.Type.Podcast) {
            return track
        }

        val targetTrack = if (track.id.isBlank() && !track.isrc.isNullOrBlank()) {
            fetchTrackByIsrc(track.isrc!!) ?: track
        } else {
            track
        }

        if (targetTrack.id.isBlank()) {
            return targetTrack.copy(streamables = emptyList())
        }

        val isMp3Misc = targetTrack.extras["FILESIZE_MP3_MISC"]?.let { it != "0" } ?: false

        val streamables = if (isMp3Misc) {
            listOf(
                Streamable.server(
                    id = "$placeholderPrefix${targetTrack.id}:mp3",
                    quality = 0,
                    title = "MP3",
                    extras = mapOf(
                        "TRACK_TOKEN" to targetTrack.extras["TRACK_TOKEN"].orEmpty(),
                        "FALLBACK_ID" to targetTrack.extras["FALLBACK_ID"].orEmpty()
                    )
                )
            )
        } else {
            qualityOptions.map { quality ->
                val qualityValue = when (quality) {
                    "flac" -> 1411
                    "320" -> 320
                    "128" -> 128
                    else -> 0
                }
                val qualityTitle = when (quality) {
                    "flac" -> "FLAC"
                    "320" -> "320kbps"
                    "128" -> "128kbps"
                    else -> "UNKNOWN"
                }
                Streamable.server(
                    id = "$placeholderPrefix${targetTrack.id}:$quality",
                    quality = qualityValue,
                    title = qualityTitle,
                    extras = mapOf(
                        "TRACK_TOKEN" to targetTrack.extras["TRACK_TOKEN"].orEmpty(),
                        "FALLBACK_ID" to targetTrack.extras["FALLBACK_ID"].orEmpty()
                    )
                )
            }
        }
        return targetTrack.copy(
            streamables = streamables
        )
    }

    private suspend fun fetchTrackByIsrc(isrc: String): Track? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://api.deezer.com/track/isrc:$isrc").build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.string()
                val json = api.decodeJson(body)
                val id = json["id"]?.jsonPrimitive?.content ?: return@withContext null
                val title = json["title"]?.jsonPrimitive?.content ?: ""
                val trackToken = json["track_token"]?.jsonPrimitive?.content.orEmpty()
                Track(
                    id = id,
                    title = title,
                    isrc = isrc,
                    extras = mapOf("TRACK_TOKEN" to trackToken)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private val placeholderPrefix = "dzp:"
}
