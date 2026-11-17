package com.nova.iptv.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.data.Folders
import com.nova.iptv.data.ItemType
import com.nova.iptv.player.ReadAheadBuffer
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ⬇ Download while watching a film / episode — ONE connection:
 *  - the film's buffer (already on the box) becomes the start of the download, and the player's own connection
 *    keeps saving the whole film (ahead to the end, then the part before where you started); nothing is deleted;
 *  - when you leave the film before it is complete, the saved 4 MB pieces are placed into the download file and
 *    the normal downloader continues from there (pieces map) — nothing is fetched twice;
 *  - complete while you watch: the pieces are joined into the film file at once.
 */
object FilmKeep {
    const val NOTE = "📼 From your watching · 1 connection (shared with the player)"

    private class Job(val id: String, val buf: ReadAheadBuffer)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val main = Handler(Looper.getMainLooper())

    /** Downloads the downloader must not take (the player's connection is saving them). */
    fun attached(id: String): Boolean = jobs.containsKey(id)

    fun owns(buf: ReadAheadBuffer?): Boolean = buf != null && jobs.values.any { it.buf === buf }

    private val busyDirs = ConcurrentHashMap.newKeySet<String>()

    /** Folders of buffers being kept or handed over (never cleared by a new film). */
    fun keptDirs(): Set<String> = jobs.values.map { it.buf.dir.name }.toSet() + busyDirs

    /** The buffer of a film being kept, to watch it again without a second connection. */
    fun bufferFor(url: String): ReadAheadBuffer? = jobs.values.firstOrNull { it.buf.url == url }?.buf

