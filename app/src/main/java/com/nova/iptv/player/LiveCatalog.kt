package com.nova.iptv.player

import com.nova.iptv.NovaApp
import com.nova.iptv.data.Category
import com.nova.iptv.data.ContentType
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.LiveChannel
import com.nova.iptv.data.Org
import com.nova.iptv.data.SavedItem

/**
 * Live TV groups and channels for the overlays inside the player (◀ group list, recent bar).
 * Uses the same order, hidden items and sorting as the main screen.
 */
object LiveCatalog {

    const val QUEUE = "__queue__"
    const val FAV = "__fav__"

    data class Group(val key: String, val title: String)

    private var categories: List<Category>? = null
    private var categoriesAt = 0L
    private val channels = HashMap<String, List<LiveChannel>>()

    /** "Current list", "★ Favorites", then every account's groups (first added account first). */
    suspend fun groups(app: NovaApp): List<Group> {
        PlaybackQueue.playlist?.let { pl ->
            return listOf(Group(QUEUE, "▶ Current list")) + pl.keys.map { Group("m3u:$it", it) }
        }
        val s = app.storage
        val accounts = s.accounts
        val now = System.currentTimeMillis()
        val cats = categories?.takeIf { now - categoriesAt < 30 * 60_000 } ?: accounts.flatMap { a ->
            runCatching { app.api.categories(a, ContentType.LIVE) }.getOrDefault(emptyList())
        }.also { categories = it; categoriesAt = now }
        val org = s.org
        val ordered = Org.applyOrder(cats, org.groupOrder["live"]) { it.key }
            .filter { "live|${it.key}" !in org.hiddenGroups }
        val multi = accounts.size > 1
        val list = mutableListOf(Group(QUEUE, "▶ Current list"))
        if (s.favorites.any { it.type == ItemType.LIVE }) list += Group(FAV, "★ Favorites")
        accounts.forEach { a ->
            ordered.filter { it.accountId == a.id }.forEach { c ->
                list += Group(c.key, if (multi) "${c.name}  ·  ${a.name}" else c.name)
            }
        }
        return list
    }

    /** The channels of a group as play items, in the chosen order / sort, hidden ones left out. */
    suspend fun channels(app: NovaApp, key: String): List<PlayItem> {
        val s = app.storage
        if (key == QUEUE) return PlaybackQueue.items
        if (key.startsWith("m3u:")) return PlaybackQueue.playlist?.get(key.removePrefix("m3u:")).orEmpty()
        val fmt = s.liveFormat
        if (key == FAV) {
            return s.favorites.filter { it.type == ItemType.LIVE }.mapNotNull { f ->
                val a = s.accounts.firstOrNull { it.id == f.accountId } ?: return@mapNotNull null
                PlayItem(f, app.api.liveUrl(a, f.id, fmt), isLive = true)
            }
        }
        val accountId = key.substringBefore('|')
        val catId = key.substringAfter('|')
        val a = s.accounts.firstOrNull { it.id == accountId } ?: return emptyList()
        val list = channels[key] ?: app.api.liveStreams(a, catId).also { channels[key] = it }
        val org = s.org
        var shown = Org.applyOrder(list, org.itemOrder["live|$key"]) { it.key }
            .filter { "live|${it.key}" !in org.hiddenItems }
        shown = when (s.sortFor("live")) {
            "added" -> shown.sortedByDescending { it.added }
            "number" -> shown.sortedBy { it.num.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }
            "name" -> shown.sortedBy { it.name.lowercase() }
            else -> shown
        }
        return shown.map { ch ->
            PlayItem(SavedItem(ItemType.LIVE, ch.streamId, ch.name, ch.icon, accountId = ch.accountId), app.api.liveUrl(a, ch.streamId, fmt), isLive = true)
        }
    }

    private val allByAccount = HashMap<String, List<LiveChannel>>()

    private fun normalize(name: String): String = name.lowercase()
        .replace(Regex("""[|\[(][^|\])]{1,12}[|\])]"""), " ")
        .replace(Regex("""\b(fhd|uhd|hd|sd|4k|hevc|h265|backup|ott|raw|vip|hq|multi)\b"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")

    /** The same channel (by name) on one of your other accounts, if there is one. */
    suspend fun sameChannelElsewhere(app: NovaApp, item: PlayItem): PlayItem? {
        val s = app.storage
        val want = normalize(item.title)
        if (want.isBlank()) return null
        val fmt = s.liveFormat
        for (a in s.accounts.filter { it.id != item.saved.accountId }) {
            val list = allByAccount[a.id] ?: runCatching { app.api.liveStreams(a, null) }.getOrNull()?.also { allByAccount[a.id] = it } ?: continue
            val hit = list.firstOrNull { normalize(it.name) == want } ?: continue
            return PlayItem(SavedItem(ItemType.LIVE, hit.streamId, hit.name, hit.icon, accountId = a.id), app.api.liveUrl(a, hit.streamId, fmt), isLive = true)
        }
        return null
    }

    /** The last channels watched (newest first), without [exceptKey]. */
    fun recentChannels(app: NovaApp, exceptKey: String?, max: Int = 6): List<PlayItem> {
        val s = app.storage
        val fmt = s.liveFormat
        return s.recent.filter { it.type == ItemType.LIVE && it.key != exceptKey }.mapNotNull { r ->
            val a = s.accounts.firstOrNull { it.id == r.accountId } ?: return@mapNotNull null
            PlayItem(r, app.api.liveUrl(a, r.id, fmt), isLive = true)
        }.distinctBy { it.saved.key }.take(max)
    }
}
