package com.nova.iptv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Web tab › 🛡 Filter lists: the well-known ad-blocking lists (EasyList, AdGuard, uBlock, 1Hosts…), the person's own
 * lists (added by link) and own rules. Two kinds of rules are used:
 *  - "block" rules (a hosts line, a plain domain or `||domain^`): requests to that address are never sent;
 *  - "hide" rules (`##selector`, `site.com##selector`): the box is hidden on the page and inside every embedded player.
 * uBlock's script rules (`##+js`, `#%#`, `#$#`, `:has-text` …) need uBlock's own engine and are skipped.
 * Lists update in the background: at every start / daily / weekly / manual.
 */
object FilterLists {
    data class FList(val id: String, val name: String, val url: String, val group: String, val defaultOn: Boolean)
    data class Info(val rules: Int = 0, val bytes: Long = 0, val updated: Long = 0, val error: String = "")

    val CATALOGUE = listOf(
        FList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", "ADS", true),
        FList("adg_base", "AdGuard Base", "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt", "ADS", true),
        FList("adg_mobile", "AdGuard Mobile Ads", "https://filters.adtidy.org/extension/ublock/filters/11.txt", "ADS", true),
        FList("ubo", "uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt", "ADS", true),
        FList("1hosts", "1Hosts (Lite)", "https://badmojr.github.io/1Hosts/Lite/adblock.txt", "ADS", true),
        FList("pgl", "Peter Lowe's ad & tracking servers", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext", "ADS", false),
        FList("oisd", "OISD Big", "https://big.oisd.nl", "ADS", false),
        FList("adg_fr", "AdGuard French (Liste FR)", "https://filters.adtidy.org/extension/ublock/filters/16.txt", "YOUR LANGUAGES", true),
        FList("liste_ar", "Liste AR (Arabic)", "https://easylist-downloads.adblockplus.org/Liste_AR.txt", "YOUR LANGUAGES", true),
        FList("adg_es", "AdGuard Spanish / Portuguese", "https://filters.adtidy.org/extension/ublock/filters/9.txt", "YOUR LANGUAGES", false),
        FList("adg_de", "EasyList German", "https://easylist.to/easylistgermany/easylistgermany.txt", "YOUR LANGUAGES", false),
        FList("adg_tr", "AdGuard Turkish", "https://filters.adtidy.org/extension/ublock/filters/13.txt", "YOUR LANGUAGES", false),
        FList("adg_ru", "AdGuard Russian", "https://filters.adtidy.org/extension/ublock/filters/1.txt", "YOUR LANGUAGES", false),
        FList("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", "PRIVACY · SAFETY", true),
        FList("ubo_badware", "uBlock — Badware risks", "https://ublockorigin.github.io/uAssets/filters/badware.txt", "PRIVACY · SAFETY", true),
        FList("ubo_privacy", "uBlock — Privacy", "https://ublockorigin.github.io/uAssets/filters/privacy.txt", "PRIVACY · SAFETY", false),
        FList("urlhaus", "Online Malicious URL list", "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt", "PRIVACY · SAFETY", true),
        FList("fanboy_annoy", "Fanboy's Annoyance List", "https://secure.fanboy.co.nz/fanboy-annoyance.txt", "ANNOYANCES", true),
        FList("adg_annoy", "AdGuard Annoyances", "https://filters.adtidy.org/extension/ublock/filters/14.txt", "ANNOYANCES", false),
        FList("ubo_annoy", "uBlock — Annoyances", "https://ublockorigin.github.io/uAssets/filters/annoyances-others.txt", "ANNOYANCES", false),
        FList("cookies", "EasyList Cookie List", "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", "ANNOYANCES", false),
        FList("antiadblock", "Adblock Warning Removal", "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt", "ANNOYANCES", false),
    )

    private const val DAY = 24L * 3600_000

    private lateinit var app: Context
    private lateinit var client: OkHttpClient
    private val prefs by lazy { app.getSharedPreferences("filters", Context.MODE_PRIVATE) }
    private val dir by lazy { File(app.filesDir, "filters").apply { mkdirs() } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var buildJob: Job? = null

    /** List id → on/off (the catalogue ids and "u:<link>" for the person's own lists). */
    val enabled = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** The person's own lists (links). */
    val custom = MutableStateFlow<List<String>>(emptyList())
    /** The person's own rules, one per line. */
    val myRules = MutableStateFlow("")
    /** "start", "daily", "weekly" or "manual". */
    val update = MutableStateFlow("daily")
    val info = MutableStateFlow<Map<String, Info>>(emptyMap())
    val updating = MutableStateFlow(false)
    val lastUpdate = MutableStateFlow(0L)
    /** Totals of the rules in use. */
    val blockCount = MutableStateFlow(0)
    val hideCount = MutableStateFlow(0)

    @Volatile private var blockHosts: Set<String> = emptySet()
    @Volatile private var allowHosts: Set<String> = emptySet()
    @Volatile private var genericCss: String = ""
    @Volatile private var siteHide: Map<String, List<String>> = emptyMap()
    @Volatile private var siteUnhide: Map<String, Set<String>> = emptyMap()

    fun isOn(id: String): Boolean = enabled.value[id] ?: (CATALOGUE.firstOrNull { it.id == id }?.defaultOn ?: true)
    private fun all(): List<FList> = CATALOGUE + custom.value.map { FList("u:$it", hostOf(it), it, "MY LISTS", true) }
    private fun hostOf(url: String) = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url
    private val UNSAFE = Regex("[^A-Za-z0-9_]")
    private fun fileOf(l: FList) = File(dir, l.id.replace(UNSAFE, "_").take(80) + ".txt")

    fun init(context: Context, http: OkHttpClient) {
        if (::app.isInitialized) return
        app = context.applicationContext
        client = http
        readPrefs()
        scope.launch {
            build()
            if (due()) updateNow()
        }
    }

    private fun due(): Boolean {
        val age = System.currentTimeMillis() - lastUpdate.value
        return when (update.value) {
            "start" -> true
            "daily" -> age > DAY
            "weekly" -> age > 7 * DAY
            else -> lastUpdate.value == 0L
        }
    }

    private fun readPrefs() {
        enabled.value = runCatching { JSONObject(prefs.getString("enabled", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getBoolean(it) } } }.getOrDefault(emptyMap())
        custom.value = runCatching { JSONArray(prefs.getString("custom", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        myRules.value = prefs.getString("my_rules", "") ?: ""
        update.value = prefs.getString("update", "daily") ?: "daily"
        lastUpdate.value = prefs.getLong("last_update", 0L)
        info.value = runCatching {
            val o = JSONObject(prefs.getString("info", "{}")!!)
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Info(it.optInt("r"), it.optLong("b"), it.optLong("u"), it.optString("e")) } }
        }.getOrDefault(emptyMap())
    }

    private fun save() {
        prefs.edit()
            .putString("enabled", JSONObject(enabled.value as Map<*, *>).toString())
            .putString("custom", JSONArray(custom.value).toString())
            .putString("my_rules", myRules.value)
            .putString("update", update.value)
            .putLong("last_update", lastUpdate.value)
            .apply()
    }

    private fun saveInfo() {
        val o = JSONObject()
        info.value.forEach { (k, v) -> o.put(k, JSONObject().put("r", v.rules).put("b", v.bytes).put("u", v.updated).put("e", v.error)) }
        prefs.edit().putString("info", o.toString()).apply()
    }

    // ------------------------------------------------------------ changes (all saved in the browser backup too)

    fun setOn(id: String, on: Boolean) {
        enabled.value = enabled.value + (id to on); save(); WebStore.changed()
        val l = all().firstOrNull { it.id == id }
        scope.launch { if (on && l != null && !fileOf(l).exists()) download(l); rebuildSoon() }
    }
    fun setUpdate(v: String) { update.value = v; save(); WebStore.changed() }
    fun setMyRules(v: String) { myRules.value = v; save(); WebStore.changed(); rebuildSoon() }
    fun addList(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        if (u in custom.value) return true
        custom.value = custom.value + u; save(); WebStore.changed()
        scope.launch { all().firstOrNull { it.url == u && it.id.startsWith("u:") }?.let { download(it) }; rebuildSoon() }
        return true
    }
    fun removeList(url: String) {
        custom.value = custom.value - url
        enabled.value = enabled.value - "u:$url"
        save(); WebStore.changed(); rebuildSoon()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", JSONObject(enabled.value as Map<*, *>))
        .put("custom", JSONArray(custom.value))
        .put("myRules", myRules.value)
        .put("update", update.value)

    fun fromJson(o: JSONObject?) {
        if (o == null || !::app.isInitialized) return
        o.optJSONObject("enabled")?.let { e -> enabled.value = e.keys().asSequence().associateWith { e.optBoolean(it, true) } }
        o.optJSONArray("custom")?.let { a -> custom.value = (0 until a.length()).map { a.getString(it) } }
        myRules.value = o.optString("myRules", myRules.value)
        update.value = o.optString("update", update.value)
        save()
        scope.launch { all().filter { isOn(it.id) && !fileOf(it).exists() }.forEach { download(it) }; rebuildSoon() }
    }

    // ------------------------------------------------------------ update

    fun updateNow() {
        if (updating.value) return
        updating.value = true
        scope.launch {
            try {
                all().filter { isOn(it.id) }.forEach { download(it) }
                lastUpdate.value = System.currentTimeMillis(); save()
                build()
            } finally { updating.value = false }
        }
    }

    private fun download(l: FList): Boolean {
        val f = fileOf(l)
        val ok = runCatching {
            client.newCall(Request.Builder().url(l.url).header("User-Agent", "Mozilla/5.0 NovaIPTV").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val tmp = File(dir, f.name + ".tmp")
                r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() < 200) { tmp.delete(); error("empty list") }
                tmp.renameTo(f)
            }
        }
        val old = info.value[l.id] ?: Info()
        info.value = info.value + (l.id to if (ok.isSuccess) old.copy(bytes = f.length(), updated = System.currentTimeMillis(), error = "")
            else old.copy(error = ok.exceptionOrNull()?.message ?: "failed"))
        saveInfo()
        Log.i("NovaTest", "FILTERS ${l.id}: " + (if (ok.isSuccess) "${f.length()} bytes" else "failed ${ok.exceptionOrNull()}"))
        return ok.isSuccess
    }

    private fun rebuildSoon() {
        buildJob?.cancel()
        buildJob = scope.launch { delay(800); build() }
    }

    // ------------------------------------------------------------ parsing

    private val HOSTS_LINE = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::1?)\\s+([A-Za-z0-9._-]+)")
    private val PLAIN_DOMAIN = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")
    private val NET_RULE = Regex("^(@@)?\\|\\|([A-Za-z0-9.*_-]+)\\^?(?:\\$(.*))?$")
    /** Network options that keep a `||domain^` rule a plain "block this address" rule. */
    private val OK_OPTS = setOf(
        "third-party", "3p", "script", "image", "popup", "all", "important", "subdocument", "frame", "xmlhttprequest", "xhr",
        "media", "stylesheet", "css", "other", "document", "doc", "object", "ping", "websocket", "font", "first-party", "1p",
    )
    /** Hide rules that need uBlock / AdGuard's own engine (not plain CSS). */
    private val NOT_CSS = Regex(
        ":(-abp-|has-text|upward|xpath|matches-|remove|style|min-text-length|watch-attr|others|if|if-not|nth-ancestor|contains|-ext-|properties|matches-path|matches-media|matches-attr|matches-css|spath|shadow)",
    )

    private class Acc {
        val block = HashSet<String>(300_000)
        val allow = HashSet<String>()
        val generic = LinkedHashSet<String>()
        val site = HashMap<String, MutableList<String>>()
        val unhide = HashMap<String, MutableSet<String>>()
    }

    /** Reads one rule; returns true when it is used. */
    private fun rule(raw: String, a: Acc): Boolean {
        val l = raw.trim()
        if (l.isEmpty() || l[0] == '!' || l[0] == '[' || l[0] == '#' && !l.startsWith("##")) return false
        // Hide rules.
        val hi = l.indexOf("##")
        if (hi >= 0 || l.contains("#@#")) {
            if (l.contains("#@#")) {
                val i = l.indexOf("#@#")
                val sel = l.substring(i + 3)
                val doms = l.substring(0, i)
                if (doms.isEmpty() || sel.isEmpty()) return false
                doms.split(',').filter { it.isNotBlank() && !it.startsWith("~") }.forEach { a.unhide.getOrPut(it.lowercase()) { HashSet() } += sel }
                return true
            }
            val doms = l.substring(0, hi)
            val sel = l.substring(hi + 2)
            if (sel.isEmpty() || sel.startsWith("+js") || sel.startsWith("^") || NOT_CSS.containsMatchIn(sel) || sel.length > 400) return false
            if (doms.contains('#') || doms.contains('$') || doms.contains('%') || doms.contains('?')) return false
            if (doms.isEmpty()) { a.generic += sel; return true }
            val list = doms.split(',').filter { it.isNotBlank() && !it.startsWith("~") && !it.contains('*') }
            if (list.isEmpty()) return false
            list.forEach { a.site.getOrPut(it.lowercase()) { ArrayList() } += sel }
            return true
        }
        if (l.contains("#$#") || l.contains("#%#") || l.contains("#?#")) return false
        HOSTS_LINE.find(l)?.let { m -> val h = m.groupValues[1].lowercase(); if (h.contains('.') && h != "localhost") { a.block += h; return true }; return false }
        if (PLAIN_DOMAIN.matches(l)) { a.block += l.lowercase(); return true }
        val m = NET_RULE.matchEntire(l) ?: return false
        val host = m.groupValues[2].lowercase().trimEnd('.')
        if (host.contains('*') || !host.contains('.')) return false
        val opts = m.groupValues[3]
        if (opts.isNotEmpty() && opts.split(',').any { o -> o.trimStart('~') !in OK_OPTS }) return false
        if (m.groupValues[1].isNotEmpty()) a.allow += host else a.block += host
        return true
    }

    private fun build() {
        val a = Acc()
        val newInfo = info.value.toMutableMap()
        all().filter { isOn(it.id) }.forEach { l ->
            val f = fileOf(l)
            if (!f.exists()) return@forEach
            var n = 0
            runCatching { f.forEachLine { if (rule(it, a)) n++ } }
            newInfo[l.id] = (newInfo[l.id] ?: Info()).copy(rules = n, bytes = f.length(), updated = (newInfo[l.id]?.updated ?: 0L).takeIf { it > 0 } ?: f.lastModified())
        }
        var mine = 0
        myRules.value.lineSequence().forEach { if (rule(it, a)) mine++ }
        newInfo["mine"] = Info(rules = mine)
        a.block.removeAll(a.allow)
        // Generic hide rules: grouped 20 per CSS rule (a selector the browser does not know drops only its group).
        val css = StringBuilder()
        a.generic.chunked(20).forEach { g -> css.append(g.joinToString(",")).append("{display:none!important}\n") }
        blockHosts = a.block
        allowHosts = a.allow
        genericCss = css.toString()
        siteHide = a.site
        siteUnhide = a.unhide
        blockCount.value = a.block.size
        hideCount.value = a.generic.size + a.site.values.sumOf { it.size }
        info.value = newInfo
        saveInfo()
        Log.i("NovaTest", "FILTERS built: block ${a.block.size}, hide ${hideCount.value} (generic ${a.generic.size}, sites ${a.site.size})")
    }

    // ------------------------------------------------------------ use

    private fun inSet(host: String?, set: Set<String>): Boolean {
        if (host.isNullOrBlank() || set.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** A "block" rule matches this address (and no exception rule). */
    fun blocks(host: String?): Boolean = inSet(host, blockHosts) && !inSet(host, allowHosts)

    /** The CSS that hides the ad boxes for this host (generic rules + the rules for this site). */
    fun cssFor(host: String?): String {
        val h = host.orEmpty().lowercase()
        val sel = ArrayList<String>()
        val un = HashSet<String>()
        var x = h
        while (x.isNotEmpty()) {
            siteHide[x]?.let { sel += it }
            siteUnhide[x]?.let { un += it }
            val dot = x.indexOf('.')
            if (dot < 0) break
            x = x.substring(dot + 1)
        }
        val specific = sel.filter { it !in un }.joinToString("\n") { "$it{display:none!important}" }
        return genericCss + specific
    }
}
