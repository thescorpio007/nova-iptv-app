package com.nova.iptv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.iptv.NovaApp
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.SavedItem
import com.nova.iptv.download.DownloadCenter
import com.nova.iptv.download.HlsDownload
import com.nova.iptv.player.PlayItem
import com.nova.iptv.player.PlaybackQueue
import com.nova.iptv.player.PlayerActivity

/**
 * Any video link (mp4, mkv, ts, m3u8, m3u…) from the "Open link" tab or from Share: play it, download it, or
 * download & watch it — like a film, but never saved as an account.
 */
object LinkActions {
    private val URL = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** The first web link inside shared text ("Look at this: https://…"). */
    fun findLink(text: String?): String? = text?.let { URL.find(it)?.value?.trimEnd('.', ',', ')', ']') }

    fun title(url: String): String {
        val path = Uri.parse(url).path.orEmpty().trimEnd('/')
        return Uri.decode(path.substringAfterLast('/')).ifBlank { Uri.parse(url).host ?: "Video" }
    }

    private fun ext(url: String): String = when {
        HlsDownload.isHls(url) -> "ts"
        else -> url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mpg", "mpeg", "flv", "wmv") } ?: "mp4"
    }

    fun remember(context: Context, url: String) {
        val s = (context.applicationContext as NovaApp).storage
        s.recentLinks = listOf("${System.currentTimeMillis()}|$url") + s.recentLinks.filter { it.substringAfter('|') != url }
    }

    /** What a link really is, found by asking the website for its first bytes. */
    enum class Kind { FILE, HLS_FILM, HLS_LIVE, M3U_LIST, WEB_PAGE, PROTECTED, REFUSED, UNKNOWN }
    class Probe(val kind: Kind, val message: String? = null)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun toast(context: Context, text: String) =
        main.post { android.widget.Toast.makeText(context.applicationContext, text, android.widget.Toast.LENGTH_LONG).show() }

