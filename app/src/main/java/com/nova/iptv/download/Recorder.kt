package com.nova.iptv.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.data.Folders
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Records the live channel being watched into Download/NovaIPTV/Recordings as a .ts file.
 * It reuses the stream the player is already receiving, so no extra connection is used.
 */
object Recorder {

    private var out: OutputStream? = null
    private var itemId: String? = null
    private var bytes = 0L
    private var lastUi = 0L
    private var file: File? = null

    @Volatile
    var isRecording = false
        private set

    @Synchronized
    fun start(context: Context, channelName: String, icon: String?): File {
        stopLocked()
        val dir = com.nova.iptv.data.Locations.dirFor(
            com.nova.iptv.data.Locations.Kind.RECORDINGS,
            com.nova.iptv.data.Locations.subFolderFor(com.nova.iptv.data.Locations.Kind.RECORDINGS, title = channelName),
        )
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())
        val f = Folders.uniqueFile(dir, "$channelName $stamp", "ts")
        out = BufferedOutputStream(FileOutputStream(f), 256 * 1024)
        file = f
        bytes = 0
        lastUi = 0
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            title = "$channelName · $stamp",
            kind = "recording",
            url = "",
            path = f.absolutePath,
            icon = icon,
            status = DownloadStatus.RECORDING,
            createdAt = System.currentTimeMillis(),
        )
        DownloadCenter.add(item)
        itemId = item.id
        isRecording = true
        return f
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (!isRecording) return
        synchronized(this) {
            val o = out ?: return
            try {
                o.write(buffer, offset, length)
                bytes += length
                val now = System.currentTimeMillis()
                if (now - lastUi > 2000) {
                    lastUi = now
                    val id = itemId ?: return
                    val b = bytes
                    DownloadCenter.update(id, persist = false) { it.copy(doneBytes = b) }
                }
            } catch (e: IOException) {
                stopLocked()
            }
        }
    }

    /** Stops recording; returns the saved file, if any. */
    @Synchronized
    fun stop(): File? = stopLocked()

    private fun stopLocked(): File? {
        if (!isRecording && out == null) return null
        isRecording = false
        runCatching { out?.flush() }
        runCatching { out?.close() }
        out = null
        val f = file
        val id = itemId
        if (id != null) {
            val size = f?.length() ?: bytes
            DownloadCenter.update(id) { it.copy(status = DownloadStatus.DONE, doneBytes = size, totalBytes = size) }
        }
        itemId = null
        file = null
        return f
    }
}

/** Lets the player's own download be copied into the recording (memory buffer mode). */
@OptIn(UnstableApi::class)
class RecorderSink : DataSink {
    override fun open(dataSpec: DataSpec) = Unit
    override fun write(buffer: ByteArray, offset: Int, length: Int) = Recorder.write(buffer, offset, length)
    override fun close() = Unit
}
