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


    /** The same channel (same cleaned name and country) on one of your other accounts, if there is one. */
    suspend fun sameChannelElsewhere(app: NovaApp, item: PlayItem): PlayItem? {
        val s = app.storage
        val want = similarKey(item.title)
        if (want.isBlank()) return null
        val wantCountry = countryOf(item.title)
        val fmt = s.liveFormat
        for (a in s.accounts.filter { it.id != item.saved.accountId }) {
            val list = runCatching { liveList(app, a) }.getOrNull() ?: continue
            val keys = similarKeys[a.id] ?: continue
            val countries = similarCountries[a.id] ?: continue
            val candidates = list.indices.filter { keys[it] == want && sameCountry(countries[it], wantCountry) }
            if (candidates.isEmpty()) continue
            // The closest quality to the one watched.
            val q0 = qualityOf(item.title).let { if (it < 0) 2 else it }
            val i = candidates.minBy { kotlin.math.abs((qualityOf(list[it].name).let { q -> if (q < 0) 2 else q }) - q0) }
            val hit = list[i]
            return PlayItem(SavedItem(ItemType.LIVE, hit.streamId, hit.name, hit.icon, accountId = a.id), app.api.liveUrl(a, hit.streamId, fmt), isLive = true)
        }
        return null
    }

    // ------------------------------------------------------------ "≈ Similar": same channel, same server, other groups

    data class Similar(val item: PlayItem, val group: String, val quality: Int, val lighter: Boolean) {
        val qualityLabel: String get() = when (quality) { 3 -> "4K"; 2 -> "FHD"; 1 -> "HD"; 0 -> "SD"; else -> "" }
    }

    // Leading tags: "|FR|", "[US]", "FR|", "FR:", "FR-KIDS:", "BE-NL|", "Ca -", "FR-TF1", "FR_TF1", "|4K| FR|"…
    private val LEAD = Regex("""^(\s*(\|[^|]{1,16}\||\[[^\]]{1,16}\]|[A-Za-z]{2,6}(?:-[A-Za-z]{2,6})?\s*[|:]|[A-Za-z]{2,3}\s+-\s|[A-Za-z]{2,3}[-_](?=\S)))+""")
    private val TAIL_TAG = Regex("""(\[[^\]]{0,16}\]|\([^)]{0,16}\))\s*$""")
    private val FANCY = Regex("[\u0250-\u02FF\u1D00-\u1DBF\u2070-\u209F\uA720-\uA7FF]+")
    private val MARKS = Regex("""\p{Mn}+""")
    private val SPLIT = Regex("""[^\p{L}\p{N}]+""")
    private val TOKENS = Regex("""[^\p{L}\p{N}+]+""")
    private val LETTERS = Regex("[^a-z]+")
    private val QUALITY_WORDS = setOf(
        "4k", "8k", "uhd", "fhd", "hd", "sd", "lq", "hq", "hevc", "h265", "h264", "x265", "raw", "backup", "hdr", "dv",
        "50fps", "60fps", "fps", "1080p", "720p", "2160p", "1080", "720", "2160", "multi", "vip", "ott", "ultra", "plus4k",
    )
    /** Words in the leading tags that are not a country / language ("|4K|", "|VIP|", "FR-KIDS"…). */
    private val NOT_COUNTRY = setOf("4k", "8k", "uhd", "fhd", "hd", "sd", "vip", "ott", "tv", "raw", "hevc", "kids", "enf", "bein", "the", "vod", "ppv", "new")
    private val similarKeys = HashMap<String, List<String>>()
    private val similarCountries = HashMap<String, List<String?>>()
    private val catNames = HashMap<String, Map<String, String>>()

    private fun clean(name: String): String {
        var n = name.replace(FANCY, " ").replace('\u00A0', ' ')
        repeat(3) { n = n.replace(TAIL_TAG, " ").trimEnd() }
        return n
    }

    /** "|FR| TF1 HD", "FR_TF1 FHD HEVC", "|4K| TF1 4K" → "tf1". Numbers kept exactly ("tf1 1" for TF1 +1, beIN 1 ≠ beIN 10). */
    fun similarKey(name: String): String {
        var n = clean(name).replace(LEAD, "")
        if (n.any { it.code > 127 }) n = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replace(MARKS, "")
        val toks = n.lowercase().split(TOKENS).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t == "full" && i + 1 < toks.size && (toks[i + 1] == "hd" || toks[i + 1] == "fhd")) { i += 2; continue }
            if (t in QUALITY_WORDS || t == "+") { i++; continue }
            t.replace("+", "").takeIf { it.isNotBlank() }?.let { out += it }
            i++
        }
        return out.joinToString(" ")
    }

    /** The country / language of the leading tag ("fr" for "|FR|", "FR-KIDS:", "FR_"), null when there is none ("|4K|"). */
    fun countryOf(name: String): String? {
        val m = LEAD.find(clean(name)) ?: return null
        if (m.range.first != 0) return null
        return m.value.lowercase().split(LETTERS).firstOrNull { it.length in 2..3 && it !in NOT_COUNTRY }
    }

    private fun sameCountry(a: String?, b: String?) = a == null || b == null || a == b

    /** 3 = 4K/UHD, 2 = FHD/1080, 1 = HD/720, 0 = SD, -1 = not written in the name. */
    fun qualityOf(name: String): Int {
        if (name.contains("ᵁᴴᴰ") || name.contains("⁴ᴷ")) return 3
        if (name.contains("ᶠᴴᴰ") || name.contains("ꜰʜᴅ")) return 2
        val words = name.lowercase().split(SPLIT).toSet()
        return when {
            words.any { it == "4k" || it == "8k" || it == "uhd" || it == "2160" || it == "2160p" } -> 3
            words.any { it == "fhd" || it == "1080" || it == "1080p" } -> 2
            name.contains("ᴴᴰ") || name.contains("ʜᴅ") || words.any { it == "hd" || it == "720" || it == "720p" } -> 1
            words.any { it == "sd" || it == "lq" } -> 0
            else -> -1
        }
    }

    /** Result of a "≈ Similar" search (for the popup and the report). */
    class SimilarResult(val found: List<Similar>, val searched: Int, val key: String)

    private suspend fun liveList(app: NovaApp, a: com.nova.iptv.data.Account): List<LiveChannel> {
        val list = allByAccount[a.id] ?: app.api.liveStreams(a, null).also { allByAccount[a.id] = it }
        if (similarKeys[a.id]?.size != list.size) {
            similarKeys[a.id] = list.map { similarKey(it.name) }
            similarCountries[a.id] = list.map { countryOf(it.name) }
        }
        return list
    }

    /** The same channel in the other groups of the SAME server, same country: lighter versions (FHD, HD, SD) first. Throws when the list can't load. */
    suspend fun similarOnSameServer(app: NovaApp, item: PlayItem): SimilarResult {
        val s = app.storage
        val a = s.accounts.firstOrNull { it.id == item.saved.accountId } ?: return SimilarResult(emptyList(), 0, "")
        val want = similarKey(item.title)
        val wantCountry = countryOf(item.title)
        val list = liveList(app, a)
        if (want.isBlank()) return SimilarResult(emptyList(), list.size, want)
        val keys = similarKeys[a.id]!!
        val countries = similarCountries[a.id]!!
        val names = catNames[a.id] ?: runCatching { app.api.categories(a, ContentType.LIVE) }.getOrDefault(emptyList())
            .associate { it.id to it.name }.also { catNames[a.id] = it }
        val q0 = qualityOf(item.title).let { if (it < 0) 2 else it }
        val fmt = s.liveFormat
        val found = list.indices.filter { keys[it] == want && list[it].streamId != item.saved.id && sameCountry(countries[it], wantCountry) }.map { i ->
            val ch = list[i]
            val q = qualityOf(ch.name)
            Similar(
                PlayItem(SavedItem(ItemType.LIVE, ch.streamId, ch.name, ch.icon, accountId = a.id), app.api.liveUrl(a, ch.streamId, fmt), isLive = true),
                names[ch.categoryId] ?: "", q, lighter = q in 0 until q0,
            )
        }.distinctBy { it.item.saved.id }
        val sorted = found.filter { it.lighter }.sortedByDescending { it.quality } +
            found.filter { !it.lighter }.sortedBy { if (it.quality < 0) q0 else it.quality }
        return SimilarResult(sorted, list.size, want)
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
