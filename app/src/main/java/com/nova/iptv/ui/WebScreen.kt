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
    fun level(): String = if (site() in WebStore.allowSites.value) "off" else WebStore.adLevel.value
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

    val webView = remember {
        WebState.webView ?: WebView(context).apply {
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(android.graphics.Color.WHITE)
            WebState.webView = this
        }
    }
    val defaultUa = remember { WebSettings.getDefaultUserAgent(context) }

    // Settings that change how pages are loaded.
    LaunchedEffect(flags) {
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

    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                val host = u.host
                val empty = { WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) }
                if (!request.isForMainFrame) {
                    if (WebStore.kidsMode.value != "off" && AdBlock.isAdult(host, u.toString())) { main.post { blocked++ }; return empty() }
                    if (AdBlock.isAd(host, level()) && AdBlock.siteOf(host) != site()) { main.post { blocked++ }; return empty() }
                }
                if (WebStore.findVideos.value) {
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
                if (request.isForMainFrame && !request.hasGesture() && WebStore.flag("redirects") && level() != "off" &&
                    site().isNotEmpty() && AdBlock.siteOf(u.host) != site()
                ) {
                    main.post { blocked++ }
                    showNote("🚫 Redirect blocked: the page tried to send you to ${u.host}")
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val newSite = AdBlock.siteOf(Uri.parse(url).host)
                if (newSite != AdBlock.siteOf(WebState.pageSite)) blocked = 0
                WebState.pageUrl = url
                WebState.pageSite = Uri.parse(url).host.orEmpty()
                address = url
                found.clear()
                seen.clear()
                listOpen = false
                canBack = view.canGoBack()
            }

            override fun onPageFinished(view: WebView, url: String) {
                canBack = view.canGoBack()
                if (!WebStore.flag("privateMode")) WebStore.lastUrl = url
                val lv = level()
                if (lv != "off") {
                    val js = "(function(){var s=document.createElement('style');s.innerHTML=\"" + AdBlock.HIDE_CSS.replace("\"", "\\\"") +
                        "\";(document.head||document.documentElement).appendChild(s);})();"
                    view.evaluateJavascript(js, null)
                    if (lv == "maximum") view.evaluateJavascript(AdBlock.MAX_JS, null)
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (WebStore.flag("popups") && level() != "off") {
                    blocked++
                    showNote("🚫 Pop-up / new tab blocked")
                    return false
                }
                // Allowed: open the new window's address in this same page.
                val temp = WebView(view.context)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        go(r.url.toString()); v.destroy(); return true
                    }
                }
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
            if (WebStore.flag("siteDownloads") || WebStore.kidsMode.value != "off" || url.endsWith(".apk", true) || mime == "application/vnd.android.package-archive") {
                blocked++
                showNote("🚫 Download started by the site blocked")
            } else {
                choose = FoundVideo(url, mime.substringAfter('/').uppercase().take(5), WebState.pageUrl, info = "file")
            }
        }
        onDispose {
            if (WebStore.flag("privateMode")) { CookieManager.getInstance().removeAllCookies(null); webView.clearHistory() }
        }
    }

    BackHandler(enabled = fullView != null) { fullCallback?.onCustomViewHidden(); fullView = null }
    BackHandler(enabled = fullView == null && (listOpen || settingsOpen)) { listOpen = false; settingsOpen = false }
    BackHandler(enabled = fullView == null && !listOpen && !settingsOpen && showPage && canBack) { webView.goBack() }
    // At the first page / the bookmarks: Back twice (within 2 s) leaves the Web tab.
    var lastBack by remember { mutableStateOf(0L) }
    BackHandler(enabled = fullView == null && !listOpen && !settingsOpen && !(showPage && canBack)) {
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
            else -> LinkActions.download(context, v.url, name)
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
                Chip("🎬 ${found.size}", found.isNotEmpty(), { listOpen = !listOpen })
                Text(
                    if (adLevel == "off" || site() in allow) "🛡 off" else "🛡 $blocked",
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
                if (showPage) AndroidView(
                    factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                )
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
            } + " Lists update weekly. No browser can remove every ad: some sites serve ads from their own address.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
