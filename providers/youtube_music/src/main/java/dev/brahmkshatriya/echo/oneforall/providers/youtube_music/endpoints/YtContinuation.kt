package dev.brahmkshatriya.echo.oneforall.providers.youtube_music.endpoints

import kotlinx.serialization.Serializable

@Serializable
data class YtContinuation(
    val onResponseReceivedActions: List<OnResponseReceivedAction>
) {
    @Serializable
    data class OnResponseReceivedAction(
        val appendContinuationItemsAction: AppendContinuationItemsAction
    )

    @Serializable
    data class AppendContinuationItemsAction(
        val continuationItems: List<YoutubeiBrowseResponse.YoutubeiShelf.YoutubeiShelfContentsItem>
    )
}