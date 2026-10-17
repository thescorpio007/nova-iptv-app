package com.nova.iptv.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.NovaApp
import com.nova.iptv.data.AdBlock
import com.nova.iptv.data.WebStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * A video found on the page. [height] = 1080, 720… (0 = not known). Played with the page's details (Referer, cookies…).
 */
data class FoundVideo(
    val url: String, val kind: String, val page: String,
    val height: Int = 0, val info: String = "", val protectedDrm: Boolean = false,
    /** The page's title and when the video was grabbed. */
    val title: String = "", val at: Long = 0L,
) {
    val quality: String get() = when { protectedDrm -> "🔒"; height > 0 -> "${height}p"; else -> "?" }
    val key: String get() = url.substringBefore('?')
}

/** 🎬 Grabbed videos: kept (on the device) until you remove them or press Clear. */
private object Grabbed {
    val list = kotlinx.coroutines.flow.MutableStateFlow<List<FoundVideo>>(emptyList())
    private var file: java.io.File? = null
    private val io = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun init(context: android.content.Context) {
        if (file != null) return
        val f = java.io.File(context.filesDir, "web-grabbed.json")
        file = f
        list.value = runCatching {
            val a = org.json.JSONArray(f.readText())
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { o ->
                    FoundVideo(o.getString("u"), o.optString("k"), o.optString("p"), o.optInt("h"), o.optString("i"),
                        o.optBoolean("d"), o.optString("t"), o.optLong("a"))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val f = file ?: return
        val snap = list.value
        io.launch {
            runCatching {
                val a = org.json.JSONArray()
                snap.forEach { v ->
                    a.put(org.json.JSONObject().put("u", v.url).put("k", v.kind).put("p", v.page).put("h", v.height).put("i", v.info)
                        .put("d", v.protectedDrm).put("t", v.title).put("a", v.at))
                }
                val tmp = java.io.File(f.parentFile, f.name + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(f)
            }
        }
    }

    /** The videos found on [page] during this visit replace that visit's entries (newest first; same address kept once). */
    fun merge(page: String, visitStart: Long, current: List<FoundVideo>, pageTitle: String) {
        val now = current.map { c -> c.copy(title = c.title.ifBlank { pageTitle }, at = if (c.at > 0) c.at else System.currentTimeMillis()) }
        val keys = now.map { it.key }.toSet()
        val rest = list.value.filterNot { (it.page == page && it.at >= visitStart) || it.key in keys }
        val merged = (now + rest).sortedByDescending { it.at }
        if (merged != list.value) { list.value = merged; save() }
    }
    fun remove(v: FoundVideo) { list.value = list.value.filterNot { it.key == v.key }; save() }
    fun clear(olderThanToday: Boolean) {
        val day = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        list.value = if (olderThanToday) list.value.filter { it.at >= day } else emptyList()
        save()
    }
}

/** The web page being shown (kept while you move to other tabs). */
private object WebState {
    @Volatile var pageUrl: String = ""
    @Volatile var pageSite: String = ""
    var webView: WebView? = null
    /** The "hide this box" script is added at the start of every frame (newer web engines). */
    @Volatile var frameScript = false
    /** Ad boxes removed on the page (counted with the blocked addresses in 🛡). */
    val removed = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Open tabs (the first one is the original page), the tab each was opened from, titles and addresses. */
    val tabs = mutableListOf<WebView>()
    val opener = HashMap<WebView, WebView>()
    val titles = HashMap<WebView, String>()
    val urls = HashMap<WebView, String>()
    val lastUsed = HashMap<WebView, Long>()
    val tabsVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** A button on the page that did not react (its name), reported by [RETRY_JS]. */
    val noReaction = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** The last button tapped in a group of buttons (e.g. a film page's servers): its name, the others, the time. */
    @Volatile var tapName: String = ""
    @Volatile var tapGroup: List<String> = emptyList()
    @Volatile var tapAt: Long = 0L
    /** When you last opened an address yourself (Go, bookmark, Open with): its redirects are allowed for 15 s. */
    @Volatile var userNavAt: Long = 0L
    /** Per tab: the pages you actually saw (redirect hops and blocked ad pages are never kept), and where you are. */
    class Hist { val list = mutableListOf<String>(); var idx = -1; @Volatile var going: String? = null }
    val hist = HashMap<WebView, Hist>()
    fun histOf(v: WebView): Hist = hist.getOrPut(v) { Hist() }
    /** When the page being shown was opened (its videos of this visit are "This page"). */
    @Volatile var visitStart: Long = 0L
    /** Tabs opened by ▶ Smart play (a clean video player), and the ones whose video was already offered. */
    val playerTabs = HashSet<WebView>()
    val offered = HashSet<WebView>()
}

/** ▶ Smart play: a player address caught after a tap ([blocked] = refused by the internet line). */
private data class SmartCatch(val url: String, val host: String, val name: String, val page: String, val others: List<String>, val blocked: Boolean = false)

/**
 * Main page only: when you tap one of a group of short buttons (e.g. a film page's servers) and the page's player box
 * does not change within 3 s, Nova is told (it then reloads the page and taps the same button again).
 */
private const val RETRY_JS = "(function(){try{if(window.top!==window||window.__novaRetry)return;window.__novaRetry=1;var N=window.NovaFilters;if(!N)return;" +
    "function txt(e){return (e.innerText||e.textContent||'').trim();}" +
    "function srcs(){return [].map.call(document.querySelectorAll('iframe'),function(f){return f.src}).join('|');}" +
    "function big(){return [].some.call(document.querySelectorAll('iframe'),function(f){var r=f.getBoundingClientRect();return r.width>200&&r.height>100;});}" +
    "document.addEventListener('click',function(ev){try{var e=ev.target,t='';for(var i=0;i<4&&e&&e!==document.body;i++){t=txt(e);if(t.length>=2&&t.length<=25&&t.indexOf('\\n')<0)break;e=e.parentElement;}" +
    "if(!e||e===document.body||t.length<2||t.length>25)return;var g=e.parentElement&&e.parentElement.parentElement?e.parentElement.parentElement:e.parentElement;if(!g)return;" +
    "var grp=[].filter.call(g.querySelectorAll(e.tagName),function(x){var s=txt(x);return s.length>=2&&s.length<=25&&s.indexOf('\\n')<0;});" +
    "if(grp.length<3)return;try{N.tap(t,grp.slice(0,15).map(function(x){return txt(x)}).join('|'));}catch(y){}" +
    "if(!N.retryOn()||!big())return;var before=srcs(),loc=location.href;" +
    "setTimeout(function(){if(location.href==loc&&srcs()==before)N.noReaction(t);},3000);}catch(x){}},true);}catch(e){}})();"

/** Taps the element whose text is exactly [text] (the smallest one), after a reload. */
private fun clickJs(text: String): String =
    "(function(t){var best=null;document.querySelectorAll('a,button,li,span,div').forEach(function(e){var s=(e.innerText||'').trim();" +
        "if(s===t&&(!best||best.contains(e)))best=e;});if(best){best.scrollIntoView({block:'center'});best.click();}})(" + org.json.JSONObject.quote(text) + ")"

private const val MAX_TABS = 6
private val SAFE_HOST = Regex("[^A-Za-z0-9.-]")
private val SAFE_HOST_PORT = Regex("[^A-Za-z0-9.:-]")

/** The page's own name: its main heading, its share title and its window title (cleaned in [pageNameOf]). */
private const val NAME_JS = "(function(){function t(s){return (s||'').replace(/\\s+/g,' ').trim();}var h=document.querySelector('h1');" +
    "var o=document.querySelector('meta[property=\"og:title\"]');return JSON.stringify([t(h&&h.innerText),t(o&&o.content),t(document.title)]);})()"
private val NAME_SPLIT = Regex("\\s+[|–—]\\s+|\\s+-\\s+")
private val NAME_JUNK = Regex("(?i)\\b(en streaming|streaming|regarder|voir|film complet|gratuit|vostfr|vf|french|full hd|hd|1080p|720p|4k|online|watch|free)\\b")
private val NAME_EDGES = Regex("^[\\s:|,.\\-–—]+|[\\s:|,.\\-–—]+$")
private val SLUG_START = Regex("^\\d+[-_]")
private val MULTI_SPACE = Regex("\\s{2,}")
private val SLUG_EXT = Regex("\\.(html?|php|aspx?)$", RegexOption.IGNORE_CASE)

/** Cleans a page name: the part that isn't the site's name, without "streaming / VF / HD…" words. */
private fun cleanName(raw: String, host: String): String {
    if (raw.isBlank() || raw.length > 160) return ""
    val site = host.removePrefix("www.").substringBefore('.').lowercase()
    val parts = raw.split(NAME_SPLIT).map { it.trim() }.filter { it.isNotBlank() && !it.lowercase().contains(site) }
    val pick = (parts.firstOrNull() ?: raw).replace(NAME_JUNK, " ").replace(MULTI_SPACE, " ").replace(NAME_EDGES, "")
    return if (pick.length >= 2 && !pick.equals(site, true)) pick else ""
}

/** The film name from the address: ".../11896-creed-lheritage-de-rocky-balboa.html" → "Creed lheritage de rocky balboa". */
private fun slugName(url: String): String {
    val seg = Uri.parse(url).lastPathSegment.orEmpty().replace(SLUG_EXT, "").replace(SLUG_START, "").replace('-', ' ').replace('_', ' ').trim()
    return if (seg.length >= 3 && seg.any { it.isLetter() }) seg.replaceFirstChar { it.uppercase() } else ""
}

/** Picks the best name among [h1, share title, window title]. */
private fun pageNameOf(parts: List<String>, host: String): String {
    val c = parts.map { cleanName(it, host) }
    val h1 = c.getOrElse(0) { "" }; val og = c.getOrElse(1) { "" }; val doc = c.getOrElse(2) { "" }
    // The heading is the film's name when the other titles agree on its first word (otherwise it may be the site's logo).
    val first = h1.lowercase().substringBefore(' ')
    return when {
        h1.isNotBlank() && first.length >= 2 && (og.lowercase().contains(first) || doc.lowercase().contains(first)) -> h1
        og.isNotBlank() -> og
        doc.isNotBlank() -> doc
        else -> h1
    }
}
/** Network errors that mean "your internet line refused this address". */
private val LINE_ERRORS = setOf(
    WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_UNKNOWN,
    WebViewClient.ERROR_IO, WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
)

/** The page put in a video player box when its host is refused by the internet line. */
private fun blockedHostPage(host: String): WebResourceResponse {
    val h = host.replace(SAFE_HOST_PORT, "")
    val html = "<html><head><meta name='viewport' content='width=device-width'></head><body style='margin:0;background:#15191f;color:#eee;" +
        "font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'><div style='padding:16px'>" +
        "<div style='font-size:34px'>⚠</div><div style='font-size:17px;font-weight:bold;margin:6px 0'>$h is blocked by your internet connection</div>" +
        "<div style='font-size:14px;color:#aaa'>Choose another server on the page.</div>" +
        "<button style='margin-top:14px;background:#2ee66b;color:#00210b;border:0;border-radius:14px;padding:9px 16px;font-weight:bold;font-size:14px' " +
        "onclick=\"try{NovaFilters.retry('$h')}catch(e){}location.reload()\">⟳ Try again</button></div></body></html>"
    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
}

/** A new browser page (each tab has its own). */
private fun newWebView(context: android.content.Context): WebView = WebView(context).apply {
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.setSupportMultipleWindows(true)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    setBackgroundColor(android.graphics.Color.WHITE)
    addJavascriptInterface(FilterBridge, "NovaFilters")
    runCatching {
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, AdBlock.FRAME_JS, setOf("*"))
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, RETRY_JS, setOf("*"))
            WebState.frameScript = true
        }
    }
}

/** Blocking level for the page being shown ("off" on the sites where blocking is turned off). */
private fun pageLevel(): String = if (AdBlock.siteOf(WebState.pageSite) in WebStore.allowSites.value) "off" else WebStore.adLevel.value

/** Called by the page script in every frame (the web engine's own thread). */
private object FilterBridge {
    @android.webkit.JavascriptInterface fun level(): String = pageLevel()
    @android.webkit.JavascriptInterface fun css(host: String?): String =
        if (!hideOn()) "" else runCatching { com.nova.iptv.data.FilterLists.cssFor(host) }.getOrDefault("")
    @android.webkit.JavascriptInterface fun base(): String = if (pageLevel() == "normal" || !hideOn()) "" else AdBlock.HIDE_CSS
    /** Hiding page parts (hide rules + clean-up) is on for this site. */
    @android.webkit.JavascriptInterface fun hideOn(): Boolean = AdBlock.siteOf(WebState.pageSite) !in WebStore.hideOffSites.value
    @android.webkit.JavascriptInterface fun removed(n: Int) { WebState.removed.value += n }
    @android.webkit.JavascriptInterface fun retry(host: String?) { host?.let { WebStore.clearBad(it) } }
    @android.webkit.JavascriptInterface fun retryOn(): Boolean = WebStore.flag("retryButtons")
    @android.webkit.JavascriptInterface fun tap(name: String?, group: String?) {
        WebState.tapName = name.orEmpty(); WebState.tapGroup = group.orEmpty().split('|').filter { it.isNotBlank() }; WebState.tapAt = System.currentTimeMillis()
    }
    @android.webkit.JavascriptInterface fun noReaction(text: String?) { if (!text.isNullOrBlank()) WebState.noReaction.value = text }
}

private val VIDEO_EXT = Regex("""\.(m3u8|mp4|mpd|webm|mkv|m4v|mov)$""", RegexOption.IGNORE_CASE)
private val DRM_HINT = Regex("""widevine|playready|fairplay|/license""", RegexOption.IGNORE_CASE)
private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
private val QUALITY_IN_URL = Regex("""(?<!\d)(2160|1440|1080|720|576|480|360|240)p?(?!\d)""")
private val EXTINF = Regex("""#EXTINF:([\d.]+)""")
private const val SEARCH = "https://duckduckgo.com/?q="
private const val NOVA_FOLDER = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FNovaIPTV"
private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

private fun durationText(sec: Double): String {
    val m = (sec / 60).toInt()
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min"
}

private fun sizeText(b: Long): String = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else "${b / 1048576} MB"

/** 🌐 Web: a small browser with strong ad blocking, kids protection, and Nova's player for the page's videos. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(vm: MainViewModel, onLeave: () -> Unit = { vm.setTab(Tab.LIVE) }) {
    val context = LocalContext.current
    val app = context.applicationContext as NovaApp
    val main = remember { Handler(Looper.getMainLooper()) }
    val bookmarks by WebStore.bookmarks.collectAsStateWithLifecycle()
    val adLevel by WebStore.adLevel.collectAsStateWithLifecycle()
    val kids by WebStore.kidsMode.collectAsStateWithLifecycle()
    val flags by WebStore.flags.collectAsStateWithLifecycle()
    val allow by WebStore.allowSites.collectAsStateWithLifecycle()
    val offer by WebStore.restoreOffer.collectAsStateWithLifecycle()
    val hasPin = vm.appStorage.hasPin

    var address by remember { mutableStateOf(WebState.pageUrl) }
    var title by remember { mutableStateOf("") }
    var showPage by remember { mutableStateOf(WebState.pageUrl.isNotBlank()) }
    var canBack by remember { mutableStateOf(false) }
    var blocked by remember { mutableIntStateOf(0) }
    val removedBoxes by WebState.removed.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateListOf<FoundVideo>() }
    remember { Grabbed.init(context); true }
    val grabbed by Grabbed.list.collectAsStateWithLifecycle()
    var clearAsk by remember { mutableStateOf(false) }
    /** Video addresses already seen on this page (read from the web engine's own threads). */
    val seen = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    var listOpen by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf<FoundVideo?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var fullView by remember { mutableStateOf<View?>(null) }
    var fullCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    // 🔒 PIN to open the Web tab.
    var unlocked by remember { mutableStateOf(!(hasPin && WebStore.flag("pinOpen"))) }
    LaunchedEffect(Unit) { if (!unlocked) PinGate.require(true) { unlocked = true } }
    LaunchedEffect(adLevel, kids) { AdBlock.ensure(adLevel, kids) }

