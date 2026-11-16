package com.nova.iptv.download

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.nova.iptv.NovaApp
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.data.Folders
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.Locations
import com.nova.iptv.data.Locations.Kind
import java.io.File

/**
 * Where a download really goes, checked just before it starts (works the same for any USB / SD drive):
 *  - you chose a USB drive but none is plugged in → "⏳ Wait for the USB" (default) or "Save on internal storage";
 *  - the USB is FAT32 (4 GB per file at most) → a film over 4 GB, or of unknown size, goes to internal storage;
 *  - exFAT, NTFS (writable), ext4, F2FS → everything stays on the USB.
 */
object StorageRules {
    const val WAITING = "⏳ Waiting for the USB drive (plug it in)"
    private const val INTERNAL_OK = "Saved on internal storage (your choice: USB not found)"

    private fun kindOf(d: DownloadItem) = if (d.kind == ItemType.MOVIE) Kind.MOVIES else Kind.SERIES

    /** The download as it should start, or null when it waits (for the USB, or for your answer). */
    fun before(app: NovaApp, d: DownloadItem): DownloadItem? {
        val started = File(d.path).exists() || Sidecar.file(d.path, "pieces").exists() || Sidecar.file(d.path, "index").exists()
        if (started) return d
        val kind = kindOf(d)
        var item = d
        if (Locations.wantsUsb(kind) && item.note != INTERNAL_OK) {
            val r = Locations.resolve(kind)
            if (r.drive?.removable != true) {
                DownloadCenter.update(d.id) { it.copy(status = DownloadStatus.PAUSED, note = WAITING, speed = 0) }
                Log.i("NovaTest", "STORAGE ${d.title}: USB chosen but not found → waiting / asking")
                askUsbMissing(app, d)
                return null
            }
            // The USB is here: a download that was waiting (or saved by default on internal) moves to it.
            if (Locations.driveOf(item.path) == null) item = moveTo(item, Locations.dirFor(kind, subOf(item.path, kind)), null)
        }
        // FAT32: 4 GB per file.
        val drive = Locations.driveOf(item.path)
        if (drive != null && Locations.isFat32(drive)) {
            val size = if (HlsDownload.isHls(item.url)) -1L else sizeOf(app, item.url)
            if (size !in 1..Locations.FAT32_MAX) {
                val why = if (size > 0) "%.1f GB is over the 4 GB limit of %s (FAT32) → saved on internal storage".format(size / 1073741824.0, Locations.nameOf(drive))
                else "size unknown and ${Locations.nameOf(drive)} is FAT32 (4 GB per file) → saved on internal storage"
                item = moveTo(item, Locations.internalDirFor(kind, subOf(item.path, kind)), why)
                Log.i("NovaTest", "STORAGE ${d.title}: $why")
            }
        }
        return item
    }

    private fun subOf(path: String, kind: Kind): String {
        val parent = File(path).parent ?: return ""
        val marker = "/${kind.defaultSub}/"
        return if (parent.contains(marker)) parent.substringAfter(marker) else ""
    }

    private fun moveTo(d: DownloadItem, dir: File, note: String?): DownloadItem {
        val f = File(d.path)
        val nf = Folders.uniqueFile(dir, f.nameWithoutExtension, f.extension.ifBlank { "mp4" })
        DownloadCenter.update(d.id) { it.copy(path = nf.absolutePath, note = note ?: if (it.note == WAITING) null else it.note) }
        return d.copy(path = nf.absolutePath, note = note)
    }

    /** The film's size from the server (HEAD, or a 1-byte range), or -1. */
    private fun sizeOf(app: NovaApp, url: String): Long = runCatching {
        val head = okhttp3.Request.Builder().url(url).head().header("User-Agent", app.storage.userAgent).build()
        val n = app.http.newCall(head).execute().use { r -> if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: -1L else -1L }
        if (n > 0) return@runCatching n
        val get = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", app.storage.userAgent).build()
        app.http.newCall(get).execute().use { r -> r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L }
    }.getOrDefault(-1L)

    private fun askUsbMissing(app: NovaApp, d: DownloadItem) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val a = com.nova.iptv.TopActivity.get() ?: return@post
            com.nova.iptv.player.RecPopups.choose(
                a, "USB drive not found",
                "“${d.title}” should be saved on your USB / SD drive, but none is plugged in.",
                listOf(
                    com.nova.iptv.player.RecPopups.Choice("⏳ Wait for the USB", "It starts by itself when you plug the drive in · chosen in 10 s") { },
                    com.nova.iptv.player.RecPopups.Choice("Save on internal storage") {
                        DownloadCenter.update(d.id) { it.copy(status = DownloadStatus.QUEUED, note = INTERNAL_OK) }
                        DownloadService.start(a)
                    },
                ),
                default = 0,
            ) { }
        }
    }

    /** Plugging a drive in: downloads waiting for it start (on the drive). */
    fun watchDrives(c: Context) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val waiting = DownloadCenter.items.value.filter { it.status == DownloadStatus.PAUSED && it.note == WAITING }
                Log.i("NovaTest", "STORAGE drive ${i.action?.substringAfterLast('.')}: ${waiting.size} waiting")
                if (i.action != Intent.ACTION_MEDIA_MOUNTED || waiting.isEmpty()) return
                waiting.forEach { w -> DownloadCenter.update(w.id) { it.copy(status = DownloadStatus.QUEUED) } }
                DownloadService.start(ctx)
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        runCatching { androidx.core.content.ContextCompat.registerReceiver(c, r, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED) }
    }
}