    /** Checks the link before playing / downloading it (blocking: background thread). */
    fun probe(context: Context, url: String): Probe {
        if (!url.startsWith("http", ignoreCase = true)) return Probe(Kind.FILE)
        val app = context.applicationContext as NovaApp
        // whole = the full text (an .m3u8 list of a long film is several hundred KB: its end mark is at the end).
        fun fetch(u: String, whole: Boolean = false): Pair<okhttp3.Response, String> {
            val max = if (whole) 8 * 1024 * 1024 else 65536
            val req = okhttp3.Request.Builder().url(u).header("User-Agent", app.storage.userAgent)
                .apply { if (!whole) header("Range", "bytes=0-65535") }.build()
            val r = app.http.newCall(req).execute()
            val head = r.body?.byteStream()?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val b = ByteArray(65536)
                while (out.size() < max) { val k = s.read(b); if (k < 0) break; out.write(b, 0, k) }
                String(out.toByteArray(), Charsets.ISO_8859_1)
            }.orEmpty()
            r.close()
            return r to head
        }
        return try {
            val (r, head) = fetch(url)
            val type = r.header("Content-Type").orEmpty().lowercase()
            val text = head.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            when {
                r.code == 401 || r.code == 403 -> Probe(Kind.REFUSED, "The website refused this link (HTTP ${r.code}). It may have expired, or it only works from the website: try Open link → Advanced, or get a fresh link.")
                r.code == 404 || r.code == 410 -> Probe(Kind.REFUSED, "This link has expired or doesn't exist any more (HTTP ${r.code}). Get a fresh link.")
                !r.isSuccessful -> Probe(Kind.REFUSED, "The website answered HTTP ${r.code}.")
                text.startsWith("#EXTM3U") && !text.contains("#EXT-X-") && text.contains("#EXTINF") -> Probe(Kind.M3U_LIST)
                text.startsWith("#EXTM3U") -> {
                    // Read the WHOLE list (and the quality list it points to), then decide.
                    var listUrl = r.request.url.toString()
                    var list = fetch(listUrl, whole = true).second.ifEmpty { text }
                    if (list.contains("#EXT-X-STREAM-INF")) {
                        val variant = list.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        if (variant != null) {
                            listUrl = java.net.URI(listUrl).resolve(variant).toString()
                            list = fetch(listUrl, whole = true).second
                        }
                    }
                    when {
                        list.contains("SAMPLE-AES") || list.contains("KEYFORMAT=", ignoreCase = true) -> Probe(Kind.PROTECTED, "This video is protected (DRM): it can't be played or downloaded outside its website.")
                        HlsLists.isFilm(list) -> Probe(Kind.HLS_FILM)
                        list.contains("#EXT-X-PLAYLIST-TYPE:EVENT") -> Probe(Kind.HLS_LIVE)
                        // No mark either way: look again a few seconds later — a live list grows, a film's doesn't.
                        HlsLists.grows(list) { fetch(listUrl, whole = true).second } -> Probe(Kind.HLS_LIVE)
                        else -> Probe(Kind.HLS_FILM)
                    }
                }
                text.startsWith("<MPD") || type.contains("dash+xml") -> Probe(Kind.PROTECTED, "This is a DASH stream (usually protected): Nova can't download it.")
                text.startsWith("<") || type.contains("text/html") -> Probe(Kind.WEB_PAGE, "This link opens a web page, not a video. In 1DM, share the video link it found (the one with .m3u8 / .mp4), not the page.")
                else -> Probe(Kind.FILE)
            }
        } catch (e: Exception) {
            Probe(Kind.UNKNOWN, "Could not reach the link: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Checks the link, then does [then] — or says clearly why it can't. */
    private fun checked(context: Context, url: String, what: String, then: (Probe) -> Unit) {
        val app = context.applicationContext
        Thread {
            val p = probe(app, url)
            Log.i("NovaTest", "LINK check ${title(url)}: ${p.kind}")
            if (p.kind in setOf(Kind.REFUSED, Kind.WEB_PAGE, Kind.PROTECTED)) {
                toast(app, p.message ?: "This link can't be used")
                return@Thread
            }
            main.post { then(p) }
        }.start()
    }

    /** Plays it like "Open with" does (an .m3u list opens as a temporary channel list). */
    /** [name]: the title to show (e.g. the web page's title) instead of the file name. */
    fun play(context: Context, url: String, name: String? = null) {
        remember(context, url)
        Log.i("NovaTest", "LINK play ${name ?: title(url)}")
        val app = context.applicationContext
        checked(app, url, "play") {
            app.startActivity(
                Intent(app, OpenWithActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
                    .apply { if (!name.isNullOrBlank()) putExtra(OpenWithActivity.EXTRA_NAME, name) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun download(context: Context, url: String, name: String? = null) {
        remember(context, url)
        Log.i("NovaTest", "LINK download ${name ?: title(url)}")
        val app = context.applicationContext
        checked(app, url, "download") { p ->
            when (p.kind) {
                Kind.HLS_LIVE, Kind.M3U_LIST -> toast(app, if (p.kind == Kind.HLS_LIVE) "This is a live stream: it can be played, not downloaded." else "This is a channel list: open it with ▶ Play.")
                else -> {
                    DownloadCenter.enqueue(app, name?.takeIf { it.isNotBlank() } ?: title(url).substringBeforeLast('.'), url, ext(url), ItemType.MOVIE, null)
                    toast(app, "Download added — see the Downloads tab")
                }
            }
        }
    }

    fun downloadAndWatch(context: Context, url: String, name: String? = null) {
        remember(context, url)
        val app = context.applicationContext
        checked(app, url, "download & watch") { p ->
            when (p.kind) {
                // Live streams and channel lists can't be downloaded: just play them.
                Kind.HLS_LIVE, Kind.M3U_LIST -> {
                    toast(app, if (p.kind == Kind.HLS_LIVE) "Live stream: playing (it can't be downloaded)" else "Channel list: opening it")
                    app.startActivity(Intent(app, OpenWithActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                else -> startDownloadAndWatch(app, url, name)
            }
        }
    }

    private fun startDownloadAndWatch(context: Context, url: String, label: String? = null) {
        Log.i("NovaTest", "LINK download & watch ${label ?: title(url)}")
        val name = label?.takeIf { it.isNotBlank() } ?: title(url).substringBeforeLast('.')
        val d = DownloadCenter.watchNow(context.applicationContext, name, url, ext(url), ItemType.MOVIE, null)
        // HLS: the player reads the joined file being written, not the .m3u8 list.
        val playUrl = if (HlsDownload.isHls(url)) "nova-dl://${d.id}/video.ts" else url
        PlaybackQueue.items = listOf(
            PlayItem(SavedItem(ItemType.MOVIE, url, name, accountId = "link"), playUrl, isLive = false, temporary = true, downloadId = d.id)
        )
        PlaybackQueue.index = 0
        PlaybackQueue.groupKey = null
        PlaybackQueue.playlist = null
        context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}


/** Helpers to tell an HLS film (.m3u8 with an end) from a live stream. */
object HlsLists {
    fun isFilm(list: String) = list.contains("#EXT-X-ENDLIST") || list.contains("#EXT-X-PLAYLIST-TYPE:VOD", ignoreCase = true)

    private fun lastPart(list: String) = list.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") }
    private fun sequence(list: String) = list.lines().firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE") }?.substringAfter(':')?.trim()

    /** True when the list changes after its part duration (a live stream). */
    fun grows(list: String, reload: () -> String): Boolean {
        val wait = list.lines().firstOrNull { it.startsWith("#EXT-X-TARGETDURATION") }?.substringAfter(':')?.trim()?.toDoubleOrNull() ?: 6.0
        Thread.sleep((wait.coerceIn(2.0, 10.0) * 1000 + 500).toLong())
        val again = runCatching(reload).getOrNull() ?: return false
        return lastPart(again) != lastPart(list) || sequence(again) != sequence(list)
    }
}
