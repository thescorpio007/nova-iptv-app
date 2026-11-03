package com.nova.iptv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jumping in MKV films that have no index (no "Cues"): the player alone treats them as start-to-end only and every
 * jump went back to 00:00. Nova finds the place itself, like VLC:
 *  - the film's start (header, tracks) is read once;
 *  - the byte position of the wanted time is estimated from the film's size and length, then the next "cluster"
 *    (a block of a few seconds, marked with its time) is found there, corrected until it is close to the wanted time;
 *  - the player then gets a joined stream: the film's start + the film from that cluster on ([SpliceDataSource]).
 * Works for downloaded files, the disk read-ahead and direct streaming alike.
 */
object MkvSeek {
    /** Reads [len] bytes at [pos] (fewer at the end of the file; null = can't). */
    interface Reader {
        val total: Long
        fun read(pos: Long, len: Int): ByteArray?
    }

    data class Found(val header: ByteArray, val clusterOffset: Long, val clusterMs: Long)

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_INFO = 0x1549A966L
    private const val ID_SCALE = 0x2AD7B1L
    private const val ID_TIMECODE = 0xE7L
    private const val ID_CRC = 0xBFL

    private class Vint(val value: Long, val len: Int, val unknown: Boolean)

    /** An element ID (keeps its marker bits), or null. */
    private fun readId(b: ByteArray, at: Int): Vint? {
        if (at >= b.size) return null
        val first = b[at].toInt() and 0xFF
        val len = when { first and 0x80 != 0 -> 1; first and 0x40 != 0 -> 2; first and 0x20 != 0 -> 3; first and 0x10 != 0 -> 4; else -> return null }
        if (at + len > b.size) return null
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return Vint(v, len, false)
    }

    /** A size (marker removed); all ones = unknown size. */
    private fun readSize(b: ByteArray, at: Int): Vint? {
        if (at >= b.size) return null
        val first = b[at].toInt() and 0xFF
        if (first == 0) return null
        var len = 1
        while (len <= 8 && first and (0x80 ushr (len - 1)) == 0) len++
        if (len > 8 || at + len > b.size) return null
        var v = (first and (0xFF ushr len)).toLong()
        var allOnes = v == (0xFF ushr len).toLong()
        for (i in 1 until len) {
            val x = b[at + i].toLong() and 0xFF
            if (x != 0xFFL) allOnes = false
            v = (v shl 8) or x
        }
        return Vint(v, len, allOnes)
    }