    val pickTree = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) CoroutineScope(Dispatchers.IO).launch {
            val n = WebStore.setTree(uri)
            main.post { Toast.makeText(context, if (n != null) "Browser restored: $n bookmarks" else "No browser file found in that folder", Toast.LENGTH_LONG).show() }
        }
    }

    fun site(): String = AdBlock.siteOf(WebState.pageSite)
    fun level(): String = pageLevel()
    fun showNote(t: String) {
        if (!WebStore.flag("blockMsg")) return
        main.post { note = t; main.postDelayed({ if (note == t) note = null }, 4000) }
    }
    fun bookmarkSites() = WebStore.bookmarks.value.map { AdBlock.siteOf(Uri.parse(it.url).host) }.toSet()
    /** Kids protection: may this address be opened? (null = yes, otherwise why not). */
    fun kidsRefuse(url: Uri): String? = when (WebStore.kidsMode.value) {
        "bookmarks" -> if (AdBlock.siteOf(url.host) in bookmarkSites()) null else "only your bookmarked sites can be opened"
        "adult" -> if (AdBlock.isAdult(url.host, url.toString())) "adult or gambling site" else null
        else -> null
    }

    /** Adds a video once (pieces of the same file share an address without the "?…" part). */
    fun addFound(v: FoundVideo) = main.post {
        val key = v.url.substringBefore('?')
        val i = found.indexOfFirst { it.url.substringBefore('?') == key }
        if (i >= 0) {
            val old = found[i]
            if ((v.height > 0 && old.height == 0) || (v.info.isNotBlank() && old.info.isBlank()) || v.protectedDrm != old.protectedDrm) {
                found[i] = old.copy(
                    height = if (v.height > 0) v.height else old.height,
                    info = v.info.ifBlank { old.info }, protectedDrm = old.protectedDrm || v.protectedDrm,
                )
            }
        } else if (found.size < 30) found += v.copy(at = if (v.at > 0) v.at else System.currentTimeMillis())
    }

    /** Asks the website about a found video: qualities of an HLS list, length, size of a file. */
    fun inspect(url: String, kind: String, page: String, headers: Map<String, String>) {
        CoroutineScope(Dispatchers.IO).launch {
            fun get(u: String, head: Boolean = false): okhttp3.Response = app.http.newCall(
                okhttp3.Request.Builder().url(u).apply { headers.forEach { (k, v) -> header(k, v) }; if (head) head() }.build(),
            ).execute()
            runCatching {
                if (kind == "HLS") {
                    val text = get(url).use { it.body?.string().orEmpty() }
                    val drm = text.contains("SAMPLE-AES") || text.contains("KEYFORMAT=", ignoreCase = true)
                    if (text.contains("#EXT-X-STREAM-INF")) {
                        val lines = text.lines()
                        var firstVariant: String? = null
                        lines.forEachIndexed { i, l ->
                            if (!l.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
                            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@forEachIndexed
                            val h = RESOLUTION.find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val v = java.net.URI(url).resolve(next.trim()).toString()
                            if (firstVariant == null) firstVariant = v
                            addFound(FoundVideo(v, "HLS", page, h, protectedDrm = drm))
                        }
                        // The master list itself: shown only when it brought no qualities.
                        main.post { if (found.any { it.page == page && it.url != url && it.kind == "HLS" }) found.removeAll { it.url == url } }
                        // Length (same for every quality): from one quality list.
                        firstVariant?.let { fv ->
                            val list = get(fv).use { it.body?.string().orEmpty() }
                            val sec = EXTINF.findAll(list).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
                            if (sec > 30 && list.contains("#EXT-X-ENDLIST")) {
                                val info = "HLS · " + durationText(sec)
                                main.post { found.replaceAll { if (it.page == page && it.kind == "HLS") it.copy(info = info) else it } }
                            }
                        }
                    } else {
                        val sec = EXTINF.findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
                        val info = if (text.contains("#EXT-X-ENDLIST") && sec > 30) "HLS · " + durationText(sec) else "HLS · live"
                        main.post { found.replaceAll { if (it.url == url) it.copy(info = info, protectedDrm = it.protectedDrm || drm) else it } }
                    }
                } else if (kind != "DASH") {
                    val size = get(url, head = true).use { it.header("Content-Length")?.toLongOrNull() ?: 0L }
                    if (size > 0) main.post { found.replaceAll { if (it.url == url) it.copy(info = "$kind · " + sizeText(size)) else it } }
                }
            }
        }
    }

    fun go(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> SEARCH + Uri.encode(t)
        }
        kidsRefuse(Uri.parse(url))?.let { why ->
            Toast.makeText(context, "👶 Blocked for children: $why", Toast.LENGTH_LONG).show()
            return
        }
        showPage = true
        address = url
        WebState.userNavAt = System.currentTimeMillis()
        WebState.webView?.loadUrl(url)
    }

    var webView by remember {
        mutableStateOf(WebState.webView ?: newWebView(context).also { WebState.webView = it; WebState.tabs += it })
    }
    if (webView !in WebState.tabs) WebState.tabs.add(0, webView)
    val tabsVersion by WebState.tabsVersion.collectAsStateWithLifecycle()
    var tabsOpen by remember { mutableStateOf(false) }
    var askTab by remember { mutableStateOf<String?>(null) }
    var shieldOpen by remember { mutableStateOf(false) }
    val hideOff by WebStore.hideOffSites.collectAsStateWithLifecycle()
    /** "Retry buttons that don't react": the button to tap again after the reload. */
    var pendingClick by remember { mutableStateOf<String?>(null) }
    var lastRetry by remember { mutableStateOf("" to 0L) }
    var smart by remember { mutableStateOf<SmartCatch?>(null) }
    /** The page's real name (read from the page itself when its videos are found). */
    var pageName by remember { mutableStateOf("") }
    var smartReady by remember { mutableStateOf(false) }
    val defaultUa = remember { WebSettings.getDefaultUserAgent(context) }

    // Settings that change how pages are loaded.
    fun applySettings(webView: WebView) {
        val s = webView.settings
        s.javaScriptEnabled = WebStore.flag("javascript")
        s.blockNetworkImage = WebStore.flag("blockImages")
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = WebStore.flag("safeBrowsing")
        CookieManager.getInstance().setAcceptCookie(WebStore.flag("cookies"))
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, WebStore.flag("cookies") && !WebStore.flag("privateMode"))
        // "Look like a normal browser": no "wv" (app browser) mark, and no X-Requested-With header with the app's name.
        s.userAgentString = when {
            WebStore.flag("desktop") -> DESKTOP_UA
            WebStore.flag("normalBrowser") -> defaultUa.replace("; wv", "").replace(" Version/4.0", "")
            else -> defaultUa
        }
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, if (WebStore.flag("normalBrowser")) emptySet() else setOf("*"))
            }
        }
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, WebStore.flag("dark"))
            }
        }
        s.cacheMode = if (WebStore.flag("privateMode")) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    }
    LaunchedEffect(flags) { WebState.tabs.toList().forEach { applySettings(it) } }

    // Tabs: switch, open, close (declared before the page events that use them).
    lateinit var attachRef: (WebView) -> Unit
    fun switchTo(wv: WebView) {
        webView = wv
        WebState.webView = wv
        WebState.lastUsed[wv] = System.currentTimeMillis()
        val u = WebState.urls[wv].orEmpty()
        WebState.pageUrl = u
        WebState.pageSite = Uri.parse(u).host.orEmpty()
        address = u
        title = WebState.titles[wv].orEmpty(); pageName = ""
        found.clear(); seen.clear(); blocked = 0; WebState.removed.value = 0
        WebState.visitStart = System.currentTimeMillis()
        canBack = wv.canGoBack()
        showPage = u.isNotBlank()
        listOpen = false; tabsOpen = false
        WebState.tabsVersion.value++
    }
    fun closeTab(wv: WebView) {
        val back = WebState.opener[wv]?.takeIf { it in WebState.tabs && it !== wv }
        WebState.tabs.remove(wv)
        WebState.opener.entries.filter { it.value === wv }.forEach { e -> back?.let { WebState.opener[e.key] = it } ?: WebState.opener.remove(e.key) }
        WebState.opener.remove(wv); WebState.titles.remove(wv); WebState.urls.remove(wv); WebState.lastUsed.remove(wv)
        WebState.playerTabs.remove(wv); WebState.offered.remove(wv)
        if (wv === webView) {
            val next = back ?: WebState.tabs.lastOrNull() ?: newWebView(context).also { applySettings(it); attachRef(it); WebState.tabs += it }
            switchTo(next)
        }
        (wv.parent as? ViewGroup)?.removeView(wv)
        runCatching { wv.stopLoading(); wv.destroy() }
        WebState.tabsVersion.value++
    }
    fun openTab(url: String?, referer: String? = null, player: Boolean = false) {
        while (WebState.tabs.size >= MAX_TABS) {
            val oldest = WebState.tabs.filter { it !== webView }.minByOrNull { WebState.lastUsed[it] ?: 0L } ?: break
            closeTab(oldest)
        }
        val from = webView
        val wv = newWebView(context)
        applySettings(wv); attachRef(wv)
        WebState.tabs += wv
        if (url != null) WebState.opener[wv] = from
        switchTo(wv)
        if (player) WebState.playerTabs += wv
        if (url != null && referer != null) {
            showPage = true; address = url
            wv.loadUrl(url, mapOf("Referer" to referer))
        } else if (url != null) go(url)
    }

    // ---- clean history (← / →): only pages that stayed on screen are kept.
    var histVersion by remember { mutableIntStateOf(0) }
    fun record(view: WebView, url: String) {
        if (!url.startsWith("http")) return
        val h = WebState.histOf(view)
        val going = h.going
        if (going != null) {
            h.going = null
            val i = h.list.indexOf(going)
            if (i >= 0) { h.idx = i; if (url != going && h.list.getOrNull(i) != url) h.list[i] = url; histVersion++; return }
        }
        if (h.list.getOrNull(h.idx) == url) return
        while (h.list.size > h.idx + 1) h.list.removeAt(h.list.size - 1)
        h.list += url; h.idx = h.list.size - 1
        while (h.list.size > 100) { h.list.removeAt(0); h.idx-- }
        histVersion++
    }
    /** A page is kept once it stays on screen 1.5 s (a redirect hop moves on before that). */
    fun recordSoon(view: WebView, url: String) {
        main.postDelayed({ if (view.url == url || WebState.urls[view] == url) record(view, url) }, 1500)
    }
    fun histCanBack(): Boolean { val h = WebState.histOf(webView); return h.idx > 0 }
    fun histCanFwd(): Boolean { val h = WebState.histOf(webView); return h.idx >= 0 && h.idx < h.list.size - 1 }
    fun histGo(delta: Int) {
        val h = WebState.histOf(webView)
        val j = h.idx + delta
        val target = h.list.getOrNull(j) ?: return
        h.idx = j; h.going = target; histVersion++
        showPage = true; address = target
        WebState.userNavAt = System.currentTimeMillis()
        webView.loadUrl(target)
    }
    /** ← : the previous page you saw; at a tab's first page: back to the tab it came from, or "No earlier page". */
    fun goBackClean() {
        when {
            !showPage && WebState.pageUrl.isNotBlank() -> showPage = true
            histCanBack() -> histGo(-1)
            WebState.opener[webView]?.let { it in WebState.tabs } == true -> closeTab(webView)
            else -> Toast.makeText(context, "No earlier page in this tab", Toast.LENGTH_SHORT).show()
        }
    }
    fun reloadClean() {
        val cur = WebState.histOf(webView).let { it.list.getOrNull(it.idx) }
        if (!showPage) { go(address); return }
        if (cur != null && webView.url != cur) { WebState.userNavAt = System.currentTimeMillis(); webView.loadUrl(cur) } else webView.reload()
    }
    var histOpen by remember { mutableStateOf(false) }
    fun fetchName(view: WebView) {
        val forUrl = WebState.urls[view].orEmpty()
        view.evaluateJavascript(NAME_JS) { r ->
            if (view !== WebState.webView || WebState.urls[view].orEmpty() != forUrl) return@evaluateJavascript
            val parts = runCatching {
                val a = org.json.JSONArray(org.json.JSONTokener(r).nextValue() as String)
                (0 until a.length()).map { a.optString(it) }
            }.getOrDefault(emptyList())
            val n = pageNameOf(parts, Uri.parse(forUrl).host.orEmpty())
            if (n.isNotBlank()) pageName = n
        }
    }
    /** Name for videos of this page: the page's own name, else its title, else the name in its address. */
    fun bestName(): String = pageName.ifBlank { cleanName(title, WebState.pageSite) }.ifBlank { slugName(WebState.pageUrl) }.ifBlank { title }

    /** The clean player refused to work on its own: back to the film page and its own player. */
    fun playerFailed(wv: WebView) {
        if (wv !in WebState.tabs) return
        val msg = "▶ This player only works inside its page — back to the page's player"
        note = msg; main.postDelayed({ if (note == msg) note = null }, 6000)
        closeTab(wv)
    }

    fun attach(webView: WebView) {
        fun current() = webView === WebState.webView
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                val host = u.host
                val empty = { WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) }
                val cur = current()
                if (!request.isForMainFrame) {
                    if (WebStore.kidsMode.value != "off" && AdBlock.isAdult(host, u.toString())) { if (cur) main.post { blocked++ }; return empty() }
                    if (AdBlock.siteOf(host) != site() && (AdBlock.isAd(host, level()) || (level() != "off" && com.nova.iptv.data.FilterLists.blocks(host)))) { if (cur) main.post { blocked++ }; return empty() }
                    val isDoc = request.requestHeaders?.get("Accept")?.contains("text/html") == true
                    // ▶ Smart play: a player from another site loaded just after you tapped one of the page's buttons.
                    val tapped = cur && isDoc && WebStore.smartPlay.value != "off" && view !in WebState.playerTabs &&
                        System.currentTimeMillis() - WebState.tapAt < 5000 && WebState.tapName.isNotBlank() &&
                        AdBlock.siteOf(host) != site() && kidsRefuse(u) == null
                    if (tapped) {
                        val c = SmartCatch(u.toString(), host.orEmpty(), WebState.tapName, WebState.pageUrl,
                            WebState.tapGroup.filter { it != WebState.tapName }, blocked = WebStore.isBad(u.authority))
                        WebState.tapAt = 0L
                        main.post { smartReady = false; smart = c }
                    }
                    // A video host the internet line refused: a Nova message instead of a page that never loads.
                    if (WebStore.isBad(u.authority) && isDoc) return blockedHostPage(u.authority.orEmpty())
                }
                if (cur && WebStore.findVideos.value) {
                    val s = u.toString()
                    if (DRM_HINT.containsMatchIn(s) && request.method == "POST") {
                        main.post { found.replaceAll { if (it.page == WebState.pageUrl) it.copy(protectedDrm = true) else it } }
                    }
                    val m = VIDEO_EXT.find(u.path.orEmpty())
                    if (m != null) {
                        val ext = m.groupValues[1].lowercase()
                        val page = WebState.pageUrl
                        val h = QUALITY_IN_URL.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val kind = when (ext) { "m3u8" -> "HLS"; "mpd" -> "DASH"; else -> ext.uppercase() }
                        val key = s.substringBefore('?')
                        val isNew = seen.add(key)
                        addFound(FoundVideo(s, kind, page, h, info = kind, protectedDrm = ext == "mpd"))
                        if (isNew) {
                            val hdr = HashMap<String, String>()
                            request.requestHeaders?.forEach { (k, v) -> if (!k.equals("Range", true)) hdr[k] = v }
                            CookieManager.getInstance().getCookie(s)?.let { hdr["Cookie"] = it }
                            inspect(s, kind, page, hdr)
                        }
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme.orEmpty().lowercase()
                if (scheme != "http" && scheme != "https") {
                    // intent://, market://, tg:// … : "open another app" jumps.
                    if (WebStore.flag("appJumps") || WebStore.kidsMode.value != "off") { main.post { blocked++ }; showNote("🚫 Blocked: the page tried to open another app ($scheme)"); return true }
                    return false
                }
                if (request.isForMainFrame) kidsRefuse(u)?.let { why ->
                    main.post { blocked++ }
                    main.post { Toast.makeText(context, "👶 Blocked for children: $why", Toast.LENGTH_LONG).show() }
                    return true
                }
                // The whole page sent to an ad address (click-under tricks): refused even after a click.
                if (request.isForMainFrame && level() != "off" && AdBlock.siteOf(u.host) != site() &&
                    (AdBlock.isAd(u.host, level()) || com.nova.iptv.data.FilterLists.blocks(u.host))
                ) {
                    main.post { blocked++ }
                    showNote("🚫 Ad page blocked: ${u.host}")
                    return true
                }
                // An address you opened yourself may move to its new home (e.g. a site's new address): allowed for 15 s.
                if (request.isForMainFrame && System.currentTimeMillis() - WebState.userNavAt < 15_000) {
                    WebState.userNavAt = System.currentTimeMillis()
                    return false
                }
                if (request.isForMainFrame && !request.hasGesture() && WebStore.flag("redirects") && level() != "off" &&
                    site().isNotEmpty() && AdBlock.siteOf(u.host) != site()
                ) {
                    main.post { blocked++ }
                    // Always told (even with short messages off): otherwise the page just seems not to open.
                    val msg = "🚫 Redirect to ${u.host} blocked — tap Go again to open it"
                    main.post { note = msg; address = u.toString(); main.postDelayed({ if (note == msg) note = null }, 6000) }
                    return true
                }
                return false
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && view in WebState.playerTabs && response.statusCode >= 400) main.post { playerFailed(view) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame && view in WebState.playerTabs) { main.post { playerFailed(view) }; return }
                if (Build.VERSION.SDK_INT < 23 || request.isForMainFrame) return
                if (request.requestHeaders?.get("Accept")?.contains("text/html") != true) return
                if (error.errorCode !in LINE_ERRORS) return
                val host = request.url.host?.lowercase() ?: return
                val key = request.url.authority?.lowercase() ?: return
                // Never the page's own address (only the video hosts it embeds).
                if (key == Uri.parse(WebState.urls[view].orEmpty()).authority?.lowercase()) return
                if (WebStore.isBad(key)) return
                WebStore.markBad(key)
                main.post { smart?.let { if (it.host == host) smart = it.copy(blocked = true) } }
                android.util.Log.i("NovaTest", "WEB host refused by the line: $host (${error.errorCode})")
                // Load that player box again: it now gets Nova's message and counts as loaded, so the page accepts the next server.
                val h = key.replace(SAFE_HOST_PORT, "")
                main.post {
                    view.evaluateJavascript(
                        "(function(){document.querySelectorAll('iframe').forEach(function(f){try{if(f.src&&new URL(f.src).host=='$h'){f.src=f.src;}}catch(e){}});})()", null,
                    )
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                WebState.urls[view] = url
                WebState.tabsVersion.value++
                if (!current()) return
                val newSite = AdBlock.siteOf(Uri.parse(url).host)
                if (newSite != AdBlock.siteOf(WebState.pageSite)) { blocked = 0; WebState.removed.value = 0 }
                WebState.pageUrl = url
                WebState.pageSite = Uri.parse(url).host.orEmpty()
                WebState.visitStart = System.currentTimeMillis()
                // A new page: the old page's name is never carried over.
                title = ""; pageName = ""
                address = url
                found.clear()
                seen.clear()
                listOpen = false
                canBack = view.canGoBack()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Pages changed by the site's own scripts (no real reload) are noticed too.
                val changed = WebState.urls[view] != url
                WebState.urls[view] = url
                if (current() && !isReload) {
                    address = url; WebState.pageUrl = url
                    // A site that changes page with its own scripts: its name follows the new address.
                    if (changed) { pageName = ""; main.postDelayed({ if (WebState.urls[view] == url) fetchName(view) }, 1500) }
                }
                recordSoon(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (current()) canBack = view.canGoBack()
                recordSoon(view, url)
                if (current()) fetchName(view)
                if (!WebState.frameScript) view.evaluateJavascript(RETRY_JS, null)
                if (current()) pendingClick?.let { t ->
                    pendingClick = null
                    main.postDelayed({ view.evaluateJavascript(clickJs(t), null) }, 1500)
                }
                if (!WebStore.flag("privateMode")) WebStore.lastUrl = url
                val lv = level()
                // Older web engines (no start-of-frame script): the same script, on the main page only.
                if (lv != "off" && !WebState.frameScript) view.evaluateJavascript(AdBlock.FRAME_JS, null)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, t: String?) {
                WebState.titles[view] = t.orEmpty(); WebState.tabsVersion.value++
                if (current()) title = t.orEmpty()
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val blockPopups = WebStore.flag("popups") && level() != "off"
                if (blockPopups && !isUserGesture) {
                    blocked++
                    showNote("🚫 Pop-up / new tab blocked")
                    return false
                }
                // A link that opens a "new tab" (target=_blank): same site → opened in this page; another site → pop-up (blocked).
                val temp = WebView(view.context)
                var handled = false
                fun take(u: Uri) {
                    if (handled) return
                    handled = true
                    main.post { runCatching { temp.stopLoading(); temp.destroy() } }
                    val same = AdBlock.siteOf(u.host) == site()
                    // Every link is filtered first: ad / malicious / adult / gambling addresses never open, in any mode.
                    val refused = kidsRefuse(u) != null ||
                        (level() != "off" && (AdBlock.isAd(u.host, level()) || com.nova.iptv.data.FilterLists.blocks(u.host)))
                    when {
                        refused -> { main.post { blocked++ }; showNote("🚫 Blocked: ${u.host}") }
                        WebStore.newTab.value == "tab" -> main.post { openTab(u.toString()) }
                        WebStore.newTab.value == "ask" -> main.post { askTab = u.toString() }
                        !blockPopups || same -> main.post { go(u.toString()) }
                        else -> { main.post { blocked++ }; showNote("🚫 Pop-up to ${u.host} blocked") }
                    }
                }
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { take(r.url); return true }
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { if (url.startsWith("http")) take(Uri.parse(url)) }
                }
                main.postDelayed({ if (!handled) { handled = true; runCatching { temp.destroy() } } }, 5000)
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (WebStore.flag("denyPerms") || WebStore.kidsMode.value != "off") { request.deny(); showNote("🚫 Camera / microphone refused") }
                else request.deny() // Nova never gives the camera or microphone to websites
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
                showNote("🚫 Location refused")
            }

            override fun onShowCustomView(v: View, callback: CustomViewCallback) { fullView = v; fullCallback = callback }
            override fun onHideCustomView() { fullView = null; fullCallback = null }
        }
        webView.setDownloadListener { url, _, _, mime, _ ->
            if (!current()) return@setDownloadListener
            if (WebStore.flag("siteDownloads") || WebStore.kidsMode.value != "off" || url.endsWith(".apk", true) || mime == "application/vnd.android.package-archive") {
                blocked++
                showNote("🚫 Download started by the site blocked")
            } else {
                choose = FoundVideo(url, mime.substringAfter('/').uppercase().take(5), WebState.pageUrl, info = "file")
            }
        }
    }
    attachRef = { attach(it) }
    DisposableEffect(Unit) {
        WebState.tabs.toList().forEach { attach(it) }
        onDispose {
            if (WebStore.flag("privateMode")) { CookieManager.getInstance().removeAllCookies(null); WebState.tabs.forEach { it.clearHistory() } }
        }
    }

    BackHandler(enabled = fullView != null) { fullCallback?.onCustomViewHidden(); fullView = null }
    BackHandler(enabled = fullView == null && (listOpen || settingsOpen)) { listOpen = false; settingsOpen = false }
    val canBackClean = remember(histVersion, webView, showPage) { histCanBack() || (!showPage && WebState.pageUrl.isNotBlank()) }
    BackHandler(enabled = fullView == null && !listOpen && !settingsOpen && canBackClean) { goBackClean() }
    // A tab opened from another one: Back at its first page closes it and returns to the tab it came from.
    BackHandler(enabled = fullView == null && !listOpen && !settingsOpen && !canBackClean && WebState.opener[webView]?.let { it in WebState.tabs } == true) {
        closeTab(webView)
    }
    // At the first page / the bookmarks: Back twice (within 2 s) leaves the Web tab.
    var lastBack by remember { mutableStateOf(0L) }
    BackHandler(enabled = fullView == null && !listOpen && !settingsOpen && !canBackClean && WebState.opener[webView]?.let { it in WebState.tabs } != true) {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) onLeave()
        else { lastBack = now; Toast.makeText(context, "Press Back again to leave Web", Toast.LENGTH_SHORT).show() }
    }
    // A button that did not react: reload the page and tap it again (once per button within 20 s).
    val noReact by WebState.noReaction.collectAsStateWithLifecycle()
    LaunchedEffect(noReact) {
        val t = noReact ?: return@LaunchedEffect
        WebState.noReaction.value = null
        val now = System.currentTimeMillis()
        if (lastRetry.first == t && now - lastRetry.second < 20_000) return@LaunchedEffect
        lastRetry = t to now
        pendingClick = t
        val msg = "⟳ The page didn't react — reloading and selecting $t…"
        note = msg; main.postDelayed({ if (note == msg) note = null }, 5000)
        webView.reload()
    }
    // Every video found is also kept in 🎬 Grabbed (with the page's title and the time).
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { Triple(found.toList(), title, pageName) }.collect { (l, _, _) ->
            if (l.isNotEmpty()) {
                if (pageName.isBlank()) WebState.webView?.let { fetchName(it) }
                Grabbed.merge(WebState.pageUrl, WebState.visitStart, l, bestName())
            }
        }
    }
    // ▶ Smart play: 2–3 s check, then ask (10 s → open the clean player) or open at once.
    LaunchedEffect(smart) {
        val c = smart ?: return@LaunchedEffect
        kotlinx.coroutines.delay(2500)
        if (smart !== c) return@LaunchedEffect
        if (c.blocked) { kotlinx.coroutines.delay(6000); if (smart === c) smart = null; return@LaunchedEffect }
        if (WebStore.smartPlay.value == "auto") { smart = null; openTab(c.url, referer = c.page, player = true) }
        else smartReady = true
    }
    // In a clean player tab: when the video is found, offer the 3 choices once (best quality first).
    LaunchedEffect(found.size, webView) {
        val wv = webView
        if (wv !in WebState.playerTabs || wv in WebState.offered || found.none { !it.protectedDrm }) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        if (webView !== wv || wv in WebState.offered) return@LaunchedEffect
        WebState.offered += wv
        choose = found.filter { !it.protectedDrm }.maxByOrNull { it.height }
    }
    // An address sent to the Web tab (Open with, Share, welcome screen).
    val pending by WebStore.pendingUrl.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { pending?.let { WebStore.pendingUrl.value = null; go(it) } }

    // First visit: start page.
    remember {
        if (WebState.pageUrl.isBlank()) when (WebStore.startPage.value) {
            "last" -> WebStore.lastUrl.takeIf { it.isNotBlank() }?.let { main.post { go(it) } }
            "search" -> main.post { go("https://duckduckgo.com/") }
        }
        true
    }

    fun act(v: FoundVideo, what: Int) {
        if (v.protectedDrm) {
            Toast.makeText(context, "🔒 This video is protected (DRM): it can only play on the website.", Toast.LENGTH_LONG).show()
            return
        }
        val host = Uri.parse(v.url).host ?: return
        val pageUri = Uri.parse(v.page)
        val headers = linkedMapOf(
            "User-Agent" to (webView.settings.userAgentString ?: ""),
            "Referer" to v.page,
            "Origin" to "${pageUri.scheme}://${pageUri.host}",
        )
        CookieManager.getInstance().getCookie(v.url)?.let { headers["Cookie"] = it }
        app.storage.setLinkHeaders(host, app.storage.linkHeaders(host) + headers.filterValues { it.isNotBlank() })
        val name = v.title.ifBlank { bestName() }.ifBlank { pageUri.host ?: "Video" }.let { if (v.height > 0) "$it (${v.height}p)" else it }
        when (what) {
            0 -> LinkActions.play(context, v.url, name)
            1 -> LinkActions.downloadAndWatch(context, v.url, name)
            else -> {
                LinkActions.download(context, v.url, name)
                Toast.makeText(context, "⬇ Download started: $name — see it in ⬇ Downloads", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (!unlocked) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🔒 The Web tab is locked with the PIN", fontSize = 16.sp)
                SmallButton("Enter the PIN", { PinGate.require(true) { unlocked = true } })
            }
        }
        return
    }

    val hideAddress = WebStore.flag("hideAddress") && kids != "off" && !PinGate.unlocked(hasPin)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // ---- row 2: the web address
            if (!hideAddress) {
                // The address box, then ⟳ · Paste · Go as their own buttons (one press → on the remote reaches them).
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TvTextField(
                        value = address, onValueChange = { address = it }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search or type a web address") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        onSubmit = { go(address) },
                    )
                    FocusBox(onClick = { reloadClean() }, modifier = Modifier.background(Color(0xFF262B33), RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp)) {
                        Text("⟳", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    PasteButton { go(it) }
                    FocusBox(onClick = { go(address) }, modifier = Modifier.background(Color(0xFF2EE66B), RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp)) {
                        Text("Go ›", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00210B),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                    }
                }
            }
            // ---- row 3: the buttons
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FocusBox(onClick = { goBackClean() }, onLongClick = { histOpen = true }, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
                    Text("←", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                SmallButton("→", { if (histCanFwd()) histGo(1) else Toast.makeText(context, "No next page", Toast.LENGTH_SHORT).show() })
                val marked = bookmarks.any { it.url == WebState.pageUrl }
                SmallButton(if (marked) "★" else "☆", {
                    if (showPage && WebState.pageUrl.isNotBlank()) {
                        if (WebStore.flag("pinSettings")) PinGate.require(hasPin) { WebStore.toggleBookmark(title, WebState.pageUrl) }
                        else WebStore.toggleBookmark(title, WebState.pageUrl)
                    }
                })
                SmallButton("★ List", { showPage = false; listOpen = false })
                key(tabsVersion) { SmallButton("▢ ${WebState.tabs.size}", { tabsOpen = true }) }
                Chip("🎬 ${found.size}" + if (grabbed.size > found.size) " · ${grabbed.size}" else "", found.isNotEmpty(), { listOpen = !listOpen })
                FocusBox(onClick = { shieldOpen = true }, modifier = Modifier.background(Color(0xFF0F2A1C), RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp)) {
                    Text(
                        if (adLevel == "off" || site() in allow) "🛡 off" else "🛡 ${blocked + removedBoxes}" + if (site() in hideOff) " ◐" else "",
                        fontSize = 13.sp, color = Color(0xFFBFF5D3), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (kids != "off") Text("👶", fontSize = 15.sp)
                SmallButton("⚙", {
                    if (WebStore.flag("pinSettings")) PinGate.require(hasPin) { settingsOpen = true } else settingsOpen = true
                })
            }
            note?.let {
                Text(it, fontSize = 12.sp, color = Color(0xFFFFD08A),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF2A2112)).padding(horizontal = 12.dp, vertical = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                // The web page only takes its own space (it is not shown at all on the bookmarks page).
                if (showPage) Row(Modifier.fillMaxSize()) {
                    key(webView) {
                        AndroidView(
                            factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                            modifier = Modifier.weight(1f).fillMaxSize().clipToBounds(),
                        )
                    }
                    WebScrollBar(webView)
                }
                if (!showPage) StartPage(bookmarks, offer, onOpen = { go(it) }, onRestore = { pickTree.launch(Uri.parse(NOVA_FOLDER)) },
                    canEdit = { f -> if (WebStore.flag("pinSettings")) PinGate.require(hasPin, f) else f() })
            }
        }

        // ---- 🎬 grabbed videos: this page first, then earlier pages (kept until you clear them)
        if (listOpen) {
            val cur = found.sortedWith(compareBy<FoundVideo> { it.protectedDrm }.thenByDescending { it.height }.thenBy { it.kind != "HLS" })
            val curKeys = cur.map { it.key }.toSet()
            val earlier = grabbed.filter { it.key !in curKeys }.groupBy { it.page }
            val df = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
            @Composable
            fun row(v: FoundVideo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FocusBox(onClick = { if (v.protectedDrm) act(v, 0) else choose = v }, modifier = Modifier.weight(1f)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                v.quality, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                color = if (v.height >= 1080) Color(0xFF00210B) else Color.White,
                                modifier = Modifier.background(
                                    when { v.height >= 1080 -> Color(0xFF2EE66B); v.height >= 720 -> Color(0xFF3D6FB8); else -> Color(0xFF555555) },
                                    RoundedCornerShape(5.dp),
                                ).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(v.title.ifBlank { bestName() }.ifBlank { "Video" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "🕒 " + (if (v.at > 0) df.format(java.util.Date(v.at)) else "now") + " · " +
                                        (if (v.protectedDrm) "only on the website" else v.info.ifBlank { v.kind }),
                                    fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1,
                                )
                            }
                        }
                    }
                    SmallButton("✕", { Grabbed.remove(v); found.removeAll { it.key == v.key } })
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 720.dp)
                    .background(Color(0xF815191F), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .border(2.dp, Color(0xFF2EE66B), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎬 Grabbed videos (${cur.size + earlier.values.sumOf { it.size }})", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (grabbed.isNotEmpty() || cur.isNotEmpty()) SmallButton("🗑 Clear all", { clearAsk = true })
                    SmallButton("✕", { listOpen = false })
                }
                Text("Kept until you clear them · newest first · tap one → Play / Download & watch / Download", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item {
                        Text("THIS PAGE" + (if (title.isNotBlank()) " · " + title.substringBefore(" » ") else ""), fontSize = 11.sp, color = Color(0xFF2EE66B),
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        if (cur.isEmpty()) Text("No video found yet on this page — start the video, then look again.", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                    }
                    itemsIndexed(cur) { _, v -> row(v) }
                    earlier.forEach { (page, vids) ->
                        item {
                            FocusBox(onClick = { listOpen = false; go(page) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text("EARLIER · " + vids.first().title.ifBlank { "Video" } + " · " + (Uri.parse(page).host ?: "") + "  ›",
                                    fontSize = 11.sp, color = Color(0xFF2EE66B), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                            }
                        }
                        itemsIndexed(vids) { _, v -> row(v) }
                    }
                    if (earlier.isNotEmpty()) item {
                        Text("⚠ Older links may have expired on the site — if one doesn't play, open its page again (tap the page name).",
                            fontSize = 11.sp, color = Color(0xFFFFB74D), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        if (clearAsk) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { clearAsk = false }) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                        .border(2.dp, Color(0xFFFF8A80), RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("🗑 Clear all grabbed videos?", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${grabbed.size} videos from ${grabbed.map { it.page }.distinct().size} pages. Downloaded files are not touched.",
                        fontSize = 12.sp, color = Color(0xFFAAAAAA))
                    FocusBox(onClick = { clearAsk = false; Grabbed.clear(false); found.clear() }, modifier = Modifier.fillMaxWidth().background(Color(0xFF3A1A1A), RoundedCornerShape(10.dp))) {
                        Text("🗑  Clear all", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF8A80), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    FocusBox(onClick = { clearAsk = false; Grabbed.clear(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear only older than today", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    FocusBox(onClick = { clearAsk = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("✕  Cancel", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
            }
        }

        // ---- the 3 choices
        choose?.let { v ->
            androidx.compose.ui.window.Dialog(onDismissRequest = { choose = null }) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                        .border(2.dp, Color(0xFF2EE66B), RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("[${v.quality}] " + v.title.ifBlank { bestName() }.ifBlank { "Video" }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text((v.info.ifBlank { v.kind }) + " · from " + (Uri.parse(v.page).host ?: ""), fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    listOf("▶  Play now", "⬇▶  Download & watch", "⬇  Download only").forEachIndexed { i, label ->
                        FocusBox(onClick = { choose = null; listOpen = false; act(v, i) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                    }
                    FocusBox(onClick = { choose = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (webView in WebState.playerTabs) "Keep the site's player" else "Cancel", fontSize = 14.sp, color = Color(0xFFAAAAAA),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }

        // ---- 🛡 this site: page hiding / all blocking on or off
        if (shieldOpen) {
            val st = site()
            androidx.compose.ui.window.Dialog(onDismissRequest = { shieldOpen = false }) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                        .border(2.dp, Color(0xFF2EE66B), RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("🛡 $st", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${blocked} addresses blocked · ${removedBoxes} boxes removed on this page", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                    Text("A menu or part of the page missing? Turn page hiding off for this site — ad addresses stay blocked.",
                        fontSize = 12.sp, color = Color(0xFFAAAAAA))
                    fun pin(f: () -> Unit) = if (WebStore.flag("pinSettings")) PinGate.require(hasPin, f) else f()
                    FocusBox(onClick = { shieldOpen = false; pin { WebStore.toggleHideOff(st); webView.reload() } }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (st in hideOff) "◐  Turn page hiding back ON for $st" else "◐  Turn page hiding OFF for $st (keep blocking ads)",
                            fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    FocusBox(onClick = { shieldOpen = false; pin { WebStore.toggleAllowSite(st); webView.reload() } }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (st in allow) "🛡  Turn all blocking back ON for $st" else "🛡  Turn ALL blocking off for $st",
                            fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    FocusBox(onClick = { shieldOpen = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("✕  Close", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
            }
        }

        // ---- ▶ Smart play panel (checking / blocked) and question (10 s → open the clean player)
        smart?.let { c ->
            if (!smartReady) Column(
                Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().widthIn(max = 560.dp)
                    .background(Color(0xF21D2229), RoundedCornerShape(14.dp))
                    .border(1.dp, if (c.blocked) Color(0x99FF8A80) else Color(0x662EE66B), RoundedCornerShape(14.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (c.blocked) {
                    Text("⚠ ${c.name} is blocked by your connection", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    Text("${c.host} can't be reached on this line — pick another server.", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    if (c.others.isNotEmpty()) Text("Other servers on this page: " + c.others.take(8).joinToString(" · "), fontSize = 12.sp, color = Color(0xFF2EE66B))
                } else {
                    Text("🔎 Preparing ${c.name}…  ${c.host}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    Text("✅ Not an ad / malicious / adult address", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    Text("✅ Not blocked by your connection", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    Text("⏳ Getting the player ready…", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { SmallButton("✕", { smart = null }) }
            } else {
                var left by remember(c) { mutableIntStateOf(10) }
                LaunchedEffect(c) {
                    while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
                    if (smart === c) { smart = null; smartReady = false; openTab(c.url, referer = c.page, player = true) }
                }
                androidx.compose.ui.window.Dialog(onDismissRequest = { smart = null; smartReady = false }) {
                    Column(
                        Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                            .border(2.dp, Color(0xFF2EE66B), RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("▶ ${c.name} is ready", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${c.host} · checked & filtered", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                        Box(Modifier.fillMaxWidth().background(Color(0xFF262B33), RoundedCornerShape(3.dp))) {
                            Box(Modifier.fillMaxWidth(left / 10f).heightIn(min = 5.dp).background(Color(0xFF2EE66B), RoundedCornerShape(3.dp)))
                        }
                        Text("Opening the clean player in $left s", fontSize = 11.sp, color = Color(0xFF999999))
                        FocusBox(onClick = { smart = null; smartReady = false; openTab(c.url, referer = c.page, player = true) },
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF2EE66B), RoundedCornerShape(10.dp))) {
                            Text("▶  Open clean player", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00210B),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                        FocusBox(onClick = { smart = null; smartReady = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("↳  Stay in the page", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                    }
                }
            }
        }

        // ---- "Open this link in a new tab?" (10 s, then Cancel)
        askTab?.let { link ->
            var left by remember(link) { mutableIntStateOf(10) }
            LaunchedEffect(link) {
                while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
                if (askTab == link) askTab = null
            }
            val lu = Uri.parse(link)
            val same = AdBlock.siteOf(lu.host) == site()
            androidx.compose.ui.window.Dialog(onDismissRequest = { askTab = null }) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                        .border(2.dp, Color(0xFF2EE66B), RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Open this link in a new tab?", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text((lu.host ?: "") + " — " + if (same) "same site" else "other site", fontSize = 13.sp,
                        color = if (same) Color(0xFF2EE66B) else Color(0xFFFFB74D))
                    Text(link, fontSize = 11.sp, color = Color(0xFF999999), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.fillMaxWidth().background(Color(0xFF262B33), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(left / 10f).heightIn(min = 5.dp).background(Color(0xFF2EE66B), RoundedCornerShape(3.dp)))
                    }
                    Text("Cancel in $left s", fontSize = 11.sp, color = Color(0xFF999999))
                    FocusBox(onClick = { askTab = null; openTab(link) }, modifier = Modifier.fillMaxWidth().background(Color(0xFF2EE66B), RoundedCornerShape(10.dp))) {
                        Text("▢  Open in new tab", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00210B), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    FocusBox(onClick = { askTab = null; go(link) }, modifier = Modifier.fillMaxWidth()) {
                        Text("↳  Open here", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    FocusBox(onClick = { askTab = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("✕  Cancel", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
            }
        }

        // ---- long-press ←: this tab's history
        if (histOpen) {
            val h = WebState.histOf(webView)
            androidx.compose.ui.window.Dialog(onDismissRequest = { histOpen = false }) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("History of this tab", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Redirect pages and blocked ad pages are not kept.", fontSize = 11.sp, color = Color(0xFF999999))
                    if (h.list.isEmpty()) Text("Nothing yet.", fontSize = 13.sp)
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        itemsIndexed(h.list.toList()) { i, u ->
                            FocusBox(onClick = { histOpen = false; histGo(i - h.idx) }, modifier = Modifier.fillMaxWidth()) {
                                Text("${i + 1} · " + (Uri.parse(u).host ?: "") + (Uri.parse(u).path?.takeIf { it.length > 1 }?.let { " " + it.take(60) } ?: "") +
                                    if (i == h.idx) "   ◀ you are here" else "",
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (i == h.idx) Color(0xFF2EE66B) else Color.Unspecified,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp))
                            }
                        }
                    }
                    SmallButton("✕ Close", { histOpen = false })
                }
            }
        }

        // ---- ▢ open tabs
        if (tabsOpen) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { tabsOpen = false }) {
                key(tabsVersion) {
                    Column(
                        Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("▢ Open tabs (${WebState.tabs.size} of $MAX_TABS)", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            SmallButton("✕", { tabsOpen = false })
                        }
                        WebState.tabs.toList().forEach { t ->
                            val u = WebState.urls[t].orEmpty()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FocusBox(
                                    onClick = { switchTo(t) },
                                    modifier = Modifier.weight(1f).let { if (t === webView) it.border(2.dp, Color(0xFF2EE66B), RoundedCornerShape(10.dp)) else it },
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                        Text((if (t in WebState.playerTabs) "▶ Player · " else "") + WebState.titles[t].orEmpty().ifBlank { if (u.isBlank()) "New tab" else Uri.parse(u).host ?: u },
                                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (u.isNotBlank()) Text(Uri.parse(u).host ?: "", fontSize = 11.sp, color = Color(0xFF999999), maxLines = 1)
                                    }
                                }
                                SmallButton("✕", { closeTab(t) }, warn = true)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton("＋ New tab", { openTab(null) })
                            if (WebState.tabs.size > 1) SmallButton("Close all others", { WebState.tabs.filter { it !== webView }.forEach { closeTab(it) } })
                        }
                    }
                }
            }
        }

        // ---- ⚙ browser settings
        if (settingsOpen) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { settingsOpen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    Modifier.fillMaxWidth(0.94f).widthIn(max = 760.dp).heightIn(max = 900.dp)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(14.dp)).padding(6.dp),
                ) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                Text("⚙ Browser settings", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                SmallButton("✕", { settingsOpen = false })
                            }
                        }
                        item { WebSettingsContent(page = WebState.pageUrl.takeIf { showPage }, onPick = { settingsOpen = false; pickTree.launch(Uri.parse(NOVA_FOLDER)) }) }
                    }
                }
            }
        }

        // ---- a video shown full screen by the page itself
        fullView?.let { v ->
            AndroidView(factory = { FrameLayout(it).apply { setBackgroundColor(android.graphics.Color.BLACK); (v.parent as? ViewGroup)?.removeView(v); addView(v) } },
                modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StartPage(
    bookmarks: List<WebStore.Bookmark>, offer: Boolean, onOpen: (String) -> Unit, onRestore: () -> Unit, canEdit: (() -> Unit) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offer) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF1C2633), RoundedCornerShape(10.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Restore your bookmarks and browser settings from an earlier installation?", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Choose the Download/NovaIPTV folder once — Nova keeps the permission and restores automatically from then on.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("📂 Choose folder", onRestore)
                    SmallButton("No thanks", { WebStore.dismissOffer() })
                }
            }
        }
        Text("★ Bookmarks", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (bookmarks.isEmpty()) {
            Text("No bookmarks yet. Open a page and press ☆ to keep it here. Type a web address or words to search at the top.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(bookmarks) { _, b ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusBox(onClick = { onOpen(b.url) }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(b.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Uri.parse(b.url).host ?: b.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    SmallButton("▲", { canEdit { WebStore.moveBookmark(b, -1) } })
                    SmallButton("▼", { canEdit { WebStore.moveBookmark(b, 1) } })
                    SmallButton("✕", { canEdit { WebStore.removeBookmark(b) } }, warn = true)
                }
            }
        }
    }
}

/** Web ⚙ › Ads › 🛡 Filter lists: lists on / off, own lists and rules, automatic update. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterListsContent() {
    val FL = com.nova.iptv.data.FilterLists
    val enabled by FL.enabled.collectAsStateWithLifecycle()
    val custom by FL.custom.collectAsStateWithLifecycle()
    val info by FL.info.collectAsStateWithLifecycle()
    val update by FL.update.collectAsStateWithLifecycle()
    val updating by FL.updating.collectAsStateWithLifecycle()
    val last by FL.lastUpdate.collectAsStateWithLifecycle()
    val blockN by FL.blockCount.collectAsStateWithLifecycle()
    val hideN by FL.hideCount.collectAsStateWithLifecycle()
    val mine by FL.myRules.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nf = remember { java.text.NumberFormat.getIntegerInstance() }
    val df = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    var addOpen by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var rulesOpen by remember { mutableStateOf(false) }
    var rulesText by remember(mine) { mutableStateOf(mine) }
    Column(Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Active rules: ${nf.format(blockN + hideN)} · block ${nf.format(blockN)} · hide ${nf.format(hideN)}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(
            (if (updating) "Updating now…" else if (last > 0) "Updated " + df.format(java.util.Date(last)) else "Not downloaded yet") +
                " · \"hide\" rules also work inside the video players on the page. uBlock's special script rules are skipped.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Automatic update", fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("start" to "At every start", "daily" to "Daily", "weekly" to "Weekly", "manual" to "Manual").forEach { (k, l) -> Chip(l, update == k, { FL.setUpdate(k) }) }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(if (updating) "⟳ Updating…" else "⟳ Update now", { FL.updateNow(); Toast.makeText(context, "Updating the filter lists…", Toast.LENGTH_SHORT).show() })
            SmallButton("＋ Add list by link", { addOpen = !addOpen })
            SmallButton("✎ My rules" + (info["mine"]?.rules?.takeIf { it > 0 }?.let { " ($it)" } ?: ""), { rulesOpen = !rulesOpen })
        }
        if (addOpen) {
            TvTextField(
                value = newUrl, onValueChange = { newUrl = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://… filter list (EasyList / AdGuard / hosts)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = { PasteButton { newUrl = it } },
                onSubmit = { if (FL.addList(newUrl)) { newUrl = ""; addOpen = false } else Toast.makeText(context, "Paste a link starting with https://", Toast.LENGTH_SHORT).show() },
            )
            SmallButton("Add", { if (FL.addList(newUrl)) { newUrl = ""; addOpen = false } else Toast.makeText(context, "Paste a link starting with https://", Toast.LENGTH_SHORT).show() })
        }
        if (rulesOpen) {
            Text("One rule per line, e.g.  ads.example.com   ·   ||tracker.net^   ·   vidara.to##.ad-box   ·   ##.popup-banner",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TvTextField(value = rulesText, onValueChange = { rulesText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp), maxLines = 8)
            SmallButton("Save my rules", { FL.setMyRules(rulesText); Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() })
        }
        @Composable
        fun row(id: String, name: String, onRemove: (() -> Unit)? = null) {
            val on = enabled[id] ?: (FL.CATALOGUE.firstOrNull { it.id == id }?.defaultOn ?: true)
            val i = info[id]
            Row(verticalAlignment = Alignment.CenterVertically) {
                FocusBox(onClick = { FL.setOn(id, !on) }, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 13.sp)
                            val sub = when {
                                i == null || (i.bytes == 0L && i.error.isBlank()) -> if (on) "will download at the next update" else "off"
                                i.error.isNotBlank() && i.bytes == 0L -> "⚠ could not download (${i.error})"
                                else -> "${nf.format(i.rules)} rules · ${if (i.bytes >= 1048576) "%.1f MB".format(i.bytes / 1048576.0) else "${i.bytes / 1024} KB"}" +
                                    (if (i.updated > 0) " · " + df.format(java.util.Date(i.updated)) else "") + (if (i.error.isNotBlank()) " · ⚠ last update failed" else "")
                            }
                            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (on) "● On" else "○ Off", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF2EE66B) else Color(0xFF999999))
                    }
                }
                if (onRemove != null) SmallButton("✕", onRemove, warn = true)
            }
        }
        FL.CATALOGUE.groupBy { it.group }.forEach { (g, lists) ->
            Text(g, fontSize = 11.sp, color = Color(0xFF2EE66B), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            lists.forEach { row(it.id, it.name) }
        }
        if (custom.isNotEmpty()) {
            Text("MY LISTS", fontSize = 11.sp, color = Color(0xFF2EE66B), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            custom.forEach { u -> row("u:$u", Uri.parse(u).host ?: u) { FL.removeList(u) } }
        }
    }
}

@Composable
private fun SwitchRow(label: String, key: String, flags: Map<String, Boolean>) {
    val on = flags[key] ?: WebStore.FLAG_DEFAULTS[key] ?: false
    FocusBox(onClick = { WebStore.setFlag(key, !on) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (on) "● On" else "○ Off", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = if (on) Color(0xFF2EE66B) else Color(0xFF999999))
        }
    }
}

@Composable
private fun SectionTitleWeb(t: String) =
    Text(t, color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp))

/** All browser settings (⚙ in the Web tab and Settings › Films & downloads › Web browser). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WebSettingsContent(page: String? = null, onPick: (() -> Unit)? = null) {
    val adLevel by WebStore.adLevel.collectAsStateWithLifecycle()
    val kids by WebStore.kidsMode.collectAsStateWithLifecycle()
    val flags by WebStore.flags.collectAsStateWithLifecycle()
    val findVideos by WebStore.findVideos.collectAsStateWithLifecycle()
    val start by WebStore.startPage.collectAsStateWithLifecycle()
    val allow by WebStore.allowSites.collectAsStateWithLifecycle()
    val last by WebStore.lastExport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var resetOpen by remember { mutableStateOf(false) }
    if (resetOpen) ResetBrowserDialog { resetOpen = false }
    val nf = remember { java.text.NumberFormat.getIntegerInstance() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitleWeb("ADS & POP-UPS")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("off" to "Off", "normal" to "Normal", "strong" to "Strong", "maximum" to "Maximum").forEach { (k, l) ->
                Chip(l, adLevel == k, { WebStore.setAdLevel(k); AdBlock.ensure(k, kids) })
            }
        }
        Text(
            when (adLevel) {
                "maximum" -> "Maximum = ${nf.format(AdBlock.listSize + AdBlock.maxSize)} ad / tracker / pop-up sites + page-ad hiding + scripts that open windows or cover the page stopped."
                "strong" -> "Strong = ${nf.format(AdBlock.listSize)} ad & tracker sites + page-ad hiding."
                "normal" -> "Normal = ${nf.format(AdBlock.listSize)} ad & tracker sites."
                else -> "Off = no ad blocking (the switches below still apply)."
            } + " + Filter lists below. No browser can remove every ad: some sites serve ads from their own address.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var listsOpen by remember { mutableStateOf(false) }
        val flTotal by com.nova.iptv.data.FilterLists.blockCount.collectAsStateWithLifecycle()
        val flHide by com.nova.iptv.data.FilterLists.hideCount.collectAsStateWithLifecycle()
        FocusBox(onClick = { listsOpen = !listsOpen }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🛡 Filter lists", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${nf.format(flTotal + flHide)} rules  " + if (listsOpen) "▲" else "▼", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (listsOpen) FilterListsContent()
        SwitchRow("Block pop-ups, new tabs & overlays", "popups", flags)
        SwitchRow("Block redirects to other sites", "redirects", flags)
        SwitchRow("Block “open another app” (intent, Telegram, Play Store…)", "appJumps", flags)
        SwitchRow("Block downloads started by the site (files, APKs)", "siteDownloads", flags)
        SwitchRow("Show a short message when something is blocked", "blockMsg", flags)

        SectionTitleWeb("👶 KIDS PROTECTION")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("off" to "Off", "adult" to "Block adult & gambling sites", "bookmarks" to "Only my bookmarks").forEach { (k, l) ->
                Chip(l, kids == k, { WebStore.setKidsMode(k); AdBlock.ensure(adLevel, k) })
            }
        }
        Text(
            "Block adult & gambling = ${if (AdBlock.adultSize > 0) nf.format(AdBlock.adultSize) else "≈ 100 000"} adult / casino sites + adult words in addresses. " +
                "Only my bookmarks = children can open only the sites you saved. With kids protection on, pop-ups, other apps, site downloads, camera and location are always refused.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("🔒 PIN to open the Web tab", "pinOpen", flags)
        SwitchRow("🔒 PIN to change these settings / bookmarks", "pinSettings", flags)
        SwitchRow("Hide the address bar for children (bookmarks only)", "hideAddress", flags)

        SectionTitleWeb("PRIVACY & SAFETY")
        SwitchRow("Safe browsing (warning on dangerous sites)", "safeBrowsing", flags)
        SwitchRow("Camera / microphone / location — always refuse", "denyPerms", flags)
        SwitchRow("Cookies", "cookies", flags)
        SwitchRow("Private mode (no history or cookies kept)", "privateMode", flags)
        SwitchRow("JavaScript (off = safest, many video players stop)", "javascript", flags)
        SwitchRow("Block images (faster)", "blockImages", flags)

        SectionTitleWeb("PAGE & TABS")
        val newTab by WebStore.newTab.collectAsStateWithLifecycle()
        Text("Links that open a new tab", fontWeight = FontWeight.Medium, fontSize = 13.sp)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("same" to "Same page", "ask" to "Ask me each time", "tab" to "Always new tab").forEach { (k, l) -> Chip(l, newTab == k, { WebStore.setNewTab(k) }) }
        }
        Text("Only links you tap can open a tab (up to $MAX_TABS). Pop-ups opened by the page itself are always blocked. Every tab is filtered, " +
            "and ad, malicious, adult & gambling addresses are refused before any question. The question cancels itself after 10 s.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Retry buttons that don't react (reload the page and tap again)", "retryButtons", flags)
        val smartPlay by WebStore.smartPlay.collectAsStateWithLifecycle()
        Text("▶ Smart play for player buttons", fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("off" to "Off", "ask" to "Ask", "auto" to "Automatic").forEach { (k, l) -> Chip(l, smartPlay == k, { WebStore.setSmartPlay(k) }) }
        }
        Text("When you tap a page's server / player button, Nova checks the player's address (2–3 s) and opens it alone in a clean " +
            "player tab — no page scripts, no refresh needed — then offers ▶ Play in Nova / ⬇ Download. Back returns to the page.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitleWeb("PAGE & VIDEOS")
        SmallButton("🌐 Make Nova my default browser", {
            val opened = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    val rm = context.getSystemService(android.app.role.RoleManager::class.java)
                    if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
                        context.startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
                    } else if (rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
                        Toast.makeText(context, "Nova is already your default browser", Toast.LENGTH_SHORT).show(); true
                    } else false
                } else false
            }.getOrDefault(false)
            if (!opened) runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(context, "Open Android Settings › Apps › Default apps › Browser and choose Nova", Toast.LENGTH_LONG).show() }
        })
        Text("Nova also appears in “Open with” and Share for every web link: pages open here, video links play in Nova.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FocusBox(onClick = { WebStore.setFindVideos(!findVideos) }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text("Find videos on pages (🎬 list)", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(if (findVideos) "● On" else "○ Off", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (findVideos) Color(0xFF2EE66B) else Color(0xFF999999))
            }
        }
        SwitchRow("Look like a normal browser (sites that refuse “app browsers”)", "normalBrowser", flags)
        SwitchRow("Desktop site", "desktop", flags)
        SwitchRow("Dark pages", "dark", flags)
        Text("Start page", fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("bookmarks" to "Bookmarks", "last" to "Last page", "search" to "Search").forEach { (k, l) -> Chip(l, start == k, { WebStore.setStartPage(k) }) }
        }
        Text("Sites where blocking is off", fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        page?.let { p ->
            val s = AdBlock.siteOf(Uri.parse(p).host)
            if (s.isNotBlank()) SmallButton(if (s in allow) "🛡 Turn blocking ON for $s" else "🛡 Turn blocking OFF for $s", { WebStore.toggleAllowSite(s); WebState.webView?.reload() })
        }
        if (allow.isEmpty()) Text("none", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        allow.sorted().forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s, fontSize = 13.sp, modifier = Modifier.weight(1f))
                SmallButton("✕", { WebStore.toggleAllowSite(s) })
            }
        }
        Text(
            "Bookmarks and these settings are saved automatically in Download/NovaIPTV/Browser/browser-latest.novaweb" +
                (if (last > 0) " (last " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(last)) + ")" else "") +
                " — a new installation takes them back. History, cookies and passwords are never saved there.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp),
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("⤓ Export now", {
                CoroutineScope(Dispatchers.IO).launch {
                    val w = runCatching { WebStore.export() }.getOrDefault("")
                    Handler(Looper.getMainLooper()).post { Toast.makeText(context, if (w.isNotEmpty()) "Saved: $w" else "Could not save — use Import once to choose the NovaIPTV folder", Toast.LENGTH_LONG).show() }
                }
            })
            if (onPick != null) SmallButton("⤒ Import (choose the NovaIPTV folder)", onPick)
            SmallButton("↺ Reset browser configuration", { resetOpen = true }, warn = true)
            SmallButton("🗑 History & cookies", {
                WebState.webView?.clearHistory(); CookieManager.getInstance().removeAllCookies(null); WebState.webView?.clearCache(true)
                Toast.makeText(context, "History and cookies cleared", Toast.LENGTH_SHORT).show()
            })
        }
    }
}

/** Settings › Films & downloads › Web browser. */
@Composable
fun WebSettings() {
    val adLevel by WebStore.adLevel.collectAsStateWithLifecycle()
    val kids by WebStore.kidsMode.collectAsStateWithLifecycle()
    val bookmarks by WebStore.bookmarks.collectAsStateWithLifecycle()
    SettingsCard("Web browser", summary = "ads: $adLevel · kids: $kids · ${bookmarks.size} bookmarks") {
        WebSettingsContent()
    }
}

/** ↺ Reset browser configuration: settings only (bookmarks kept) or everything. 10 s → Cancel; PIN when set. */
@Composable
private fun ResetBrowserDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var left by remember { mutableIntStateOf(10) }
    LaunchedEffect(Unit) { while (left > 0) { kotlinx.coroutines.delay(1000); left-- }; onClose() }
    val hasPin = (context.applicationContext as NovaApp).storage.hasPin
    fun doReset(all: Boolean) {
        onClose()
        PinGate.require(hasPin) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { WebStore.export() } // a dated copy of the current file first
                WebStore.reset(all)
                Handler(Looper.getMainLooper()).post {
                    if (all) {
                        WebState.tabs.drop(1).forEach { t -> (t.parent as? ViewGroup)?.removeView(t); runCatching { t.destroy() } }
                        WebState.tabs.firstOrNull()?.let { WebState.tabs.retainAll(listOf(it)); it.clearHistory(); it.clearCache(true) }
                        WebState.opener.clear(); WebState.playerTabs.clear(); WebState.offered.clear()
                        CookieManager.getInstance().removeAllCookies(null)
                        WebState.tabsVersion.value++
                    }
                    Toast.makeText(context, if (all) "Web browser reset: everything" else "Web browser settings reset (bookmarks kept)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(2.dp, Color(0xFFFF8A80), RoundedCornerShape(14.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("↺ Reset the web browser?", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Back to Nova's defaults: ads Maximum · kids protection on · balanced filter lists · new tabs: Ask me each time · Smart play: Ask.",
                fontSize = 12.sp, color = Color(0xFFAAAAAA))
            Box(Modifier.fillMaxWidth().background(Color(0xFF262B33), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(left / 10f).heightIn(min = 5.dp).background(Color(0xFF2EE66B), RoundedCornerShape(3.dp)))
            }
            Text("Cancel in $left s", fontSize = 11.sp, color = Color(0xFF999999))
            FocusBox(onClick = { doReset(false) }, modifier = Modifier.fillMaxWidth().background(Color(0xFF2EE66B), RoundedCornerShape(10.dp))) {
                Text("↺  Settings only (keep my bookmarks)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00210B),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            FocusBox(onClick = { doReset(true) }, modifier = Modifier.fillMaxWidth()) {
                Text("↺  Everything (settings, bookmarks, tabs, history, cookies)", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            FocusBox(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("✕  Cancel", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            Text("🔒 Asks for the PIN if one is set. A copy of the current browser file is kept in Download/NovaIPTV/Browser first.",
                fontSize = 11.sp, color = Color(0xFF888888))
        }
    }
}

/**
 * A vertical scroll bar beside the page, easy with a normal remote: ⤒ top · ▲ page up (hold = keeps going) ·
 * the position · ▼ page down · ⤓ bottom. On a touch screen the position bar can also be dragged.
 */
@Composable
private fun WebScrollBar(web: WebView) {
    var progress by remember { mutableStateOf(0f) }
    var scrollable by remember { mutableStateOf(true) }
    LaunchedEffect(web) {
        while (true) {
            @Suppress("DEPRECATION")
            val range = web.contentHeight * web.scale - web.height
            scrollable = range > 8
            progress = if (range > 0) (web.scrollY / range).coerceIn(0f, 1f) else 0f
            kotlinx.coroutines.delay(300)
        }
    }
    fun by(fraction: Float) {
        // Works for pages that scroll themselves and for sites that scroll an inner box.
        web.evaluateJavascript("(function(){var d=innerHeight*$fraction;var e=document.scrollingElement||document.documentElement;" +
            "var b=e.scrollHeight>innerHeight+8?null:[].slice.call(document.querySelectorAll('div,main,section')).find(function(x){var s=getComputedStyle(x);" +
            "return (s.overflowY=='auto'||s.overflowY=='scroll')&&x.scrollHeight>x.clientHeight+8;});if(b)b.scrollBy(0,d);else window.scrollBy(0,d);})()", null)
    }
    fun to(p: Float) {
        web.evaluateJavascript("(function(){var e=document.scrollingElement||document.documentElement;window.scrollTo(0,(e.scrollHeight-innerHeight)*$p);})()", null)
    }
    if (!scrollable) return
    Column(
        Modifier.fillMaxHeight().widthIn(min = 46.dp).background(MaterialTheme.colorScheme.surface).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SmallButton("⤒", { to(0f) })
        RepeatButton("▲") { by(-0.85f) }
        BoxWithConstraints(
            Modifier.weight(1f).width(14.dp).background(Color(0xFF262B33), RoundedCornerShape(7.dp))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ -> to((change.position.y / size.height).coerceIn(0f, 1f)) }
                },
        ) {
            val thumb = 44.dp
            Box(
                Modifier.padding(top = (maxHeight - thumb) * progress).width(14.dp).height(thumb)
                    .background(Color(0xFF2EE66B), RoundedCornerShape(7.dp)),
            )
        }
        RepeatButton("▼") { by(0.85f) }
        SmallButton("⤓", { to(1f) })
    }
}
