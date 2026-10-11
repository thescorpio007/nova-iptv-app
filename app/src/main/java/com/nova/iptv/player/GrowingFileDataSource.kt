package com.nova.iptv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.download.DownloadCenter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * "Download & watch": plays a film from its download file while the download is still writing it.
 * Reading past the saved part waits for the download (the player shows "Downloading… 85 %").
 * Only the download talks to the provider — one connection.
 *
 * Many MKV / MP4 files keep their index at the END of the file, and the player reads it before starting.
 * When the player asks for a part far beyond what is downloaded, the download is paused for a moment,
 * just that part is fetched (usually 1–2 MB), and the download continues — still one connection at a time.
 */
@OptIn(UnstableApi::class)
class GrowingFileDataSource(
    private val downloadId: String,
    private val context: android.content.Context,
    private val client: okhttp3.OkHttpClient,
    private val userAgent: String,
) : BaseDataSource(false) {
    companion object {
        /** Further than this beyond the downloaded part: fetch it directly instead of waiting. */
        private const val SIDE_GAP = 8L * 1024 * 1024
    }

    private var side: okhttp3.Response? = null
    private var sideStream: InputStream? = null
    private var sidePos = -1L
    private var pausedForSide = false
    private var uri: Uri? = null
    private var raf: RandomAccessFile? = null
    private var pos = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val file = waitForFile()
        raf = RandomAccessFile(file, "r")
        pos = dataSpec.position
        val total = waitForTotal()
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total > 0 -> (total - pos).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun item() = DownloadCenter.get(downloadId) ?: throw IOException("The download was removed")

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            throw InterruptedIOException()
        }
    }

    private fun waitForFile(): File {
        val end = System.currentTimeMillis() + 30_000
        while (true) {
            val f = File(item().path)
            if (f.exists() && f.length() > 0) return f
            checkRunning()
            if (System.currentTimeMillis() > end) throw IOException("The download hasn't started")
            sleep(250)
        }
    }

    /** Waits a little for the film's size (known once the server answered). */
    private fun waitForTotal(): Long {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            val it = item()
            if (it.totalBytes > 0) return it.totalBytes
            if (it.status == DownloadStatus.DONE) return File(it.path).length()
            if (System.currentTimeMillis() > end) return C.LENGTH_UNSET.toLong()
            sleep(250)
        }
    }

    private var stalledSince = 0L

    /** A paused / failed download can't feed the player: give up after a while so the player shows it. */
    private fun checkRunning() {
        val it = item()
        if (pausedForSide) return
        if (it.status == DownloadStatus.FAILED) throw IOException("Download failed: ${it.error ?: "unknown error"}")
        if (it.status == DownloadStatus.PAUSED) {
            if (stalledSince == 0L) stalledSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - stalledSince > 15_000) throw IOException("The download is paused")
        } else {
            stalledSince = 0L
        }
    }

    /** Reads [pos] straight from the server (Range), with the download paused meanwhile. */
    private fun readSide(buffer: ByteArray, offset: Int, length: Int): Int {
        if (sideStream == null || sidePos != pos) {
            closeSide(resume = false)
            val d = item()
            if (d.status == DownloadStatus.DOWNLOADING || d.status == DownloadStatus.QUEUED) {
                DownloadCenter.pause(downloadId)
                pausedForSide = true
                sleep(1200) // let the download close its connection first (one connection at a time)
            }
            android.util.Log.i("NovaTest", "SIDE read at ${pos / 1024} KB (downloaded ${File(d.path).length() / 1024} KB)")
            val request = okhttp3.Request.Builder().url(d.url).header("User-Agent", userAgent)
                .header("Range", "bytes=$pos-").build()
            val resp = client.newCall(request).execute()
            if (resp.code != 206) {
                resp.close()
                closeSide(resume = true)
                throw IOException("The server can't jump inside the file (HTTP ${resp.code}); wait for the download")
            }
            side = resp
            sideStream = resp.body?.byteStream() ?: throw IOException("Empty response")
            sidePos = pos
        }
        var want = length.toLong()
        if (remaining > 0) want = minOf(want, remaining)
        val n = sideStream!!.read(buffer, offset, want.toInt())
        if (n < 0) {
            closeSide(resume = true)
            return C.RESULT_END_OF_INPUT
        }
        sidePos += n
        pos += n
        if (remaining > 0) remaining -= n
        bytesTransferred(n)
        return n
    }

    /** Ends a direct read and lets the download continue. */
    private fun closeSide(resume: Boolean) {
        runCatching { sideStream?.close() }
        runCatching { side?.close() }
        sideStream = null
        side = null
        sidePos = -1L
        if (resume && pausedForSide) {
            pausedForSide = false
            val d = DownloadCenter.get(downloadId)
            if (d != null && d.status == DownloadStatus.PAUSED) DownloadCenter.resume(context, downloadId)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val file = raf ?: throw IOException("Not open")
        while (true) {
            val have = file.length()
            val d0 = item()
            if (pos >= have + SIDE_GAP && d0.status != DownloadStatus.DONE) {
                return readSide(buffer, offset, length)
            }
            if (sideStream != null || pausedForSide) closeSide(resume = true)
            if (pos < have) {
                var want = minOf(length.toLong(), have - pos)
                if (remaining > 0) want = minOf(want, remaining)
                file.seek(pos)
                val n = file.read(buffer, offset, want.toInt())
                if (n > 0) {
                    pos += n
                    if (remaining > 0) remaining -= n
                    bytesTransferred(n)
                    return n
                }
            }
            val it = item()
            if (it.status == DownloadStatus.DONE && pos >= File(it.path).length()) return C.RESULT_END_OF_INPUT
            checkRunning()
            sleep(200)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeSide(resume = true)
        runCatching { raf?.close() }
        raf = null
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }
}
