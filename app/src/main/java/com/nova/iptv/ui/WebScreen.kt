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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
) {
    val quality: String get() = when { protectedDrm -> "🔒"; height > 0 -> "${height}p"; else -> "?" }
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
}

private const val MAX_TABS = 6
private val SAFE_HOST = Regex("[^A-Za-z0-9.-]")
private val SAFE_HOST_PORT = Regex("[^A-Za-z0-9.:-]")
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
            WebState.frameScript = true
        }
    }
}

/** Blocking level for the page being shown ("off" on the sites where blocking is turned off). */
private fun pageLevel(): String = if (AdBlock.siteOf(WebState.pageSite) in WebStore.allowSites.value) "off" else WebStore.adLevel.value

/** Called by the page script in every frame (the web engine's own thread). */
private object FilterBridge {
    @android.webkit.JavascriptInterface fun level(): String = pageLevel()
    @android.webkit.JavascriptInterface fun css(host: String?): String = runCatching { com.nova.iptv.data.FilterLists.cssFor(host) }.getOrDefault("")
    @android.webkit.JavascriptInterface fun base(): String = if (pageLevel() == "normal") "" else AdBlock.HIDE_CSS
    @android.webkit.JavascriptInterface fun removed(n: Int) { WebState.removed.value += n }
    @android.webkit.JavascriptInterface fun retry(host: String?) { host?.let { WebStore.clearBad(it) } }
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
        } else if (found.size < 30) found += v
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
        WebState.webView?.loadUrl(url)
    }

    var webView by remember {
        mutableStateOf(WebState.webView ?: newWebView(context).also { WebState.webView = it; WebState.tabs += it })
    }
    if (webView !in WebState.tabs) WebState.tabs.add(0, webView)
    val tabsVersion by WebState.tabsVersion.collectAsStateWithLifecycle()
    var tabsOpen by remember { mutableStateOf(false) }
    var askTab by remember { mutableStateOf<String?>(null) }
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
        title = WebState.titles[wv].orEmpty()
        found.clear(); seen.clear(); blocked = 0; WebState.removed.value = 0
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
        if (wv === webView) {
            val next = back ?: WebState.tabs.lastOrNull() ?: newWebView(context).also { applySettings(it); attachRef(it); WebState.tabs += it }
            switchTo(next)
        }
        (wv.parent as? ViewGroup)?.removeView(wv)
        runCatching { wv.stopLoading(); wv.destroy() }
        WebState.tabsVersion.value++
    }
    fun openTab(url: String?) {
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
        if (url != null) go(url)
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
                    // A video host the internet line refused: a Nova message instead of a page that never loads.
                    if (WebStore.isBad(u.authority) && request.requestHeaders?.get("Accept")?.contains("text/html") == true) return blockedHostPage(u.authority.orEmpty())
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
                if (request.isForMainFrame && !request.hasGesture() && WebStore.flag("redirects") && level() != "off" &&
                    site().isNotEmpty() && AdBlock.siteOf(u.host) != site()
                ) {
                    main.post { blocked++ }
                    showNote("🚫 Redirect blocked: the page tried to send you to ${u.host}")
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (Build.VERSION.SDK_INT < 23 || request.isForMainFrame) return
                if (request.requestHeaders?.get("Accept")?.contains("text/html") != true) return
                if (error.errorCode !in LINE_ERRORS) return
                val host = request.url.host?.lowercase() ?: return
                val key = request.url.authority?.lowercase() ?: return
                // Never the page's own address (only the video hosts it embeds).
                if (key == Uri.parse(WebState.urls[view].orEmpty()).authority?.lowercase()) return
                if (WebStore.isBad(key)) return
                WebStore.markBad(key)
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
                address = url
                found.clear()
                seen.clear()
                listOpen = false
                canBack = view.canGoBack()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (current()) canBack = view.canGoBack()
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
    BackHandler(enabled = fullView == null && !listOpen && !settingsOpen && showPage && canBack) { webView.goBack() }
    // A tab opened from another one: Back at its first page closes it and returns to the tab it came from.
    BackHandler(enabled = fullView == null && !listOpen && !settingsOpen && !(showPage && canBack) && WebState.opener[webView]?.let { it in WebState.tabs } == true) {
        closeTab(webView)
    }
    // At the first page / the bookmarks: Back twice (within 2 s) leaves the Web tab.
    var lastBack by remember { mutableStateOf(0L) }
    BackHandler(enabled = fullView == null && !listOpen && !settingsOpen && !(showPage && canBack) && WebState.opener[webView]?.let { it in WebState.tabs } != true) {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) onLeave()
        else { lastBack = now; Toast.makeText(context, "Press Back again to leave Web", Toast.LENGTH_SHORT).show() }
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
        val name = title.ifBlank { pageUri.host ?: "Video" }.substringBefore(" » ").let { if (v.height > 0) "$it (${v.height}p)" else it }
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
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TvTextField(
                        value = address, onValueChange = { address = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search or type a web address") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        trailingIcon = { PasteButton { go(it) } },
                        onSubmit = { go(address) },
                    )
                }
            }
            // ---- row 3: the buttons
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallButton("←", { if (showPage && canBack) webView.goBack() else if (showPage) showPage = false })
                SmallButton("→", { if (webView.canGoForward()) { showPage = true; webView.goForward() } })
                SmallButton("⟳", { if (showPage) webView.reload() })
                val marked = bookmarks.any { it.url == WebState.pageUrl }
                SmallButton(if (marked) "★" else "☆", {
                    if (showPage && WebState.pageUrl.isNotBlank()) {
                        if (WebStore.flag("pinSettings")) PinGate.require(hasPin) { WebStore.toggleBookmark(title, WebState.pageUrl) }
                        else WebStore.toggleBookmark(title, WebState.pageUrl)
                    }
                })
                SmallButton("★ List", { showPage = false; listOpen = false })
                key(tabsVersion) { SmallButton("▢ ${WebState.tabs.size}", { tabsOpen = true }) }
                Chip("🎬 ${found.size}", found.isNotEmpty(), { listOpen = !listOpen })
                Text(
                    if (adLevel == "off" || site() in allow) "🛡 off" else "🛡 ${blocked + removedBoxes}",
                    fontSize = 13.sp, color = Color(0xFFBFF5D3),
                    modifier = Modifier.background(Color(0xFF0F2A1C), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                )
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
                if (showPage) key(webView) {
                    AndroidView(
                        factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                    )
                }
                if (!showPage) StartPage(bookmarks, offer, onOpen = { go(it) }, onRestore = { pickTree.launch(Uri.parse(NOVA_FOLDER)) },
                    canEdit = { f -> if (WebStore.flag("pinSettings")) PinGate.require(hasPin, f) else f() })
            }
        }

        // ---- 🎬 videos found on the page
        if (listOpen) {
            val list = found.sortedWith(compareBy<FoundVideo> { it.protectedDrm }.thenByDescending { it.height }.thenBy { it.kind != "HLS" })
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 720.dp)
                    .background(Color(0xF815191F), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .border(2.dp, Color(0xFF2EE66B), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎬 Videos found on this page (${list.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    SmallButton("✕", { listOpen = false })
                }
                Text("Best first · tap one → Play / Download & watch / Download", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                if (list.isEmpty()) Text("No video found yet — start the video on the page, then look again.", fontSize = 13.sp, color = Color(0xFFAAAAAA))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(list) { _, v ->
                        FocusBox(onClick = { if (v.protectedDrm) act(v, 0) else choose = v }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    v.quality, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    color = if (v.height >= 1080) Color(0xFF00210B) else Color.White,
                                    modifier = Modifier.background(
                                        when { v.height >= 1080 -> Color(0xFF2EE66B); v.height >= 720 -> Color(0xFF3D6FB8); else -> Color(0xFF555555) },
                                        RoundedCornerShape(5.dp),
                                    ).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                                Text(
                                    "  " + title.ifBlank { "Video" }.substringBefore(" » "),
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Text(if (v.protectedDrm) "only on the website" else v.info.ifBlank { v.kind }, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                            }
                        }
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
                    Text("[${v.quality}] " + title.ifBlank { "Video" }.substringBefore(" » "), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text((v.info.ifBlank { v.kind }) + " · from " + (Uri.parse(v.page).host ?: ""), fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    listOf("▶  Play now", "⬇▶  Download & watch", "⬇  Download only").forEachIndexed { i, label ->
                        FocusBox(onClick = { choose = null; listOpen = false; act(v, i) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                    }
                    FocusBox(onClick = { choose = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel", fontSize = 14.sp, color = Color(0xFFAAAAAA), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
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
                                        Text(WebState.titles[t].orEmpty().ifBlank { if (u.isBlank()) "New tab" else Uri.parse(u).host ?: u },
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
