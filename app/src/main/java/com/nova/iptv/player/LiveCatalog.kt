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

    private val TAGS = Regex("""[|\[(][^|\])]{1,12}[|\])]""")
    private val QUALITY = Regex("""\b(fhd|uhd|hd|sd|4k|hevc|h265|backup|ott|raw|vip|hq|multi)\b""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    private fun normalize(name: String): String = name.lowercase()
        .replace(TAGS, " ").replace(QUALITY, " ").replace(NON_ALNUM, "")

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

    // ------------------------------------------------------------ "≈ Similar": same channel, same server, other groups

    data class Similar(val item: PlayItem, val group: String, val quality: Int, val lighter: Boolean) {
        val qualityLabel: String get() = when (quality) { 3 -> "4K"; 2 -> "FHD"; 1 -> "HD"; 0 -> "SD"; else -> "" }
    }

    private val PREFIX = Regex("""^\s*\p{L}{2,3}\s*[|:\-–]\s*""")
    private val BRACKETS = Regex("""[\[(][^\])]{0,24}[\])]""")
    private val SMALL_LETTERS = Regex("""[\u02B0-\u02FF\u1D00-\u1DBF\u2070-\u209F]+""")
    private val MARKS = Regex("""\p{Mn}+""")
    private val SPLIT = Regex("""[^\p{L}\p{N}]+""")
    private val QUALITY_WORDS = setOf(
        "4k", "8k", "uhd", "fhd", "hd", "sd", "lq", "hq", "hevc", "h265", "h264", "x265", "raw", "backup", "hdr", "dv",
        "50fps", "60fps", "fps", "1080p", "720p", "2160p", "1080", "720", "2160", "multi", "vip", "ott", "ultra", "plus4k",
    )
    private val similarKeys = HashMap<String, List<String>>()
    private val catNames = HashMap<String, Map<String, String>>()

    /** "FR | beIN Sports 1 4K ᴴᴰ (backup)" → "bein sports 1": numbers must match exactly. */
    fun similarKey(name: String): String {
        var n = name.replace(SMALL_LETTERS, " ").replace(BRACKETS, " ").replace(PREFIX, "")
        if (n.any { it.code > 127 }) n = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replace(MARKS, "")
        return n.lowercase().split(SPLIT).filter { it.isNotBlank() && it !in QUALITY_WORDS }.joinToString(" ")
    }

    /** 3 = 4K/UHD, 2 = FHD/1080, 1 = HD/720, 0 = SD, -1 = not written in the name. */
    fun qualityOf(name: String): Int {
        val small = name.contains("ᵁᴴᴰ") || name.contains("⁴ᴷ")
        if (small) return 3
        if (name.contains("ᶠᴴᴰ")) return 2
        val words = name.lowercase().split(SPLIT).toSet()
        return when {
            words.any { it == "4k" || it == "8k" || it == "uhd" || it == "2160" || it == "2160p" } -> 3
            words.any { it == "fhd" || it == "1080" || it == "1080p" } -> 2
            name.contains("ᴴᴰ") || words.any { it == "hd" || it == "720" || it == "720p" } -> 1
            words.any { it == "sd" || it == "lq" } -> 0
            else -> -1
        }
    }

    /** The same channel in the other groups of the SAME server: lighter versions (FHD, HD, SD) first. */
    suspend fun similarOnSameServer(app: NovaApp, item: PlayItem): List<Similar> {
        val s = app.storage
        val a = s.accounts.firstOrNull { it.id == item.saved.accountId } ?: return emptyList()
        val want = similarKey(item.title)
        if (want.isBlank()) return emptyList()
        val list = allByAccount[a.id] ?: app.api.liveStreams(a, null).also { allByAccount[a.id] = it }
        val keys = similarKeys[a.id]?.takeIf { it.size == list.size } ?: list.map { similarKey(it.name) }.also { similarKeys[a.id] = it }
        val names = catNames[a.id] ?: runCatching { app.api.categories(a, ContentType.LIVE) }.getOrDefault(emptyList())
            .associate { it.id to it.name }.also { catNames[a.id] = it }
        val q0 = qualityOf(item.title).let { if (it < 0) 2 else it }
        val fmt = s.liveFormat
        val found = list.indices.filter { keys[it] == want && list[it].streamId != item.saved.id }.map { i ->
            val ch = list[i]
            val q = qualityOf(ch.name)
            Similar(
                PlayItem(SavedItem(ItemType.LIVE, ch.streamId, ch.name, ch.icon, accountId = a.id), app.api.liveUrl(a, ch.streamId, fmt), isLive = true),
                names[ch.categoryId] ?: "", q, lighter = q in 0 until q0,
            )
        }.distinctBy { it.item.saved.id }
        return found.filter { it.lighter }.sortedByDescending { it.quality } +
            found.filter { !it.lighter }.sortedBy { if (it.quality < 0) q0 else it.quality }
    }

    /** Group name of a live group key ("<account>|<category>"), when known. */
    fun groupName(key: String?): String? {
        if (key == null || !key.contains('|')) return null
        return categories?.firstOrNull { it.key == key }?.name
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
