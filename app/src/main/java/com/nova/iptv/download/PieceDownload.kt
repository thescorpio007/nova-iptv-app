package com.nova.iptv.download

import android.util.Log
import com.nova.iptv.NovaApp
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which 4 MB pieces of a file are already saved (kept next to the file as "….pieces" until it is complete),
 * and where the player is watching, so the download fetches the pieces in watching order.
 */
class PieceMap(val total: Long, private val sidecar: File) {
    companion object {
        const val PIECE = 4L * 1024 * 1024
        private val live = ConcurrentHashMap<String, PieceMap>()

        /** The map of a file downloaded in pieces (null = a normal file that grows from the start). */
        fun of(d: DownloadItem): PieceMap? {
            live[d.id]?.let { return it }
            if (d.status == DownloadStatus.DONE) return null
            val f = Sidecar.file(d.path, "pieces")
            if (!f.exists()) return null
            return runCatching { load(f) }.getOrNull()?.also { live[d.id] = it }
        }

        fun register(id: String, map: PieceMap) { live[id] = map }
        fun forget(id: String) { live.remove(id) }

        private fun load(f: File): PieceMap {
            val bytes = f.readBytes()
            val total = java.nio.ByteBuffer.wrap(bytes, 0, 8).long
            val m = PieceMap(total, f)
            for (i in 0 until m.count) if (8 + i < bytes.size && bytes[8 + i].toInt() == 1) m.done[i] = true
            return m
        }
    }

    val count = ((total + PIECE - 1) / PIECE).toInt()
    val done = BooleanArray(count)
    val busy = BooleanArray(count)

    /** Where the player reads now (bytes). */
    @Volatile var wantPos = 0L

    fun start(i: Int) = i * PIECE
    fun end(i: Int) = minOf(total, (i + 1) * PIECE) // exclusive

    @Synchronized fun doneBytes(): Long = (0 until count).sumOf { if (done[it]) end(it) - start(it) else 0L }
    @Synchronized fun complete() = done.all { it }

    /** Bytes saved without a gap from [pos] on. */
    @Synchronized fun contiguousFrom(pos: Long): Long {
        if (pos >= total) return 0
        var i = (pos / PIECE).toInt()
        if (!done[i]) return 0
        while (i < count && done[i]) i++
        return (if (i >= count) total else start(i)) - pos
    }

    /** The next piece to fetch: first the film's beginning and end (its index), then from where you watch. */
    @Synchronized fun next(): Int? {
        val first = listOf(0, count - 1, count - 2).filter { it in 0 until count }
        first.firstOrNull { !done[it] && !busy[it] }?.let { busy[it] = true; return it }
        val from = (wantPos / PIECE).toInt().coerceIn(0, count - 1)
        val pick = (from until count).firstOrNull { !done[it] && !busy[it] } ?: (0 until from).firstOrNull { !done[it] && !busy[it] }
        pick?.let { busy[it] = true }
        return pick
    }

    @Synchronized fun finished(i: Int) {
        busy[i] = false
        done[i] = true
        save()
    }

    @Synchronized fun failed(i: Int) { busy[i] = false }

    @Synchronized fun save() {
        val buf = java.nio.ByteBuffer.allocate(8 + count)
        buf.putLong(total)
        done.forEach { buf.put(if (it) 1 else 0) }
        runCatching { sidecar.writeBytes(buf.array()) }
    }
}

/** Shares a speed limit (bytes per second, 0 = none) between all connections of a download. */
class SpeedLimit(private val bytesPerSec: Long) {
    private var windowStart = System.currentTimeMillis()
    private var used = 0L

    fun take(n: Int) {
        if (bytesPerSec <= 0) return
        val wait: Long
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - windowStart >= 1000) { windowStart = now; used = 0 }
            used += n
            wait = if (used > bytesPerSec) 1000 - (now - windowStart) else 0
        }
        if (wait > 0) Thread.sleep(wait)
    }
}

/**
 * A file downloaded with several connections at once, piece by piece in watching order.
 * Returns false (nothing done) when the server can't send pieces: then the normal one-connection download runs.
 */
