package com.nova.iptv.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import okhttp3.OkHttpClient

/**
 * Disk read-ahead for films that come as HLS (a playlist of many short video pieces — most videos found on websites,
 * and .m3u8 films). The single-file read-ahead ([ReadAheadBuffer]) only ever saw the small playlist; here:
 *  - ONE connection downloads the next pieces of the version the player chose into a disk cache, in order;
 *  - the player reads those pieces from the disk (same cache) instead of the internet;
 *  - at most [limitBytes] are kept: ~80 % ahead, older pieces behind the picture are deleted as you watch;
 *  - everything is deleted when the film is closed or another one starts.
 * Sites whose piece links expire: after repeated refusals Nova stops reading far ahead (2 minutes only).
 */
@OptIn(UnstableApi::class)
class HlsReadAhead(
    private val cache: Cache,
    client: OkHttpClient,
    userAgent: String,
    limitBytes: Long,
) {
    private data class Piece(val uri: Uri, val offset: Long, val length: Long, val startUs: Long, val durUs: Long) {
        val key: String get() = uri.toString()
        fun spec() = DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length).build()
    }

    private val writerSource: CacheDataSource = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client).setUserAgent(userAgent))
        .createDataSource()

    @Volatile private var limit = limitBytes.coerceAtLeast(16L * 1024 * 1024)
    val limitBytes: Long get() = limit
    fun growTo(bytes: Long) { if (bytes > limit) limit = bytes }

    private val lock = Object()
    @Volatile private var pieces: List<Piece> = emptyList()
    @Volatile private var playlistId: Int = 0
    @Volatile private var fromUs = 0L      // the player's buffered end: reading starts after it
    @Volatile private var playUs = 0L      // the picture
    @Volatile private var closed = false
    @Volatile private var writer: CacheWriter? = null
    private var failures = 0
    @Volatile var expiring = false
        private set

    /** Pieces on disk without a gap after what the player already holds: time (ms) and bytes. */
    @Volatile var aheadMs = 0L
        private set
    @Volatile var aheadBytes = 0L
        private set
    /** Average size of one second of this film (bytes), once a few pieces are known. */
    @Volatile var bytesPerSec = 0.0
        private set
    @Volatile var started = false
        private set

    private val thread = Thread({ runLoop() }, "nova-hls-readahead").apply { isDaemon = true }

    fun start() {
        clearAll()
        thread.start()
    }

    fun close() {
        closed = true
        writer?.cancel()
        thread.interrupt()
        Thread { clearAll() }.start()
    }

    private fun clearAll() {
        runCatching { cache.keys.toList().forEach { runCatching { cache.removeResource(it) } } }
    }

    /** Called every second from the player screen with the playlist the player uses and its positions (ms). */
    fun update(playlist: HlsMediaPlaylist?, positionMs: Long, bufferedMs: Long) {
        playUs = positionMs * 1000
        fromUs = bufferedMs * 1000
        if (playlist != null && playlist.hasEndTag) {
            val id = System.identityHashCode(playlist)
            if (id != playlistId) {
                val base = playlist.baseUri
                pieces = playlist.segments.map { s ->
                    Piece(UriUtil.resolveToUri(base, s.url), s.byteRangeOffset, s.byteRangeLength, s.relativeStartTimeUs, s.durationUs)
                }
                playlistId = id
                synchronized(lock) { lock.notifyAll() }
            }
        }
        measure()
        synchronized(lock) { lock.notifyAll() }
    }

    private fun cachedLen(p: Piece): Long {
        val len = if (p.length != C.LENGTH_UNSET.toLong()) p.length
        else ContentMetadata.getContentLength(cache.getContentMetadata(p.key)).takeIf { it > 0 } ?: return 0
        return if (cache.isCached(p.key, p.offset, len)) len else 0
    }

    private fun firstAfter(list: List<Piece>, us: Long): Int {
        val i = list.indexOfFirst { it.startUs + it.durUs > us }
        return if (i < 0) list.size else i
    }

    private fun measure() {
        val list = pieces
        if (list.isEmpty()) { aheadMs = 0; aheadBytes = 0; return }
        var i = firstAfter(list, fromUs)
        var ms = 0L; var bytes = 0L
        while (i < list.size) {
            val len = cachedLen(list[i]); if (len <= 0) break
            // The piece the buffer ends in counts only for its part after the buffered end.
            val p = list[i]
            val partUs = (p.startUs + p.durUs - maxOf(fromUs, p.startUs)).coerceAtLeast(0)
            ms += partUs / 1000; bytes += len
            i++
        }
        aheadMs = ms; aheadBytes = bytes
        // Film size per second, from the pieces already on disk.
        var kb = 0L; var dur = 0L
        for (p in list) { val l = cachedLen(p); if (l > 0) { kb += l; dur += p.durUs } ; if (dur > 60_000_000) break }
        if (dur > 5_000_000) bytesPerSec = kb * 1_000_000.0 / dur
    }

    private fun runLoop() {
        while (!closed) {
            try {
                val list = pieces
                val next = if (list.isEmpty()) -1 else pickNext(list)
                if (next < 0) {
                    synchronized(lock) { lock.wait(1000) }
                    continue
                }
                started = true
                val p = list[next]
                val w = CacheWriter(writerSource, p.spec(), null, null)
                writer = w
                w.cache()
                writer = null
                failures = 0
                trimBehind(list)
            } catch (e: InterruptedException) {
                if (closed) return
            } catch (e: Exception) {
                writer = null
                if (closed) return
                val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    ?: (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                failures++
                if (code == 403 || code == 410 || code == 401) expiring = true
                Log.w("NovaTest", "HLS READAHEAD piece failed ($failures)${code?.let { " HTTP $it" } ?: ""}: ${e.message}")
                try { synchronized(lock) { lock.wait(if (failures >= 3) 30_000 else 3_000L * failures) } } catch (_: InterruptedException) { if (closed) return }
            }
        }
    }

    /** Next piece to download after the player's buffer, or -1 when enough is ahead. */
    private fun pickNext(list: List<Piece>): Int {
        val aheadLimit = limit * 8 / 10
        val maxUs = if (expiring) fromUs + 120_000_000 else Long.MAX_VALUE
        var bytes = 0L
        var i = firstAfter(list, fromUs)
        while (i < list.size) {
            val p = list[i]
            if (p.startUs > maxUs) return -1
            val len = cachedLen(p)
            if (len <= 0) return if (bytes < aheadLimit) i else -1
            bytes += len
            if (bytes >= aheadLimit) return -1
            i++
        }
        return -1
    }

    /** Deletes pieces well behind the picture (keeps ~20 % of the size behind for a short rewind). */
    private fun trimBehind(list: List<Piece>) {
        val behindLimit = limit - limit * 8 / 10
        var kept = 0L
        var i = firstAfter(list, playUs) - 1
        while (i >= 0) {
            val p = list[i]
            val len = cachedLen(p)
            if (len > 0) {
                kept += len
                // Pieces cut out of one big file share its address: those are left to the cache.
                if (kept > behindLimit && p.length == C.LENGTH_UNSET.toLong()) runCatching { cache.removeResource(p.key) }
            }
            i--
        }
    }
}
