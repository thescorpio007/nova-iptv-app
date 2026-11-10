package com.nova.iptv.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Disk read-ahead for a film / episode (the "segments on disk" idea):
 *  - ONE connection downloads the file ahead of the player into fixed-size segments (4 MB);
 *  - the player only reads from these segments, never from the network itself;
 *  - at most [limitBytes] are kept: ~80 % ahead of the playing position, ~20 % behind;
 *    older segments are deleted as playback moves on, and everything is deleted at the end.
 * Jumping to a part that isn't on disk moves the download there (HTTP Range).
 */
class ReadAheadBuffer(
    private val url: String,
    private val client: OkHttpClient,
    private val userAgent: String,
    private val dir: File,
    limitBytes: Long,
) {
    companion object {
        const val SEG = 4L * 1024 * 1024
        private const val TAG = "NovaReadAhead"
    }

    @Volatile private var limit = limitBytes.coerceAtLeast(3 * SEG)
    private val aheadLimit get() = (limit * 8 / 10).coerceAtLeast(2 * SEG)
    private val behindLimit get() = (limit - aheadLimit).coerceAtLeast(SEG)

    /** Size in use now (bytes): the chosen size, or more for a big film (see [growTo]). */
    val limitBytes: Long get() = limit

    /** Big films (4K): allow more on disk so about two minutes stay ahead. Never shrinks. */
    fun growTo(bytes: Long) {
        if (bytes <= limit) return
        lock.withLock {
            limit = bytes
            reevaluate = true
            changed.signalAll()
        }
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // ---- state (guarded by lock)
    private val complete = TreeSet<Int>()
    private val segLen = HashMap<Int, Long>()
    private var partialSeg = -1
    private var partialEnd = 0L // absolute end of the bytes written in the partial segment
    private var total = C.LENGTH_UNSET.toLong()
    private var downloading = false
    private var reevaluate = false
    private var failures = 0
    private var lastError: IOException? = null
    private var closed = false
    private var headersSeen = false
    @Volatile private var call: Call? = null

    /** Where the player is reading (bytes). */
    @Volatile var readPos = 0L
        private set

    @Volatile var rangeSupported = true
        private set

    private val thread = Thread({ runLoop() }, "nova-readahead").apply { isDaemon = true }

    fun start() {
        dir.deleteRecursively()
        dir.mkdirs()
        thread.start()
    }

    fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
        call?.cancel()
        thread.interrupt()
        runCatching { dir.deleteRecursively() }
    }

    val totalBytes: Long get() = total

    /** Bytes on disk without a gap from [from] onwards. */
    fun contiguousFrom(from: Long): Long = lock.withLock { contiguousLocked(from) }

    private fun segFile(i: Int) = File(dir, "seg_$i.bin")

    private fun segStart(i: Int) = i * SEG

    private fun contiguousLocked(from: Long): Long {
        var pos = from
        while (true) {
            val i = (pos / SEG).toInt()
            val end = when {
                i in complete -> segStart(i) + (segLen[i] ?: SEG)
                i == partialSeg && pos < partialEnd -> partialEnd
                else -> return pos - from
            }
            if (end <= pos) return pos - from
            pos = end
            if (total > 0 && pos >= total) return pos - from
            if (i == partialSeg) return pos - from
        }
    }

    /** First byte at or after [from] that is not on disk yet. */
    private fun firstMissingLocked(from: Long): Long = from + contiguousLocked(from)

    /**
     * Where a download for the player's position should start: right after the partial segment's data
     * when continuing it, otherwise at the start of the missing segment (segments are always whole from
     * their start, so no gaps inside a file).
     */
    private fun downloadStartLocked(from: Long): Long {
        val t = firstMissingLocked(from)
        val seg = (t / SEG).toInt()
        return if (seg == partialSeg && t == partialEnd) t else segStart(seg)
    }

    private fun storedLocked(): Long = complete.sumOf { segLen[it] ?: SEG } +
        (if (partialSeg >= 0) partialEnd - segStart(partialSeg) else 0L)

    /** Deletes segments far behind the player when over the limit (then far-ahead ones). */
    private fun evictLocked() {
        val readSeg = (readPos / SEG).toInt()
        val behindSegs = (behindLimit / SEG).toInt().coerceAtLeast(1)
        while (storedLocked() > limit) {
            val behind = complete.firstOrNull { it < readSeg - behindSegs } ?: complete.firstOrNull { it < readSeg - 1 }
            val victim = behind ?: complete.lastOrNull { it > readSeg + 1 } ?: break
            complete.remove(victim)
            segLen.remove(victim)
            segFile(victim).delete()
        }
    }

    // ------------------------------------------------------------------ downloader

    private fun runLoop() {
        while (true) {
            val target: Long = lock.withLock {
                while (true) {
                    if (closed) return
                    reevaluate = false
                    val t = downloadStartLocked(readPos)
                    val aheadOk = t - readPos < aheadLimit
                    val notAtEnd = total <= 0 || t < total
                    if (aheadOk && notAtEnd) return@withLock t
                    downloading = false
                    try {
                        changed.await(1, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
                @Suppress("UNREACHABLE_CODE") 0L
            }
            try {
                download(target)
                lock.withLock { failures = 0; lastError = null }
            } catch (e: InterruptedException) {
                return
            } catch (e: IOException) {
                if (lock.withLock { closed }) return
                val wait = lock.withLock {
                    failures++
                    lastError = e
                    changed.signalAll()
                    minOf(failures * 1000L, 5000L)
                }
                Log.w(TAG, "download problem (${e.message}), retrying in $wait ms")
                try { Thread.sleep(wait) } catch (ie: InterruptedException) { return }
            }
        }
    }

    /** Downloads from [from] until the window is full, the file ends, or the player jumps elsewhere. */
    private fun download(from: Long) {
        val request = Request.Builder().url(url).header("User-Agent", userAgent)
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .build()
        val c = client.newCall(request)
        call = c
        c.execute().use { resp ->
            if (resp.code == 416) {
                lock.withLock { total = from; changed.signalAll() }
                return
            }
            if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val input = body.byteStream()
            var pos = from
            if (resp.code == 206) {
                val t = resp.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                lock.withLock { if (t != null && t > 0) total = t else if (body.contentLength() > 0) total = from + body.contentLength() }
            } else {
                // The server ignores Range: it sends the whole file, skip to where we need to be.
                lock.withLock {
                    if (body.contentLength() > 0) total = body.contentLength()
                    if (from > 0) rangeSupported = false
                }
                var skip = from
                val tmp = ByteArray(64 * 1024)
                while (skip > 0) {
                    val n = input.read(tmp, 0, minOf(tmp.size.toLong(), skip).toInt())
                    if (n < 0) throw IOException("File ended early")
                    skip -= n
                }
            }
            lock.withLock {
                downloading = true
                headersSeen = true
                changed.signalAll()
            }

            val buf = ByteArray(128 * 1024)
            var out: FileOutputStream? = null
            var outSeg = -1
            try {
                while (true) {
                    // Stop when the window is full or the player jumped to a part we aren't fetching.
                    val action = lock.withLock {
                        if (closed) return
                        val ahead = pos - readPos
                        // The player is a little ahead of the download: keep going, we'll get there.
                        val nearby = readPos >= pos && readPos - pos < 2 * SEG
                        val jumpedAway = reevaluate && !nearby && downloadStartLocked(readPos) != pos
                        when {
                            ahead >= aheadLimit -> if (rangeSupported) 0 else 1 // full: stop (or wait if we can't jump back in)
                            jumpedAway && rangeSupported -> 0
                            // The next segment is already on disk: reconnect after it instead of downloading it again.
                            pos % SEG == 0L && (pos / SEG).toInt() in complete && rangeSupported -> 0
                            else -> 2
                        }
                    }
                    if (action == 0) return
                    if (action == 1) {
                        lock.withLock {
                            try { changed.await(500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { throw e }
                        }
                        continue
                    }
                    val seg = (pos / SEG).toInt()
                    val segEnd = segStart(seg + 1)
                    val want = minOf(buf.size.toLong(), segEnd - pos).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) {
                        lock.withLock {
                            if (total <= 0 || pos >= total) {
                                total = pos
                                if (partialSeg >= 0) {
                                    complete += partialSeg
                                    segLen[partialSeg] = partialEnd - segStart(partialSeg)
                                    partialSeg = -1
                                }
                            }
                            changed.signalAll()
                        }
                        if (total > 0 && pos < total) throw IOException("Connection closed early")
                        return
                    }
                    if (seg != outSeg) {
                        out?.close()
                        val continuing = lock.withLock {
                            val cont = partialSeg == seg && partialEnd == pos
                            if (!cont) {
                                // A new segment (or restart inside one): drop the old partial piece.
                                if (partialSeg >= 0 && partialSeg != seg) segFile(partialSeg).delete()
                                partialSeg = seg
                                partialEnd = segStart(seg)
                                complete.remove(seg)
                            }
                            cont
                        }
                        val f = segFile(seg)
                        // A new segment always starts at its first byte (see downloadStartLocked).
                        out = FileOutputStream(f, continuing)
                        outSeg = seg
                    }
                    out!!.write(buf, 0, n)
                    pos += n
                    lock.withLock {
                        partialEnd = pos
                        if (pos == segStart(seg + 1) || (total > 0 && pos >= total)) {
                            complete += seg
                            segLen[seg] = pos - segStart(seg)
                            partialSeg = -1
                            evictLocked()
                        }
                        changed.signalAll()
                    }
                }
            } finally {
                out?.close()
                lock.withLock { downloading = false }
                call = null
            }
        }
    }

    // ------------------------------------------------------------------ reading (player side)

    /** Total length once known (waits a little at the start). */
    fun awaitLength(timeoutMs: Long): Long = lock.withLock {
        val end = System.currentTimeMillis() + timeoutMs
        while (total <= 0 && !headersSeen && !closed) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            if (failures >= 4 && lastError != null) throw lastError!!
            try { changed.await(left, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { throw InterruptedIOException() }
        }
        total
    }

    fun setReadPosition(pos: Long) = lock.withLock {
        if (readPos != pos) {
            readPos = pos
            reevaluate = true
            changed.signalAll()
        }
    }

    /**
     * Reads at [pos], waiting for the download when the bytes aren't there yet.
     * Returns -1 at the end of the file.
     */
    fun read(pos: Long, target: ByteArray, offset: Int, length: Int, handles: HandleCache): Int {
        var seg: Int
        var available: Long
        lock.withLock {
            val started = System.currentTimeMillis()
            while (true) {
                if (closed) throw IOException("Player closed")
                if (readPos != pos) {
                    val jumped = (pos / SEG) != (readPos / SEG)
                    readPos = pos
                    if (jumped) { reevaluate = true }
                    changed.signalAll()
                }
                if (total > 0 && pos >= total) return C.RESULT_END_OF_INPUT
                seg = (pos / SEG).toInt()
                available = when {
                    seg in complete -> segStart(seg) + (segLen[seg] ?: SEG) - pos
                    seg == partialSeg && pos < partialEnd -> partialEnd - pos
                    else -> 0L
                }
                if (available > 0) break
                val err = lastError
                if (err != null && failures >= 6) throw err
                if (System.currentTimeMillis() - started > 45_000) throw IOException("No data from the server for 45 s")
                reevaluate = true
                changed.signalAll()
                try {
                    changed.await(300, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException()
                }
            }
        }
        val n = minOf(length.toLong(), available).toInt()
        val raf = handles.get(seg, segFile(seg))
        raf.seek(pos - segStart(seg))
        val read = raf.read(target, offset, n)
        return if (read <= 0) 0 else read
    }

    /** One open file per reader, for the segment being read. */
    class HandleCache {
        private var seg = -1
        private var raf: RandomAccessFile? = null

        fun get(i: Int, f: File): RandomAccessFile {
            if (i != seg || raf == null) {
                close()
                raf = RandomAccessFile(f, "r")
                seg = i
            }
            return raf!!
        }

        fun close() {
            runCatching { raf?.close() }
            raf = null
            seg = -1
        }
    }
}

/** The player's view of a [ReadAheadBuffer]. */
@OptIn(UnstableApi::class)
class ReadAheadDataSource(private val buffer: ReadAheadBuffer) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private val handles = ReadAheadBuffer.HandleCache()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        pos = dataSpec.position
        buffer.setReadPosition(pos)
        val total = buffer.awaitLength(20_000)
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total > 0 -> (total - pos).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining > 0) minOf(length.toLong(), remaining).toInt() else length
        val n = this.buffer.read(pos, buffer, offset, want, handles)
        if (n == C.RESULT_END_OF_INPUT) return n
        pos += n
        if (remaining > 0) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        handles.close()
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }
}

/**
 * Sends the film's own address to [primary] and anything else (an added subtitle file, …)
 * to the normal data source.
 */
@OptIn(UnstableApi::class)
class RoutingDataSource(
    private val primaryUrl: String,
    private val primary: () -> DataSource,
    private val other: DataSource,
) : DataSource {
    private var active: DataSource? = null
    private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        listeners += transferListener
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.toString() == primaryUrl) primary().also { p -> listeners.forEach { p.addTransferListener(it) } } else other
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }
}
