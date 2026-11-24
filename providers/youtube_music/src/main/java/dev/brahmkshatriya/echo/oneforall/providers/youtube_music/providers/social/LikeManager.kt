package dev.brahmkshatriya.echo.oneforall.providers.youtube_music.providers.social

import dev.brahmkshatriya.echo.oneforall.providers.youtube_music.ExtensionText

import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.oneforall.providers.youtube_music.auth.YouTubeAuthManager
import dev.toastbits.ytmkt.model.external.SongLikedStatus


class LikeManager(
    private val authManager: YouTubeAuthManager
) {
    suspend fun setLiked(item: EchoMediaItem, shouldLike: Boolean) {
        val track = item as? Track 
            ?: throw Exception(ExtensionText.get("providers.only_tracks_can_be_liked_cb33ae", "Only tracks can be liked"))
        
        val auth = authManager.requireAuth()
        val likeStatus = if (shouldLike) SongLikedStatus.LIKED else SongLikedStatus.NEUTRAL
        auth.SetSongLiked.setSongLiked(track.id, likeStatus).getOrThrow()
    }

    fun isLiked(item: EchoMediaItem): Boolean {
        return item.extras["isLiked"]?.toBoolean() ?: false
    }
}
