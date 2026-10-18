package com.nova.iptv.download

import com.nova.iptv.NovaApp
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS link (.m3u8): fetches every part in order and joins them into one file that plays
 * like a normal video (also while it is still downloading — "Download & watch").
 *  - a master list picks the best quality;
 *  - AES-128 protected parts are opened with their key;
 *  - progress is kept next to the file ("….parts"), so a paused / interrupted download continues where it stopped.
 */
class HlsDownload(
    private val app: NovaApp,
    private val start: DownloadItem,
    /** Parts fetched at the same time (they are still written in order). */
    private val connections: Int,
    private val limit: SpeedLimit,
    private val progress: (title: String, total: Long, done: Long) -> Unit,
) {
    companion object {
        fun isHls(url: String) = url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

        /** What the player opens for a running HLS download: a list of the parts already saved (see [GrowingHlsList]). */
        fun playUrl(downloadId: String) = "nova-dl://$downloadId/video.m3u8"

        /** The saved parts of a download: "init <len>" first when the video has a header part, then "<offset> <len> <seconds>". */
        fun indexFile(videoPath: String) = Sidecar.file(videoPath, "index")
        private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    }

    private class Key(val method: String, val uri: String?, val iv: ByteArray?)
    private class Part(val url: String, val key: Key?, val sequence: Long, val seconds: Double)

    private val id = start.id
    private val file = File(start.path)
    private val progressFile = Sidecar.file(start.path, "parts")
    private val keys = HashMap<String, ByteArray>()

    private fun get(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", app.storage.userAgent).build()
        app.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server returned HTTP ${r.code}")
            val bytes = r.body?.bytes() ?: throw IOException("Empty response")
            limit.take(bytes.size)
            return bytes
        }
    }

    private fun resolve(base: String, ref: String): String =
        if (ref.contains("://")) ref else runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)

    private fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    /** The parts of the video (after choosing the best quality in a master list). */
    private fun parts(): Pair<String?, List<Part>> {
        var url = start.url
        var text = String(get(url))
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("This link is not a video list (.m3u8)")
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines().map { it.trim() }
            var best: String? = null
            var bestBw = -1L
            lines.forEachIndexed { i, l ->
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = attrs(l)["BANDWIDTH"]?.toLongOrNull() ?: 0
                    val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    if (next != null && bw > bestBw) { bestBw = bw; best = next }
                }
            }
            url = resolve(url, best ?: throw IOException("No video quality found in this list"))
            text = String(get(url))
        }
        if (!com.nova.iptv.ui.HlsLists.isFilm(text) &&
            (text.contains("#EXT-X-PLAYLIST-TYPE:EVENT") || com.nova.iptv.ui.HlsLists.grows(text) { String(get(url)) })
        ) throw IOException("This is a live stream, not a video that can be downloaded")
        var key: Key? = null
        var init: String? = null
        var seq = 0L
        var seconds = 0.0
        val out = mutableListOf<Part>()
        text.lines().map { it.trim() }.forEach { l ->
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0
                l.startsWith("#EXT-X-KEY") -> {
                    val a = attrs(l)
                    val method = a["METHOD"] ?: "NONE"
                    if (method != "NONE" && method != "AES-128") throw IOException("This video is protected ($method) and can't be downloaded")
                    key = if (method == "NONE") null else Key(
                        method, a["URI"]?.let { resolve(url, it) },
                        a["IV"]?.removePrefix("0x")?.removePrefix("0X")?.let { hex -> hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray() },
                    )
                }
                l.startsWith("#EXT-X-MAP") -> init = attrs(l)["URI"]?.let { resolve(url, it) }
                l.startsWith("#EXTINF") -> seconds = l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                l.isNotEmpty() && !l.startsWith("#") -> { out += Part(resolve(url, l), key, seq++, seconds); seconds = 0.0 }
            }
        }
        if (out.isEmpty()) throw IOException("No video parts found in this list")
        return init to out
    }

    private fun decrypt(data: ByteArray, part: Part): ByteArray {
        val k = part.key ?: return data
        val keyBytes = keys.getOrPut(k.uri ?: "") { get(k.uri ?: throw IOException("Missing key")) }
        val iv = k.iv ?: ByteArray(16).also { b -> var s = part.sequence; for (i in 15 downTo 8) { b[i] = (s and 0xff).toByte(); s = s shr 8 } }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    /** "index length" of the parts already saved. */
    private fun readProgress(): Pair<Int, Long> = runCatching {
        val (i, len) = progressFile.readText().trim().split(' ')
        i.toInt() to len.toLong()
    }.getOrDefault(0 to 0L)

    private fun running() = DownloadCenter.get(id)?.status == DownloadStatus.DOWNLOADING

    fun run() {
        file.parentFile?.mkdirs()
        DownloadCenter.update(id) { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }
        var attempt = 0
        while (true) {
            if (!running()) return
            try {
                val (init, parts) = parts()
                var (next, length) = readProgress()
                if (next == 0) length = 0
                // Continue exactly after the last complete part.
                RandomAccessFile(file, "rw").use { it.setLength(length) }
                // The list of saved parts (for watching while it downloads) is cut to the same point.
                val indexFile = indexFile(start.path)
                val index = StringBuilder()
                if (next > 0) runCatching {
                    val keep = indexFile.readLines().filter { it.isNotBlank() }
                    val hasInit = keep.firstOrNull()?.startsWith("init") == true
                    keep.take(next + if (hasInit) 1 else 0).forEach { index.append(it).append('\n') }
                }
                indexFile.writeText(index.toString())
                FileOutputStream(file, true).use { out ->
                    if (next == 0 && init != null) {
                        val bytes = get(init)
                        out.write(bytes); out.flush()
                        length += bytes.size
                        progressFile.writeText("0 $length")
                        indexFile.writeText("init ${bytes.size}\n")
                    }
                    var lastUi = 0L
                    var lastLen = length
                    // Several parts are fetched at once, then written strictly in order.
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(connections.coerceIn(1, 16))
                    val ahead = java.util.LinkedHashMap<Int, java.util.concurrent.Future<ByteArray>>()
                    DownloadCenter.update(id, persist = false) { it.copy(connections = connections.coerceIn(1, 16)) }
                    try {
                    while (next < parts.size) {
                        if (!running()) return
                        var k = next
                        while (ahead.size < connections.coerceIn(1, 16) && k < parts.size) {
                            if (!ahead.containsKey(k)) { val p = parts[k]; ahead[k] = pool.submit<ByteArray> { decrypt(get(p.url), p) } }
                            k++
                        }
                        val bytes = try {
                            ahead.remove(next)!!.get()
                        } catch (e: java.util.concurrent.ExecutionException) {
                            throw (e.cause as? Exception) ?: e
                        }
                        out.write(bytes); out.flush()
                        indexFile.appendText("$length ${bytes.size} ${parts[next].seconds}\n")
                        length += bytes.size
                        next++
                        progressFile.writeText("$next $length")
                        attempt = 0
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 1000 || next == parts.size) {
                            val speedNow = if (lastUi > 0) (length - lastLen) * 1000 / (now - lastUi).coerceAtLeast(1) else 0
                            lastUi = now
                            lastLen = length
                            val estimate = length * parts.size / next
                            DownloadCenter.update(id, persist = next % 20 == 0) {
                                it.copy(doneBytes = length, parts = parts.size, partsDone = next, speed = speedNow, error = null)
                            }
                            progress(start.title, estimate, length)
                        }
                    }
                    } finally {
                        ahead.values.forEach { it.cancel(true) }
                        pool.shutdownNow()
                    }
                }
                progressFile.delete()
                DownloadCenter.update(id) {
                    it.copy(status = DownloadStatus.DONE, doneBytes = length, totalBytes = length, parts = parts.size, partsDone = parts.size, error = null)
                }
                return
            } catch (e: Exception) {
                if (!running()) return
                val message = e.message ?: "Download failed"
                // Problems that a retry can't solve.
                if (message.startsWith("This ") || ++attempt >= 8) {
                    DownloadCenter.update(id) { it.copy(status = DownloadStatus.FAILED, error = message, doneBytes = file.length()) }
                    return
                }
                DownloadCenter.update(id) { it.copy(error = "Retrying… ($message)", doneBytes = file.length()) }
                try { Thread.sleep(minOf(2000L * attempt, 15_000L)) } catch (ie: InterruptedException) { return }
            }
        }
    }
}