class PieceDownload(
    private val app: NovaApp,
    private val start: DownloadItem,
    private val connections: Int,
    private val limit: SpeedLimit,
    private val progress: (title: String, total: Long, done: Long) -> Unit,
) {
    private val id = start.id
    private val file = File(start.path)
    private fun running() = DownloadCenter.get(id)?.status == DownloadStatus.DOWNLOADING

    private fun ua() = app.storage.userAgent

    /** Size of the file; -1 when the server doesn't answer pieces (Range), 0 when it is small (one connection is enough). */
    private fun probe(): Long {
        val req = Request.Builder().url(start.url).header("User-Agent", ua()).header("Range", "bytes=0-0").build()
        app.http.newCall(req).execute().use { r ->
            if (r.code != 206) return -1
            val total = r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: return -1
            return if (total > 2 * PieceMap.PIECE) total else 0
        }
    }

    fun run(): Boolean {
        val total = try { probe() } catch (e: Exception) { -1L }
        if (total == 0L) return false
        if (total < 0) {
            Log.i("NovaTest", "DOWNLOAD ${start.title}: the server doesn't send pieces → 1 connection")
            DownloadCenter.update(id) { it.copy(connections = 1, note = "The website refused several connections → downloading with 1") }
            return false
        }
        val sidecar = Sidecar.file(start.path, "pieces")
        val map = PieceMap.of(start)?.takeIf { it.total == total } ?: PieceMap(total, sidecar).also {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { raf -> raf.setLength(total) }
            it.save()
        }
        PieceMap.register(id, map)
        DownloadCenter.update(id) { it.copy(status = DownloadStatus.DOWNLOADING, totalBytes = total, doneBytes = map.doneBytes(), connections = connections, error = null) }
        Log.i("NovaTest", "DOWNLOAD ${start.title}: $connections connections, ${map.count} pieces")
        val errors = AtomicInteger(0)
        val rangeRefused = java.util.concurrent.atomic.AtomicBoolean(false)
        val workers = (1..connections).map {
            Thread({
                RandomAccessFile(file, "rw").use { raf ->
                    while (running() && !rangeRefused.get() && errors.get() < 12) {
                        val i = map.next() ?: break
                        try {
                            val req = Request.Builder().url(start.url).header("User-Agent", ua())
                                .header("Range", "bytes=${map.start(i)}-${map.end(i) - 1}").build()
                            app.http.newCall(req).execute().use { r ->
                                if (r.code == 200) { rangeRefused.set(true); throw IOException("pieces refused") }
                                if (r.code != 206) throw IOException("Server returned HTTP ${r.code}")
                                val input = r.body?.byteStream() ?: throw IOException("Empty response")
                                var at = map.start(i)
                                val buf = ByteArray(64 * 1024)
                                while (at < map.end(i)) {
                                    if (!running()) throw IOException("stopped")
                                    val n = input.read(buf, 0, minOf(buf.size.toLong(), map.end(i) - at).toInt())
                                    if (n < 0) throw IOException("Connection closed early")
                                    limit.take(n)
                                    synchronized(raf) { raf.seek(at); raf.write(buf, 0, n) }
                                    at += n
                                }
                            }
                            map.finished(i)
                            errors.set(0)
                        } catch (e: Exception) {
                            map.failed(i)
                            if (running() && !rangeRefused.get()) {
                                errors.incrementAndGet()
                                DownloadCenter.update(id, persist = false) { d -> d.copy(error = "Retrying… (${e.message})") }
                                runCatching { Thread.sleep(1500) }
                            }
                        }
                    }
                }
            }, "NovaPiece$it").also { t -> t.start() }
        }
        var last = 0L
        while (workers.any { it.isAlive }) {
            runCatching { Thread.sleep(1000) }
            val done = map.doneBytes()
            DownloadCenter.update(id, persist = false) { it.copy(doneBytes = done, totalBytes = total, speed = done - last, error = null) }
            last = done
            progress(start.title, total, done)
        }
        workers.forEach { runCatching { it.join() } }
        when {
            map.complete() -> {
                PieceMap.forget(id)
                sidecar.delete()
                DownloadCenter.update(id) { it.copy(status = DownloadStatus.DONE, doneBytes = total, totalBytes = total, speed = 0, error = null) }
            }
            rangeRefused.get() -> {
                // The server stopped sending pieces: start over with one connection.
                PieceMap.forget(id)
                sidecar.delete()
                file.delete()
                DownloadCenter.update(id) { it.copy(connections = 1, doneBytes = 0, note = "The website refused several connections → downloading with 1") }
                return false
            }
            running() -> DownloadCenter.update(id) { it.copy(status = DownloadStatus.FAILED, error = "Too many errors from the server", speed = 0) }
            else -> DownloadCenter.update(id) { it.copy(speed = 0) }
        }
        return true
    }
}