    private fun uint(b: ByteArray, at: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /** Where the first cluster starts, and the time unit (ns per tick). Null = not an MKV we can handle. */
    private fun layout(r: Reader): Pair<Long, Long>? {
        val head = r.read(0, 64 * 1024) ?: return null
        val ebml = readId(head, 0) ?: return null
        if (ebml.value != ID_EBML) return null
        val ebmlSize = readSize(head, ebml.len) ?: return null
        var pos = (ebml.len + ebmlSize.len + ebmlSize.value)
        val segBytes = r.read(pos, 16) ?: return null
        val seg = readId(segBytes, 0) ?: return null
        if (seg.value != ID_SEGMENT) return null
        val segSize = readSize(segBytes, seg.len) ?: return null
        pos += seg.len + segSize.len
        var scaleNs = 1_000_000L
        repeat(200) {
            val h = r.read(pos, 16) ?: return null
            val id = readId(h, 0) ?: return null
            val size = readSize(h, id.len) ?: return null
            if (id.value == ID_CLUSTER) return pos to scaleNs
            if (size.unknown) return null
            val dataAt = pos + id.len + size.len
            if (id.value == ID_INFO && size.value < 64 * 1024) {
                val info = r.read(dataAt, size.value.toInt()) ?: return null
                var i = 0
                while (i < info.size) {
                    val cid = readId(info, i) ?: break
                    val cs = readSize(info, i + cid.len) ?: break
                    val at = i + cid.len + cs.len
                    if (cid.value == ID_SCALE && cs.value in 1..8 && at + cs.value <= info.size) scaleNs = uint(info, at, cs.value.toInt()).takeIf { it > 0 } ?: scaleNs
                    i = at + cs.value.toInt()
                }
            }
            pos = dataAt + size.value
            if (pos > 32L * 1024 * 1024) return null // the start of a film is never that long
        }
        return null
    }

    /** The first cluster at or after [from]: (its position, its time in ms). */
    private fun clusterAfter(r: Reader, from: Long, scaleNs: Long, maxMs: Long): Pair<Long, Long>? {
        val window = 512 * 1024
        var start = from
        repeat(8) {
            if (r.total > 0 && start >= r.total) return null
            val b = r.read(start, window) ?: return null
            if (b.size < 16) return null
            var i = 0
            while (i <= b.size - 12) {
                if (b[i] == 0x1F.toByte() && b[i + 1] == 0x43.toByte() && b[i + 2] == 0xB6.toByte() && b[i + 3] == 0x75.toByte()) {
                    val size = readSize(b, i + 4)
                    if (size != null) {
                        var c = i + 4 + size.len
                        var child = readId(b, c)
                        if (child != null && child.value == ID_CRC) {
                            val cs = readSize(b, c + child.len)
                            if (cs != null) { c += child.len + cs.len + cs.value.toInt(); child = readId(b, c) }
                        }
                        if (child != null && child.value == ID_TIMECODE) {
                            val ts = readSize(b, c + child.len)
                            val at = c + (child.len) + (ts?.len ?: 0)
                            if (ts != null && ts.value in 1..8 && at + ts.value <= b.size) {
                                val ms = uint(b, at, ts.value.toInt()) * scaleNs / 1_000_000L
                                if (ms in 0..(maxMs + 120_000)) return (start + i) to ms
                            }
                        }
                    }
                }
                i++
            }
            start += window - 12
        }
        return null
    }

    /** Finds the place for [targetMs] in a film of [durationMs]. Null = not possible (not an MKV, server refused…). */
    fun locate(r: Reader, targetMs: Long, durationMs: Long): Found? {
        val (first, scaleNs) = layout(r) ?: return null
        if (first > 16L * 1024 * 1024) return null
        val header = r.read(0, first.toInt()) ?: return null
        if (header.size.toLong() != first) return null
        val total = r.total
        if (total <= first || durationMs <= 0) return null
        val span = (total - first).toDouble()
        val perMs = span / durationMs
        var off = first + (targetMs * perMs).toLong()
        var before: Pair<Long, Long>? = null // closest cluster not after the wanted time
        var after: Pair<Long, Long>? = null  // closest cluster after it (used only if nothing earlier is found)
        repeat(8) {
            off = off.coerceIn(first, total - 1)
            val c = clusterAfter(r, off, scaleNs, durationMs) ?: return@repeat
            if (c.second <= targetMs) {
                if (before == null || c.second > before!!.second) before = c
                if (targetMs - c.second <= 15_000) return Found(header, c.first, c.second)
                // Too early: move forward by the missing time.
                off = c.first + ((targetMs - c.second) * perMs).toLong()
            } else {
                if (after == null || c.second < after!!.second) after = c
                // Too late: step back from where we looked, by the extra time plus a little.
                off -= ((c.second - targetMs + 3_000) * perMs).toLong().coerceAtLeast(64 * 1024)
            }
        }
        val b = before?.takeIf { targetMs - it.second <= 60_000 } ?: after ?: before ?: return null
        return Found(header, b.first, b.second)
    }

    // ------------------------------------------------------------------ the joined stream

    data class Splice(val realUri: String, val header: ByteArray, val clusterOffset: Long, val total: Long)

    private val splices = ConcurrentHashMap<String, Splice>()
    private val next = AtomicInteger(1)

    /** Registers a joined stream; play the returned address. */
    fun register(s: Splice): String {
        val id = "s" + next.getAndIncrement()
        if (splices.size > 8) splices.clear()
        splices[id] = s
        return "nova-splice://$id/film.mkv"
    }

    fun get(id: String?): Splice? = id?.let { splices[it] }
}

/**
 * The film's start + the film from a cluster on, as one stream; any other address goes straight through to [inner]
 * (which keeps the read-ahead, the cache and the downloads working with the real address).
 */
@UnstableApi
class SpliceDataSource(private val inner: DataSource) : DataSource {
    private var splice: MkvSeek.Splice? = null
    private var pos = 0L
    private var innerOpen = false
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != "nova-splice") {
            splice = null
            innerOpen = true
            return inner.open(dataSpec)
        }
        val s = MkvSeek.get(dataSpec.uri.host) ?: throw IOException("Jump expired")
        splice = s
        uri = dataSpec.uri
        pos = dataSpec.position
        innerOpen = false
        return if (s.total > 0) (s.header.size + (s.total - s.clusterOffset) - pos).coerceAtLeast(0) else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = splice ?: return inner.read(buffer, offset, length)
        if (length == 0) return 0
        if (pos < s.header.size) {
            val n = minOf(length.toLong(), s.header.size - pos).toInt()
            System.arraycopy(s.header, pos.toInt(), buffer, offset, n)
            pos += n
            return n
        }
        if (!innerOpen) {
            inner.open(DataSpec.Builder().setUri(Uri.parse(s.realUri)).setPosition(s.clusterOffset + (pos - s.header.size)).build())
            innerOpen = true
        }
        val n = inner.read(buffer, offset, length)
        if (n > 0) pos += n
        return n
    }

    override fun getUri(): Uri? = if (splice != null) uri else inner.uri

    override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

    override fun close() {
        if (innerOpen) inner.close()
        innerOpen = false
        splice = null
    }
}
