package com.nova.iptv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nova.iptv.NovaApp
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.SavedItem
import com.nova.iptv.player.PlayItem
import com.nova.iptv.player.PlaybackQueue
import com.nova.iptv.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * "Open with Nova IPTV" from file managers, browsers and other apps:
 *  - a video file or link plays in Nova's player (resume, subtitles, ffmpeg sound…);
 *  - an M3U playlist opens as a temporary channel list (◀ shows its groups). Nothing is saved as an account.
 */
class OpenWithActivity : ComponentActivity() {

    /**
     * Site details passed along with the link, the way apps hand them to MX Player ("headers": name, value, …)
     * or as a Bundle (android.media.intent.extra.HTTP_HEADERS). Remembered for that site.
     */
    private fun keepHeaders(link: String?) {
        val host = link?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: return
        val found = LinkedHashMap<String, String>()
        intent?.getStringArrayExtra("headers")?.toList()?.chunked(2)?.forEach { if (it.size == 2) found[it[0]] = it[1] }
        intent?.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { found[k] = it } } }
        listOf("referer" to "Referer", "user-agent" to "User-Agent", "User-Agent" to "User-Agent", "Referer" to "Referer").forEach { (extra, name) ->
            intent?.getStringExtra(extra)?.takeIf { it.isNotBlank() }?.let { found[name] = it }
        }
        if (found.isEmpty()) return
        val st = (application as NovaApp).storage
        st.setLinkHeaders(host, st.linkHeaders(host) + found)
        Log.i("NovaTest", "LINK headers received for $host: ${found.keys.joinToString()}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepHeaders(intent?.data?.toString() ?: LinkActions.findLink(intent?.getStringExtra(Intent.EXTRA_TEXT)))
        if (intent?.action == Intent.ACTION_SEND) {
            shared()
            return
        }
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        // A web page (not a video or a playlist): open it in Nova's Web tab.
        if (isWebPage(uri.toString(), intent?.type)) {
            openWeb(uri.toString())
            return
        }
        val type = intent?.type
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { open(uri, type) } }
            result.onSuccess { (items, playlist) ->
                if (items.isEmpty()) {
                    Toast.makeText(this@OpenWithActivity, "Nothing to play in this file", Toast.LENGTH_LONG).show()
                } else {
                    PlaybackQueue.items = items
                    PlaybackQueue.index = 0
                    PlaybackQueue.groupKey = null
                    PlaybackQueue.playlist = playlist
                    Log.i("NovaTest", "OPEN WITH ${if (playlist != null) "playlist: ${items.size} entries in ${playlist.size} groups" else "video: ${items[0].title}"}")
                    startActivity(
                        Intent(this@OpenWithActivity, PlayerActivity::class.java)
                            .setData(uri) // keeps the permission to read the file
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }
            }.onFailure { e ->
                Log.i("NovaTest", "OPEN WITH failed: $e")
                Toast.makeText(this@OpenWithActivity, "Can't open this file: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    // ------------------------------------------------------------------ Share → Play with Nova

    /** A link shared as text (1DM, browsers…) or a shared video file: Play now / Download & watch / Download only. */
    private fun shared() {
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val link = LinkActions.findLink(intent.getStringExtra(Intent.EXTRA_TEXT))
        Log.i("NovaTest", "SHARE received ${if (link != null) "link" else if (stream != null) "file" else "nothing"}")
        if (link == null) {
            if (stream != null) {
                // A shared file: play it like "Open with".
                startActivity(Intent(this, OpenWithActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(stream, intent.type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } else {
                Toast.makeText(this, "No video link found in what was shared", Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        if (isWebPage(link, null)) {
            openWeb(link)
            return
        }
        val choices = arrayOf("▶  Play now", "⬇▶  Download & watch", "⬇  Download only", "Cancel")
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("▶ Nova IPTV — " + LinkActions.title(link))
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> LinkActions.play(this, link)
                    1 -> LinkActions.downloadAndWatch(this, link)
                    2 -> {
                        LinkActions.download(this, link)
                        Toast.makeText(this, "Download added — see Downloads in Nova", Toast.LENGTH_LONG).show()
                    }
                }
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    // ------------------------------------------------------------------ what was opened

    private fun open(uri: Uri, type: String?): Pair<List<PlayItem>, LinkedHashMap<String, List<PlayItem>>?> {
        val name = displayName(uri)
        val t = type.orEmpty().lowercase()
        val lower = name.lowercase()
        val maybeList = "mpegurl" in t || lower.endsWith(".m3u") || lower.endsWith(".m3u8") ||
            uri.path.orEmpty().lowercase().let { it.endsWith(".m3u") || it.endsWith(".m3u8") } ||
            uri.toString().contains("get.php", ignoreCase = true)
        if (maybeList) {
            val text = readText(uri)
            if (text.contains("#EXT-X-")) {
                // An HLS stream (not a channel list): play it directly.
                val live = !text.contains("#EXT-X-ENDLIST")
                return listOf(video(uri, name, isLive = live)) to null
            }
            val groups = parseM3u(text, base = uri.takeIf { it.scheme?.startsWith("http") == true })
            return groups.values.flatten() to groups
        }
        return listOf(video(uri, name, isLive = false)) to null
    }

    private fun video(uri: Uri, name: String, isLive: Boolean): PlayItem {
        val url = if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString()
        return PlayItem(
            SavedItem(if (isLive) ItemType.LIVE else ItemType.MOVIE, url, name.ifBlank { "Video" }, accountId = "local"),
            url,
            isLive = isLive,
            temporary = true,
            sidecarSubtitle = sidecarFor(url),
        )
    }

    /** "Film.mkv" → "Film.srt" / ".vtt" / ".ass" next to it, when Nova can see the folder. */
    private fun sidecarFor(path: String): String? {
        if (!path.startsWith("/")) return null
        val base = path.substringBeforeLast('.')
        return listOf(".srt", ".vtt", ".ass", ".ssa").map { File(base + it) }
            .firstOrNull { runCatching { it.canRead() }.getOrDefault(false) }
            ?.let { Uri.fromFile(it).toString() }
    }

    private fun displayName(uri: Uri): String {
        intent?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { return it }
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            }
        }
        return Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/')
    }

    private fun readText(uri: Uri): String {
        val max = 30 * 1024 * 1024
        val bytes = when (uri.scheme) {
            "http", "https" -> {
                val app = application as NovaApp
                val req = Request.Builder().url(uri.toString()).header("User-Agent", app.storage.userAgent).build()
                app.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("the server answered HTTP ${r.code}")
                    r.body?.byteStream()?.use { it.readNBytesCompat(max) } ?: ByteArray(0)
                }
            }
            "file" -> File(uri.path ?: "").inputStream().use { it.readNBytesCompat(max) }
            else -> contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(max) } ?: ByteArray(0)
        }
        return String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun openWeb(url: String) {
        startActivity(
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_WEB).setData(Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        private val MEDIA_EXT = Regex("""\.(mp4|mkv|avi|mov|webm|m4v|wmv|flv|mpg|mpeg|ts|m3u|m3u8|mpd|mp3|aac|m4a|flac)$""", RegexOption.IGNORE_CASE)

        private val XTREAM_PATH = Regex("""/(live|movie|series)/[^/]+/[^/]+/\d+""")

        /** An http(s) link that is not a video, audio or playlist file: a web page. */
        fun isWebPage(url: String, type: String?): Boolean {
            if (!url.startsWith("http", ignoreCase = true)) return false
            val t = type.orEmpty().lowercase()
            if (t.startsWith("video/") || t.contains("mpegurl") || t.startsWith("audio/") || t.contains("mp2t") || t.contains("matroska")) return false
            val u = Uri.parse(url)
            val path = u.path.orEmpty()
            if (MEDIA_EXT.containsMatchIn(path)) return false
            if (url.contains("get.php", ignoreCase = true) || url.contains("type=m3u", ignoreCase = true)) return false
            // Xtream stream addresses: /live/…, /movie/…, /series/… with numbers at the end.
            if (XTREAM_PATH.containsMatchIn(path)) return false
            return true
        }

        /** The title to show (Web tab: the page's title). */
        const val EXTRA_NAME = "nova_name"
        private val VIDEO_EXT = Regex("""\.(mp4|mkv|avi|mov|webm|m4v|wmv|flv|mpg|mpeg)$""", RegexOption.IGNORE_CASE)
        private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

        /** Channels of an M3U playlist, by group ("group-title"), in the playlist's order. */
        fun parseM3u(text: String, base: Uri?): LinkedHashMap<String, List<PlayItem>> {
            val groups = LinkedHashMap<String, MutableList<PlayItem>>()
            var name: String? = null
            var logo: String? = null
            var group: String? = null
            var n = 0
            text.lineSequence().map { it.trim() }.forEach { line ->
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        name = line.substringAfterLast(',', "").trim().ifBlank { attrs["tvg-name"] }
                        logo = attrs["tvg-logo"]?.ifBlank { null }
                        group = attrs["group-title"]?.ifBlank { null }
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim().ifBlank { null }
                    line.startsWith("#") -> Unit
                    else -> {
                        val url = resolve(line, base)
                        n++
                        val title = name ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "Channel $n" }
                        val vod = VIDEO_EXT.containsMatchIn(url.substringBefore('?'))
                        val item = PlayItem(
                            SavedItem(if (vod) ItemType.MOVIE else ItemType.LIVE, url, title, logo, accountId = "m3u"),
                            url,
                            isLive = !vod,
                            temporary = true,
                        )
                        groups.getOrPut(group ?: "Channels") { mutableListOf() } += item
                        name = null; logo = null; group = null
                    }
                }
            }
            return LinkedHashMap(groups.mapValues { it.value.toList() })
        }

        private fun resolve(line: String, base: Uri?): String {
            if (line.contains("://") || base == null) return line
            return runCatching { java.net.URI(base.toString()).resolve(line).toString() }.getOrDefault(line)
        }
    }
}
