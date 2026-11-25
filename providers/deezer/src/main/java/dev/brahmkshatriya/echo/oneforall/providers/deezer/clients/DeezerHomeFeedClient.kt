package dev.brahmkshatriya.echo.oneforall.providers.deezer.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.oneforall.providers.deezer.DeezerApi
import dev.brahmkshatriya.echo.oneforall.providers.deezer.DeezerExtension
import dev.brahmkshatriya.echo.oneforall.providers.deezer.DeezerParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerHomeFeedClient(
    private val deezerExtension: DeezerExtension,
    private val api: DeezerApi,
    private val parser: DeezerParser
) {

    fun loadHomeFeed(shelf: String): Feed<Shelf> = PagedData.Single<Shelf> {
        deezerExtension.handleArlExpiration()
        val homePageResults = api.page("home")["results"]?.jsonObject ?: JsonObject(emptyMap())
        val homeSections = homePageResults["sections"]?.jsonArray ?: JsonArray(emptyList())

        supervisorScope {
            homeSections.mapNotNull { section ->
                val obj = section.asObjectOrNull() ?: return@mapNotNull null
                val id = obj.optString("module_id") ?: ""
                val title = obj.optString("title") ?: return@mapNotNull null
                val target = obj.optString("target")

                async<Shelf?>(dispatcher) {
                    runCatching {
                        if (id in CATEGORY_MODULE_ID) {
                            parser.run {
                                section.toShelfCategoryList(title, shelf) { targetChannel ->
                                    deezerExtension.channelFeed(targetChannel)
                                }
                            }
                        } else {
                            val itemsShelf = parser.run {
                                section.toShelfItemsList(title) as? Shelf.Lists.Items
                            }

                            if (itemsShelf != null) {
                                if (!target.isNullOrEmpty()) {
                                    Shelf.Lists.Items(
                                        id = itemsShelf.id,
                                        title = itemsShelf.title,
                                        subtitle = itemsShelf.subtitle,
                                        type = Shelf.Lists.Type.Linear,
                                        more = PagedData.Single<Shelf> {
                                            runCatching {
                                                val page = api.page(target.substringAfter("/"))
                                                val results = page["results"]?.jsonObject ?: return@Single emptyList()
                                                val sec = results["sections"]?.jsonArray ?: return@Single emptyList()
                                                val targetSection = sec.firstOrNull() ?: return@Single emptyList()
                                                val targetTitle = targetSection.jsonObject["title"]?.jsonPrimitive?.content ?: title
                                                parser.run {
                                                    targetSection.toShelfItemsList(targetTitle)?.let { listOf(it) } ?: emptyList()
                                                }
                                            }.getOrElse { emptyList() }
                                        }.toFeed(),
                                        list = itemsShelf.list
                                    )
                                } else {
                                    itemsShelf
                                }
                            } else {
                                val catShelf = parser.run {
                                    section.toShelfCategoryList(title, shelf) { targetChannel ->
                                        deezerExtension.channelFeed(targetChannel)
                                    }
                                }
                                if (catShelf.list.isNotEmpty()) catShelf else null
                            }
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }.toFeed()

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.optString(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        private val dispatcher = Dispatchers.Default
        private val CATEGORY_MODULE_ID = setOf(
            // Free Users
            "868606eb-4afc-4e1a-b4e4-75b30da34ac8",
            // Premium Users
            "4f6321c0-21f5-474f-8156-9f6dd6222d7c"
        )
    }
}