    /**
     * Turns the playing film's buffer into a download. Returns false when it can't (no buffer / HLS film):
     * the caller then uses the normal download.
     */
    fun adopt(c: Context, buf: ReadAheadBuffer?, title: String, url: String, ext: String, kind: String, icon: String?): Boolean {
        if (buf == null || buf.url != url) return false
        jobs.values.firstOrNull { it.buf === buf }?.let { return true }
        // Already downloaded or in the list: the existing one stays.
        DownloadCenter.items.value.firstOrNull { it.url == url && it.status != DownloadStatus.FAILED }?.let { existing ->
            if (existing.status == DownloadStatus.DONE) return true
            DownloadCenter.remove(existing.id, deleteFile = true)
            runCatching { Sidecar.file(existing.path, "pieces").delete() }
        }
        val total = buf.totalBytes
        val dirKind = if (kind == ItemType.MOVIE) com.nova.iptv.data.Locations.Kind.MOVIES else com.nova.iptv.data.Locations.Kind.SERIES
        val dir = com.nova.iptv.data.Locations.dirFor(dirKind, "")
        dir.mkdirs()
        val free = runCatching { android.os.StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (total > 0 && free < total - buf.storedBytes() + 300L * 1024 * 1024) {
            android.widget.Toast.makeText(c, "Not enough free space for “$title” (%.1f GB needed)".format(total / 1073741824.0), android.widget.Toast.LENGTH_LONG).show()
            return true
        }
        val file = Folders.uniqueFile(dir, title, ext.ifBlank { "mp4" })
        val item = DownloadItem(
            id = UUID.randomUUID().toString(), title = title, kind = kind, url = url, path = file.absolutePath, icon = icon,
            createdAt = System.currentTimeMillis(), status = DownloadStatus.DOWNLOADING, totalBytes = total,
            doneBytes = buf.storedBytes(), connections = 1, note = NOTE,
        )
        DownloadCenter.add(item)
        buf.keepAll = true
        jobs[item.id] = Job(item.id, buf)
        Log.i("NovaTest", "FILMKEEP adopt $title: ${buf.storedBytes() / 1048576} MB already saved, 1 connection")
        tick(c.applicationContext, item.id)
        return true
    }

    /** Progress every 2 s while the player saves it; Pause / Delete in Downloads are followed. */
    private fun tick(c: Context, id: String) {
        var last = -1L
        var lastAt = 0L
        val r = object : Runnable {
            override fun run() {
                val job = jobs[id] ?: return
                val d = DownloadCenter.get(id)
                if (d == null) { // 🗑 deleted
                    job.buf.keepAll = false
                    jobs.remove(id)
                    Log.i("NovaTest", "FILMKEEP deleted → buffer back to normal")
                    return
                }
                if (d.status == DownloadStatus.PAUSED) { job.buf.keepAll = false; main.postDelayed(this, 2000); return }
                if (d.status == DownloadStatus.QUEUED) DownloadCenter.update(id) { it.copy(status = DownloadStatus.DOWNLOADING, note = NOTE) }
                job.buf.keepAll = true
                val now = System.currentTimeMillis()
                val stored = job.buf.storedBytes()
                val speed = if (last >= 0 && now > lastAt) ((stored - last) * 1000 / (now - lastAt)).coerceAtLeast(0) else 0
                last = stored; lastAt = now
                DownloadCenter.update(id, persist = false) {
                    it.copy(doneBytes = stored, totalBytes = job.buf.totalBytes, speed = speed, connections = 1)
                }
                if (job.buf.isComplete()) {
                    Log.i("NovaTest", "FILMKEEP complete while watching")
                    jobs.remove(id)
                    Thread({ joinAll(c, d, job.buf, continueAfter = false, leaveBuffer = true) }, "nova-filmkeep").start()
                    return
                }
                main.postDelayed(this, 2000)
            }
        }
        main.post(r)
    }

    /**
     * The player stops using [buf] (film closed / another film): the saved pieces go into the download file
     * and the downloader continues the rest. Returns true when [buf] belonged to a download (don't clear it).
     */
    fun released(c: Context, buf: ReadAheadBuffer?): Boolean {
        val job = jobs.values.firstOrNull { it.buf === buf } ?: return false
        jobs.remove(job.id)
        val d = DownloadCenter.get(job.id) ?: run { buf?.close(); return true }
        buf?.dir?.name?.let { busyDirs += it }
        Thread({
            buf!!.closeKeep()
            try { joinAll(c.applicationContext, d, buf, continueAfter = true, leaveBuffer = false) }
            finally { busyDirs -= buf.dir.name }
        }, "nova-filmkeep").start()
        return true
    }

    /**
     * Places every saved 4 MB piece at its place in the film file (the downloader's pieces have the same size),
     * then: complete → done; otherwise the downloader continues with the missing pieces only.
     */
    private fun joinAll(c: Context, d: DownloadItem, buf: ReadAheadBuffer, continueAfter: Boolean, leaveBuffer: Boolean) {
        val total = buf.totalBytes
        if (total <= 0) {
            DownloadCenter.update(d.id) { it.copy(status = DownloadStatus.QUEUED, note = null) }
            if (!leaveBuffer) runCatching { buf.dir.deleteRecursively() }
            main.post { DownloadService.start(c) }
            return
        }
        DownloadCenter.update(d.id) { it.copy(note = "📼 Saving the watched part into the file…", speed = 0) }
        val map = PieceMap(total, Sidecar.file(d.path, "pieces"))
        var placed = 0L
        runCatching {
            RandomAccessFile(File(d.path), "rw").use { out ->
                if (out.length() < total) out.setLength(total)
                val tmp = ByteArray(256 * 1024)
                for ((i, len) in buf.completeSegments().sortedBy { it.first }) {
                    val f = buf.segmentFile(i)
                    if (!f.exists() || f.length() < len) continue
                    out.seek(i * ReadAheadBuffer.SEG)
                    f.inputStream().use { inp ->
                        var left = len
                        while (left > 0) {
                            val n = inp.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
                            if (n < 0) break
                            out.write(tmp, 0, n); left -= n
                        }
                    }
                    if (i < map.count) map.done[i] = true
                    placed += len
                    if (!leaveBuffer) f.delete()
                }
            }
        }.onFailure { Log.w("NovaTest", "FILMKEEP join failed: ${it.message}") }
        if (!leaveBuffer) runCatching { buf.dir.deleteRecursively() }
        if (map.complete()) {
            runCatching { Sidecar.file(d.path, "pieces").delete() }
            DownloadCenter.update(d.id) { it.copy(status = DownloadStatus.DONE, doneBytes = total, totalBytes = total, speed = 0, note = null) }
            Log.i("NovaTest", "FILMKEEP done ${d.title} (${total / 1048576} MB, no extra connection)")
        } else {
            map.save()
            DownloadCenter.update(d.id) {
                it.copy(status = if (continueAfter) DownloadStatus.QUEUED else it.status, doneBytes = map.doneBytes(), totalBytes = total, speed = 0,
                    note = "📼 ${placed / 1048576} MB came from your watching · the rest continues")
            }
            Log.i("NovaTest", "FILMKEEP handed over ${d.title}: ${placed / 1048576} MB placed, downloader continues")
            if (continueAfter) main.post { DownloadService.start(c) }
        }
    }
}
