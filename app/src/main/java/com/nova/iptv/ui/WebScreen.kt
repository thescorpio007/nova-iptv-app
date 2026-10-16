package com.nova.iptv.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** A video found on the page: plays in Nova's player with the page's details (Referer, cookies, browser name). */
data class FoundVideo(val url: String, val label: String, val kind: String, val page: String, val protectedDrm: Boolean = false)

/** The web page being shown (kept while you move to other tabs). */
private object WebState {
    @Volatile var pageUrl: String = ""
    @Volatile var pageSite: String = ""
    var webView: WebView? = null
}

private val VIDEO_EXT = Regex("""\.(m3u8|mp4|mpd|webm|mkv|m4v|mov)(\?|$|#)""", RegexOption.IGNORE_CASE)
private val DRM_HINT = Regex("""widevine|playready|fairplay|/license|drm""", RegexOption.IGNORE_CASE)
private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
private val QUALITY_IN_URL = Regex("""(?<!\d)(2160|1440|1080|720|576|480|360|240)p?(?!\d)""")
private val SEARCH = "https://duckduckgo.com/?q="

/** 🌐 Web: a small browser with strong ad blocking that finds the page's video and plays it in Nova. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as NovaApp
    val main = remember { Handler(Looper.getMainLooper()) }
    val bookmarks by WebStore.bookmarks.collectAsStateWithLifecycle()
    val adLevel by WebStore.adLevel.collectAsStateWithLifecycle()
    val findVideos by WebStore.findVideos.collectAsStateWithLifecycle()
    val allow by WebStore.allowSites.collectAsStateWithLifecycle()
    val offer by WebStore.restoreOffer.collectAsStateWithLifecycle()

    var address by remember { mutableStateOf(WebState.pageUrl) }
    var title by remember { mutableStateOf("") }
    var showPage by remember { mutableStateOf(WebState.pageUrl.isNotBlank()) }
    var canBack by remember { mutableStateOf(false) }
    var blocked by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateListOf<FoundVideo>() }
    var panelClosed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var fullView by remember { mutableStateOf<View?>(null) }
    var fullCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val pickTree = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) CoroutineScope(Dispatchers.IO).launch {
            val n = WebStore.setTree(uri)
            main.post { Toast.makeText(context, if (n != null) "Browser restored: $n bookmarks" else "No browser file found in that folder", Toast.LENGTH_LONG).show() }
        }
    }

    fun site(): String = AdBlock.siteOf(WebState.pageSite)
    fun blocking(): Boolean = WebStore.adLevel.value != "off" && site() !in WebStore.allowSites.value
    fun showNote(t: String) = main.post { note = t; main.postDelayed({ if (note == t) note = null }, 4000) }

    fun addFound(v: FoundVideo) = main.post {
        if (found.none { it.url == v.url } && found.size < 12) { found += v; panelClosed = false }
    }

    /** An HLS master list: one line per quality (1080p, 720p…). */
    fun inspectHls(url: String, page: String, headers: Map<String, String>) {
        CoroutineScope(Dispatchers.IO).launch {
            val text = runCatching {
                val req = okhttp3.Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                app.http.newCall(req).execute().use { it.body?.string().orEmpty() }
            }.getOrNull() ?: return@launch
            val protectedDrm = text.contains("SAMPLE-AES") || text.contains("KEYFORMAT=", ignoreCase = true)
            if (text.contains("#EXT-X-STREAM-INF")) {
                val lines = text.lines()
                lines.forEachIndexed { i, l ->
                    if (!l.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@forEachIndexed
                    val h = RESOLUTION.find(l)?.groupValues?.get(1)
                    val v = runCatching { java.net.URI(url).resolve(next.trim()).toString() }.getOrNull() ?: return@forEachIndexed
                    addFound(FoundVideo(v, h?.let { "${it}p" } ?: "HLS", "HLS", page, protectedDrm))
                }
            } else if (protectedDrm) {
                main.post { found.replaceAll { if (it.url == url) it.copy(protectedDrm = true) else it } }
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
        showPage = true
        address = url
        WebState.webView?.loadUrl(url)
    }

    val webView = remember {
        WebState.webView ?: WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            CookieManager.getInstance().setAcceptCookie(true)
            WebState.webView = this
        }
    }

    // The callbacks read the current Compose state through these lambdas.
    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                val host = u.host
                if (!request.isForMainFrame && blocking() && AdBlock.isAd(host) && AdBlock.siteOf(host) != site()) {
                    main.post { blocked++ }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (WebStore.findVideos.value) {
                    val s = u.toString()
                    if (DRM_HINT.containsMatchIn(s) && (request.method == "POST" || s.contains("license", true))) {
                        main.post { found.replaceAll { it.copy(protectedDrm = true) } }
                    }
                    val m = VIDEO_EXT.find(u.path.orEmpty())
                    if (m != null) {
                        val ext = m.groupValues[1].lowercase()
                        val page = WebState.pageUrl
                        val q = QUALITY_IN_URL.find(s)?.groupValues?.get(1)?.let { "${it}p" }
                        val kind = when (ext) { "m3u8" -> "HLS"; "mpd" -> "DASH"; else -> ext.uppercase() }
                        addFound(FoundVideo(s, q ?: kind, kind, page, protectedDrm = ext == "mpd"))
                        if (ext == "m3u8") {
                            val hdr = HashMap<String, String>()
                            request.requestHeaders?.forEach { (k, v) -> if (!k.equals("Range", true)) hdr[k] = v }
                            CookieManager.getInstance().getCookie(s)?.let { hdr["Cookie"] = it }
                            inspectHls(s, page, hdr)
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
                    if (WebStore.adLevel.value != "off") { main.post { blocked++ }; showNote("🚫 Blocked: the page tried to open another app ($scheme)"); return true }
                    return false
                }
                if (request.isForMainFrame && !request.hasGesture() && WebStore.adLevel.value == "strong" &&
                    site() !in WebStore.allowSites.value && site().isNotEmpty() && AdBlock.siteOf(u.host) != site()
                ) {
                    main.post { blocked++ }
                    showNote("🚫 Redirect blocked: the page tried to send you to ${u.host}")
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val newSite = AdBlock.siteOf(Uri.parse(url).host)
                if (newSite != AdBlock.siteOf(WebState.pageSite)) { blocked = 0 }
                WebState.pageUrl = url
                WebState.pageSite = Uri.parse(url).host.orEmpty()
                address = url
                found.clear()
                panelClosed = false
                canBack = view.canGoBack()
            }

            override fun onPageFinished(view: WebView, url: String) {
                canBack = view.canGoBack()
                WebStore.lastUrl = url
                if (blocking()) {
                    val js = "(function(){var s=document.createElement('style');s.innerHTML=\"" + AdBlock.HIDE_CSS.replace("\"", "\\\"") +
                        "\";(document.head||document.documentElement).appendChild(s);})();"
                    view.evaluateJavascript(js, null)
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (WebStore.adLevel.value != "off" && site() !in WebStore.allowSites.value) {
                    blocked++
                    showNote("🚫 Pop-up blocked")
                    return false
                }
                // Blocking off: open the new window's address in this same page.
                val temp = WebView(view.context)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        view.loadUrl(r.url.toString()); v.destroy(); return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(v: View, callback: CustomViewCallback) { fullView = v; fullCallback = callback }
            override fun onHideCustomView() { fullView = null; fullCallback = null }
        }
        onDispose { }
    }

    BackHandler(enabled = fullView != null) { fullCallback?.onCustomViewHidden(); fullView = null }
    BackHandler(enabled = fullView == null && showPage && canBack) { webView.goBack() }
    BackHandler(enabled = fullView == null && showPage && !canBack) { showPage = false }

    // First visit: start page.
    remember {
        if (WebState.pageUrl.isBlank()) when (WebStore.startPage.value) {
            "last" -> WebStore.lastUrl.takeIf { it.isNotBlank() }?.let { main.post { go(it) } }
            "search" -> main.post { go("https://duckduckgo.com/") }
        }
        true
    }

    fun play(v: FoundVideo, download: Boolean) {
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
        val name = title.ifBlank { pageUri.host ?: "Video" }.let { if (v.label.isNotBlank() && v.label != v.kind) "$it (${v.label})" else it }
        if (download) LinkActions.download(context, v.url, name) else LinkActions.play(context, v.url, name)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallButton("←", { if (showPage && canBack) webView.goBack() else showPage = false })
                SmallButton("→", { if (webView.canGoForward()) { showPage = true; webView.goForward() } })
                SmallButton("⟳", { if (showPage) webView.reload() })
                TvTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search or type a web address") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    trailingIcon = { PasteButton { go(it) } },
                    onSubmit = { go(address) },
                )
                Text(
                    if (adLevel == "off" || site() in allow) "🛡 off" else "🛡 $blocked",
                    fontSize = 13.sp, color = Color(0xFFBFF5D3),
                    modifier = Modifier.background(Color(0xFF0F2A1C), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                )
                val marked = bookmarks.any { it.url == WebState.pageUrl }
                SmallButton(if (marked) "★" else "☆", {
                    if (showPage && WebState.pageUrl.isNotBlank()) WebStore.toggleBookmark(title, WebState.pageUrl)
                })
                SmallButton("⋮", { menu = !menu })
            }
            note?.let {
                Text(it, fontSize = 12.sp, color = Color(0xFFFFD08A),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF2A2112)).padding(horizontal = 12.dp, vertical = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!showPage) StartPage(bookmarks, offer, onOpen = { go(it) }, onRestore = {
                    pickTree.launch(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FNovaIPTV"))
                })
            }
        }

        // ---- videos found on the page
        if (showPage && found.isNotEmpty() && !panelClosed && findVideos) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(14.dp).widthIn(max = 460.dp)
                    .background(Color(0xF00A0E14), RoundedCornerShape(14.dp))
                    .border(2.dp, Color(0xFF2EE66B), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("▶ Play in Nova — ${found.size} video${if (found.size > 1) "s" else ""} found", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(found.toList()) { _, v ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FocusBox(onClick = { play(v, download = false) }, modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (v.protectedDrm) "🔒 " else "🎬 ") + (title.ifBlank { "Video" }) + "  ·  " + v.label +
                                        (if (v.label != v.kind) " · ${v.kind}" else ""),
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                            if (!v.protectedDrm && v.kind != "DASH") SmallButton("⬇", { play(v, download = true) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Plays with Nova's player. 🔒 = protected: only on the website.", fontSize = 11.sp, color = Color(0xFF999999), modifier = Modifier.weight(1f))
                    SmallButton("✕", { panelClosed = true })
                }
            }
        }

        // ---- menu
        if (menu) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 8.dp).widthIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                fun item(t: String, f: () -> Unit) = @Composable {
                    FocusBox(onClick = { menu = false; f() }, modifier = Modifier.fillMaxWidth()) {
                        Text(t, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
                item("★ Bookmarks (start page)") { showPage = false }()
                if (showPage && site().isNotEmpty()) item(
                    if (site() in allow) "🛡 Turn blocking ON for ${site()}" else "🛡 Turn blocking OFF for ${site()}",
                ) { WebStore.toggleAllowSite(site()); webView.reload() }()
                if (showPage && found.isNotEmpty()) item("▶ Show the videos found") { panelClosed = false }()
                item("⤓ Export bookmarks & settings now") {
                    CoroutineScope(Dispatchers.IO).launch {
                        val w = runCatching { WebStore.export() }.getOrDefault("")
                        main.post { Toast.makeText(context, if (w.isNotEmpty()) "Saved: $w" else "Could not save — choose the NovaIPTV folder once (⋮ → Import)", Toast.LENGTH_LONG).show() }
                    }
                }()
                item("⤒ Import… (choose the NovaIPTV folder)") {
                    pickTree.launch(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FNovaIPTV"))
                }()
                item("🗑 Clear history & cookies") {
                    webView.clearHistory(); CookieManager.getInstance().removeAllCookies(null); webView.clearCache(true)
                    Toast.makeText(context, "History and cookies cleared", Toast.LENGTH_SHORT).show()
                }()
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
private fun StartPage(bookmarks: List<WebStore.Bookmark>, offer: Boolean, onOpen: (String) -> Unit, onRestore: () -> Unit) {
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
                    SmallButton("▲", { WebStore.moveBookmark(b, -1) })
                    SmallButton("▼", { WebStore.moveBookmark(b, 1) })
                    SmallButton("✕", { WebStore.removeBookmark(b) }, warn = true)
                }
            }
        }
    }
}

/** Settings › Films & downloads › Web browser. */
@Composable
fun WebSettings() {
    val adLevel by WebStore.adLevel.collectAsStateWithLifecycle()
    val findVideos by WebStore.findVideos.collectAsStateWithLifecycle()
    val start by WebStore.startPage.collectAsStateWithLifecycle()
    val allow by WebStore.allowSites.collectAsStateWithLifecycle()
    val bookmarks by WebStore.bookmarks.collectAsStateWithLifecycle()
    val last by WebStore.lastExport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsCard("Web browser", summary = "ads: $adLevel · ${bookmarks.size} bookmarks") {
        Text("Ad blocking", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("strong" to "Strong", "normal" to "Normal", "off" to "Off").forEach { (k, l) -> Chip(l, adLevel == k, { WebStore.setAdLevel(k) }) }
        }
        Text(
            "Strong = ad & tracker lists (${java.text.NumberFormat.getIntegerInstance().format(AdBlock.listSize)} sites) + pop-ups, redirects " +
                "and “open app” jumps blocked. Normal = the lists and pop-ups only. Lists update weekly.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Find videos on pages", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("On — show “Play in Nova”", findVideos, { WebStore.setFindVideos(true) })
            Chip("Off", !findVideos, { WebStore.setFindVideos(false) })
        }
        Text("Start page", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("bookmarks" to "Bookmarks", "last" to "Last page", "search" to "Search").forEach { (k, l) -> Chip(l, start == k, { WebStore.setStartPage(k) }) }
        }
        Text("Sites where blocking is off", fontWeight = FontWeight.Medium)
        if (allow.isEmpty()) Text("none · add one from ⋮ on a page that doesn't work", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("⤓ Export now", {
                CoroutineScope(Dispatchers.IO).launch {
                    val w = runCatching { WebStore.export() }.getOrDefault("")
                    Handler(Looper.getMainLooper()).post { Toast.makeText(context, if (w.isNotEmpty()) "Saved: $w" else "Could not save", Toast.LENGTH_LONG).show() }
                }
            })
            SmallButton("🗑 Clear history & cookies", {
                WebState.webView?.clearHistory(); CookieManager.getInstance().removeAllCookies(null); WebState.webView?.clearCache(true)
                Toast.makeText(context, "History and cookies cleared", Toast.LENGTH_SHORT).show()
            })
        }
    }
}
