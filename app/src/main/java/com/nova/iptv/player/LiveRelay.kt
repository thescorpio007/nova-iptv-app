package com.nova.iptv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Live TV disk buffer ("timeshift").
 *
 *   IPTV server ──► download thread ──► rolling files on disk ──► player
 *
 * A background thread keeps downloading the live MPEG-TS stream into numbered files
 * (seg_0.ts, seg_1.ts, …). The player reads from those files, so:
 *  - a short internet drop doesn't stop the picture: the player keeps reading what is on disk
 *    while the thread reconnects in the background;
 *  - pausing live TV works: the download continues while you are paused;
 *  - you can rewind within what is stored, and jump back to live.
 * Old files are deleted automatically once the buffer reaches its size limit.
 */
class LiveRelay(
    private val url: String,
    private val client: OkHttpClient,
    private val userAgent: String,
    private val dir: File,
    private val maxBytes: Long,
    private val tap: ((ByteArray, Int, Int) -> Unit)? = null,
) {
    private class Segment(val file: File, val start: Long) {
        @Volatile var length = 0L
        val end: Long get() = start + length
    }

    private val lock = Object()
    private val segments = ArrayDeque<Segment>()
    private var nextIndex = 0
    private var written = 0L
    private var readPos = 0L
    private var writer: FileOutputStream? = null
    private var reader: RandomAccessFile? = null
    private var readerSegment: Segment? = null

    @Volatile private var running = true
    @Volatile private var call: Call? = null
    @Volatile private var bytesPerSec = 0.0

    /** When the last bytes arrived from the provider (elapsedRealtime), 0 = never. */
    @Volatile var lastDataAt = 0L
        private set

    /** Bytes the player has read so far (to measure the channel's real bitrate). */
    @Volatile var bytesRead = 0L
        private set

    /** Download speed from the provider, bytes per second (0 = not known yet). */
    val downloadBytesPerSec: Double get() = bytesPerSec

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastHttpCode: Int = 0
        private set

    /** Seconds the server failed in a row without sending anything (for "dead channel" detection). */
    @Volatile var consecutiveFailures = 0
        private set

    private val thread = Thread({ downloadLoop() }, "LiveRelay")

    init {
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name.startsWith("seg_")) it.delete() }
        thread.isDaemon = true
        thread.start()
    }

    // ------------------------------------------------------------ download side

    private fun downloadLoop() {
        val buf = ByteArray(64 * 1024)
        while (running) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
                val c = client.newCall(request)
                call = c
                c.execute().use { resp ->
                    lastHttpCode = resp.code
                    if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
                    val input = (resp.body ?: throw IOException("Empty response")).byteStream()
                    var windowStart = System.nanoTime()
                    var windowBytes = 0L
                    while (running) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        append(buf, n)
                        tap?.invoke(buf, 0, n)
                        consecutiveFailures = 0
                        lastError = null
                        windowBytes += n
                        val elapsed = System.nanoTime() - windowStart
                        if (elapsed > 3_000_000_000L) {
                            val rate = windowBytes * 1e9 / elapsed
                            bytesPerSec = if (bytesPerSec == 0.0) rate else bytesPerSec * 0.7 + rate * 0.3
                            windowStart = System.nanoTime()
                            windowBytes = 0
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                lastError = e.message ?: "Connection error"
            }
            if (!running) break
            consecutiveFailures++
            synchronized(lock) { lock.notifyAll() }
            try {
                Thread.sleep(minOf(500L * consecutiveFailures, 4000L))
            } catch (e: InterruptedException) {
                break
            }
        }
        synchronized(lock) {
            runCatching { writer?.close() }
            writer = null
            lock.notifyAll()
        }
    }

    private fun append(b: ByteArray, n: Int) {
        synchronized(lock) {
            var seg = segments.lastOrNull()
            if (seg == null || seg.length >= SEGMENT_BYTES || writer == null) {
                runCatching { writer?.close() }
                seg = Segment(File(dir, "seg_${nextIndex++}.ts"), written)
                segments.addLast(seg)
                writer = FileOutputStream(seg.file)
            }
            writer?.write(b, 0, n)
            seg.length += n
            written += n
            lastDataAt = android.os.SystemClock.elapsedRealtime()
            trimLocked()
            lock.notifyAll()
        }
    }

    /** Deletes the oldest files once the buffer is over its size limit. */
    private fun trimLocked() {
        while (segments.size > 2) {
            val first = segments.first()
            if (written - first.start <= maxBytes) break
            segments.removeFirst()
            if (readerSegment === first) {
                runCatching { reader?.close() }
                reader = null
                readerSegment = null
            }
            first.file.delete()
            // If you were paused so long that this part is gone, continue from the oldest part kept.
            if (readPos < segments.first().start) readPos = segments.first().start
        }
    }

    // ------------------------------------------------------------ player side

    /** Blocks until data is available; throws if the server sent nothing for [STALL_TIMEOUT_MS]. */
    fun read(dst: ByteArray, off: Int, len: Int): Int {
        val deadline = System.currentTimeMillis() + STALL_TIMEOUT_MS
        while (true) {
            synchronized(lock) {
                if (!running) return C.RESULT_END_OF_INPUT
                val first = segments.firstOrNull()
                if (first != null && readPos < first.start) readPos = first.start
                var n = 0
                if (readPos < written) {
                    val seg = segments.lastOrNull { it.start <= readPos }
                    val raf = if (seg != null) openReader(seg) else null
                    if (seg != null && raf != null) {
                        val inSeg = readPos - seg.start
                        val count = minOf(seg.length - inSeg, len.toLong()).toInt()
                        if (count > 0) {
                            raf.seek(inSeg)
                            n = raf.read(dst, off, count)
                        }
                    }
                    if (n > 0) {
                        readPos += n
                        bytesRead += n
                        return n
                    }
                    lock.wait(50L) // file briefly unavailable; try again
                } else {
                    val wait = deadline - System.currentTimeMillis()
                    if (wait <= 0) {
                        throw IOException("No video from the server" + (lastError?.let { ": $it" } ?: ""))
                    }
                    lock.wait(minOf(wait, 500L))
                }
            }
        }
    }

    private fun openReader(seg: Segment): RandomAccessFile? {
        if (readerSegment === seg && reader != null) return reader
        runCatching { reader?.close() }
        reader = null
        readerSegment = null
        return try {
            RandomAccessFile(seg.file, "r").also {
                reader = it
                readerSegment = seg
            }
        } catch (e: IOException) {
            null
        }
    }

    /** Seconds of video stored ahead of what the player has read (approximate). */
    fun secondsAhead(): Int {
        val rate = bytesPerSec
        if (rate <= 0) return 0
        synchronized(lock) { return ((written - readPos) / rate).toInt() }
    }

    /** Seconds of video stored in total (how far back you can rewind). */
    fun secondsStored(): Int {
        val rate = bytesPerSec
        if (rate <= 0) return 0
        synchronized(lock) {
            val first = segments.firstOrNull()?.start ?: written
            return ((written - first) / rate).toInt()
        }
    }

    /** Bytes stored ahead of the player's read position / in total. */
    fun bytesAhead(): Long = synchronized(lock) { written - readPos }
    /** Everything received since the channel started (only grows). */
    fun bytesStoredTotal(): Long = synchronized(lock) { written }
    fun bytesStored(): Long = synchronized(lock) { written - (segments.firstOrNull()?.start ?: written) }

    /** Steps the read position back (to re-read what a restarted player had in memory). */
    fun moveBackBytes(bytes: Long) {
        synchronized(lock) {
            val first = segments.firstOrNull()?.start ?: 0L
            readPos = (readPos - bytes.coerceAtLeast(0)).coerceIn(first, written)
        }
    }

    /** Jumps to [bytesBack] before the newest video (measured with the channel's real bitrate). */
    fun jumpBackFromNewest(bytesBack: Long) {
        synchronized(lock) {
            val first = segments.firstOrNull()?.start ?: 0L
            readPos = (written - bytesBack.coerceAtLeast(0)).coerceIn(first, written)
        }
    }

    /** Renews only the connection to the provider: everything stored stays, the player keeps reading. */
    fun reconnect() {
        runCatching { call?.cancel() }
    }

    /** Moves the read position; the player must be restarted afterwards. */
    fun moveBy(seconds: Int) {
        val rate = bytesPerSec
        if (rate <= 0) return
        synchronized(lock) {
            val first = segments.firstOrNull()?.start ?: 0L
            readPos = (readPos + (seconds * rate).toLong()).coerceIn(first, written)
        }
    }

    /** Jumps to [cushionSeconds] behind the newest video; the player must be restarted afterwards. */
    fun jumpToLive(cushionSeconds: Int) {
        val rate = bytesPerSec
        synchronized(lock) {
            val first = segments.firstOrNull()?.start ?: 0L
            readPos = if (rate <= 0) written else (written - (cushionSeconds * rate).toLong()).coerceIn(first, written)
        }
    }

    fun close() {
        running = false
        runCatching { call?.cancel() }
        thread.interrupt()
        synchronized(lock) {
            runCatching { reader?.close() }
            reader = null
            readerSegment = null
            lock.notifyAll()
        }
        Thread {
            runCatching { thread.join(3000) }
            synchronized(lock) {
                runCatching { writer?.close() }
                writer = null
                segments.forEach { it.file.delete() }
                segments.clear()
            }
        }.start()
    }

    /** DataSource factory the player uses to read from this buffer. */
    @OptIn(UnstableApi::class)
    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { RelayDataSource(this) }

    companion object {
        private const val SEGMENT_BYTES = 4L * 1024 * 1024
        private const val STALL_TIMEOUT_MS = 25_000L
    }
}

@OptIn(UnstableApi::class)
private class RelayDataSource(private val relay: LiveRelay) : DataSource {
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return relay.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
    }
}

/**
 * For live HLS: playlists (.m3u8) always come fresh from the server, while the video
 * segments go through the disk cache.
 */
@OptIn(UnstableApi::class)
class PlaylistBypassDataSource(
    private val direct: DataSource,
    private val cached: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        direct.addTransferListener(transferListener)
        cached.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path.orEmpty()
        val source = if (path.endsWith(".m3u8", true) || path.endsWith(".m3u", true)) direct else cached
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
