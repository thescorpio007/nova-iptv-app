package com.nova.iptv.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nova.iptv.NovaApp
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.player.PlayItem
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.util.UUID

/**
 * 📼 Live engine: the channel you watch is saved behind the scenes in 30-minute .ts files (cut into parts at key
 * pictures) and played from there, in the normal live player. A slow or broken line never reaches the picture; you
 * can pause or rewind up to "Keep the last" (30 min – 4 h). Older parts are deleted as it goes; everything is deleted
 * when you change channel or leave the player with Back, and at Nova's start (after a power cut). Never shown as a
 * recording, never listed in Downloads.
 */
object LiveBuffer {
    data class Buf(val id: String, val url: String, val title: String, val path: String)

    @Volatile var current: Buf? = null
        private set
    /** Seconds of the oldest parts already deleted (the player's window starts after them). */
    @Volatile var droppedSec = 0.0
    @Volatile var savedSec = 0.0
    /** Where you were (seconds since the channel's buffer started), kept while Nova is in the background. */
    private var resumeAbs = -1.0
    private var resumeUrl: String? = null

    // ---------------------------------------------------------------- settings
    private fun prefs(c: Context) = c.getSharedPreferences("livebuffer", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = prefs(c).getBoolean("on", true)
    fun setOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("on", v).apply()
    fun keepMin(c: Context): Int = prefs(c).getInt("keep_min", 60)
    fun setKeepMin(c: Context, v: Int) = prefs(c).edit().putInt("keep_min", v).apply()
    /** "internal" (default) or "rec" (same place as recordings). */
    fun place(c: Context): String = prefs(c).getString("place", "internal") ?: "internal"
    fun setPlace(c: Context, v: String) = prefs(c).edit().putString("place", v).apply()
    /** "fast" (6 s behind live) or "steady" (20 s). */
    fun startMode(c: Context): String = prefs(c).getString("start", "fast") ?: "fast"
    fun setStartMode(c: Context, v: String) = prefs(c).edit().putString("start", v).apply()
    /**
     * How far behind the newest saved part playback starts. Parts are only listed once complete (about 4 s each), so
     * the player must stay about 3 parts behind, or it reaches the end and waits for every new part (stalls).
     */
    fun startOffsetMs(c: Context): Long = if (startMode(c) == "steady") 20_000L else 6_000L
    /** Seconds per file (30 min; the emulator test uses 1 min to cross many file joins). */
    fun fileSec(c: Context): Int = prefs(c).getInt("file_sec", 1800)
    fun setFileSec(c: Context, v: Int) = prefs(c).edit().putInt("file_sec", v.coerceIn(60, 1800)).apply()

    private fun dirs(c: Context): List<File> {
        val internal = File(c.getExternalFilesDir(null) ?: c.filesDir, "livebuffer")
        val rec = runCatching { File(com.nova.iptv.data.Locations.dirFor(com.nova.iptv.data.Locations.Kind.RECORDINGS, ""), ".livebuffer") }.getOrNull()
        return listOfNotNull(internal, rec)
    }

    private fun dirFor(c: Context): File {
        val d = dirs(c)
        return (if (place(c) == "rec" && d.size > 1) d[1] else d[0]).apply { mkdirs() }
    }

    // ---------------------------------------------------------------- control

    /** The hidden buffer item for this channel address, when the engine plays it. */
    fun activeFor(url: String?): String? = current?.takeIf { it.url == url }?.id

    fun playUrl(id: String) = "nova-dl://$id/video.m3u8"

    /** Starts saving [item] (or keeps the running buffer of the same channel). Another channel's buffer is deleted. */
    fun start(c: Context, item: PlayItem): String? {
        current?.let { if (it.url == item.url) return it.id }
        stop(c)
        return runCatching {
            val dir = File(dirFor(c), UUID.randomUUID().toString().take(8)).apply { mkdirs() }
            val id = "livebuf-" + UUID.randomUUID().toString()
            val f = File(dir, "live.ts")
            val d = DownloadItem(id = id, title = item.title, kind = "livebuffer", url = item.url, path = f.absolutePath,
                status = DownloadStatus.RECORDING, createdAt = System.currentTimeMillis(), accountId = item.saved.accountId)
            DownloadCenter.add(d)
            current = Buf(id, item.url, item.title, f.absolutePath)
            droppedSec = 0.0; savedSec = 0.0
            Log.i("NovaTest", "LIVEBUF start ${item.title}")
            ContextCompat.startForegroundService(c, Intent(c, LiveBufferService::class.java).setAction(LiveBufferService.ACTION_START).putExtra("id", id))
            id
        }.getOrNull()
    }

    /** Stops saving and deletes the channel's hidden files (channel change, Back). */
    fun stop(c: Context) {
        val b = current ?: return
        current = null
        resumeAbs = -1.0; resumeUrl = null
        Log.i("NovaTest", "LIVEBUF stop ${b.title}")
        runCatching { c.startService(Intent(c, LiveBufferService::class.java).setAction(LiveBufferService.ACTION_STOP)) }
        // The service also deletes; this covers a service that already ended.
        Thread {
            Thread.sleep(1500)
            runCatching { File(b.path).parentFile?.deleteRecursively() }
            DownloadCenter.remove(b.id, deleteFile = false)
        }.start()
    }

    /** Nova goes to the background (Home, TV off): remember where you were; saving continues. */
    fun rememberPosition(url: String, windowPosMs: Long) {
        if (current?.url != url) return
        resumeUrl = url
        resumeAbs = droppedSec + windowPosMs / 1000.0
    }

    /** Where to continue on this channel (in the player's window), or -1 for "near live". */
    fun takeResume(url: String): Long {
        if (resumeUrl != url || resumeAbs < 0) return -1L
        val ms = ((resumeAbs - droppedSec) * 1000).toLong()
        resumeAbs = -1.0; resumeUrl = null
        return ms.coerceAtLeast(0L)
    }

    /** At Nova's start: leftover hidden buffers (power cut, restart, crash) are deleted, in the background. */
    fun cleanAtStart(c: Context) {
        val app = c.applicationContext
        DownloadCenter.items.value.filter { it.kind == "livebuffer" }.forEach { DownloadCenter.remove(it.id, deleteFile = false) }
        Thread({
            var bytes = 0L
            dirs(app).forEach { d -> if (d.exists()) { bytes += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }; d.deleteRecursively() } }
            if (bytes > 0) {
                val txt = "🧹 cleared %.1f GB of old live buffer after a restart".format(bytes / 1073741824.0)
                Log.i("NovaTest", "LIVEBUF $txt")
                app.getSharedPreferences("livebuffer", Context.MODE_PRIVATE).edit().putString("cleaned", txt).apply()
            }
        }, "nova-livebuf-clean").apply { isDaemon = true }.start()
    }

    /** For the report: the last start-up cleanup. */
    fun cleanedText(c: Context): String? = prefs(c).getString("cleaned", null)
}

/** Saves the channel in the background (its own connection) so it keeps going during a pause, Home or TV off. */
class LiveBufferService : Service() {
    companion object {
        const val ACTION_START = "com.nova.iptv.LIVEBUF_START"
        const val ACTION_STOP = "com.nova.iptv.LIVEBUF_STOP"
        private const val CHANNEL = "livebuffer"
        private const val NOTIFICATION_ID = 79
    }

    @Volatile private var running = false
    /** The channel being saved now; an older worker still finishing sees it isn't its turn any more. */
    @Volatile private var activeId: String? = null
    @Volatile private var gen = 0
    @Volatile private var call: Call? = null
    private var worker: Thread? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null
    private var title = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Live TV", NotificationManager.IMPORTANCE_MIN))
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Nova · $title")
        .setContentText("Live TV kept ready (pause / rewind)")
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            LiveBufferService.ACTION_START -> {
                val d = intent.getStringExtra("id")?.let { DownloadCenter.get(it) }
                title = d?.title ?: ""
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
                if (d != null) begin(d) else stopSelf()
            }
            else -> end()
        }
        return START_NOT_STICKY
    }

    private fun begin(d: DownloadItem) {
        // One channel at a time: a running one ends first (its files are deleted by LiveBuffer.stop).
        if (running) { activeId = null; runCatching { call?.cancel() } }
        gen++
        running = true
        activeId = d.id
        runCatching { wifi?.release() }
        runCatching {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Nova:livebuffer").apply { setReferenceCounted(false); acquire() }
        }
        worker = Thread({ save(d) }, "LiveBuffer").apply { isDaemon = true; start() }
    }

    private fun end() {
        running = false
        activeId = null
        runCatching { call?.cancel() }
        val w = worker
        val g = gen
        Thread {
            runCatching { w?.join(5000) }
            // A new channel started meanwhile: keep the service.
            if (gen != g || running) return@Thread
            runCatching { wifi?.release() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        running = false
        runCatching { call?.cancel() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    private class DiskError(msg: String) : Exception(msg)

    private fun save(d: DownloadItem) {
        fun alive() = running && activeId == d.id
        val app = applicationContext as NovaApp
        val file = File(d.path)
        file.parentFile?.mkdirs()
        val index = HlsDownload.indexFile(d.path)
        runCatching { index.delete() }
        val seg = Segmenter(index)
        seg.splitSec = LiveBuffer.fileSec(this).toDouble()
        seg.partSec = 2.0
        // Every part written: kept in order (file, seconds, new timeline) for deleting the oldest.
        class Part(val file: Int, val sec: Double, val disc: Boolean)
        val parts = ArrayDeque<Part>()
        var dropped = 0
        var droppedDisc = 0
        var deletedFiles = 0 // files 1..deletedFiles are deleted
        val keepSec = LiveBuffer.keepMin(this) * 60.0
        val minFree = LiveRec.minFreeGb(this) * 1073741824L
        var lastSpace = 0L
        seg.onPart = { n, sec, disc -> parts.addLast(Part(n, sec, disc)) }

        fun trim() {
            var kept = parts.sumOf { it.sec }
            var drop = 0
            var lowSpace = false
            val now = SystemClock.elapsedRealtime()
            if (now - lastSpace > 30_000) {
                lastSpace = now
                val free = runCatching { android.os.StatFs(file.parentFile!!.absolutePath).availableBytes }.getOrDefault(-1L)
                lowSpace = free in 0 until minFree
            }
            // Older than "Keep the last" (never the newest file being written).
            while (parts.size > 1 && kept - parts.first().sec >= keepSec && parts.first().file < seg.fileNo) {
                val p = parts.removeFirst(); kept -= p.sec; drop++
                dropped++; if (p.disc) droppedDisc++
                LiveBuffer.droppedSec += p.sec
            }
            // Low free space: the oldest whole file goes early.
            if (lowSpace && parts.isNotEmpty() && parts.first().file < seg.fileNo) {
                val f0 = parts.first().file
                while (parts.isNotEmpty() && parts.first().file == f0) {
                    val p = parts.removeFirst(); drop++; dropped++; if (p.disc) droppedDisc++; LiveBuffer.droppedSec += p.sec
                }
                Log.i("NovaTest", "LIVEBUF ${d.title}: low free space → oldest file deleted early")
            }
            if (drop > 0) {
                FileWriter(index, true).use { it.write("drop $dropped\n") }
                // Files whose parts are all gone are deleted (the player no longer lists them).
                val firstFile = parts.firstOrNull()?.file ?: seg.fileNo
                while (deletedFiles + 1 < firstFile) {
                    deletedFiles++
                    runCatching { File(LiveRec.partPath(d.path, deletedFiles)).delete() }
                    Log.i("NovaTest", "LIVEBUF ${d.title}: file $deletedFiles deleted (older than ${LiveBuffer.keepMin(this)} min)")
                }
            }
        }

        var written = 0L
        var failures = 0
        var lastDataAt = 0L
        var connections = 0
        val buf = ByteArray(64 * 1024)
        var out = FileOutputStream(file, true)
        var loggedParts = 0
        fun nextFile(at: Long) {
            val n = seg.fileNo + 1
            out.close()
            out = FileOutputStream(File(LiveRec.partPath(d.path, n)), true)
            seg.startFile(at, n)
            Log.i("NovaTest", "LIVEBUF ${d.title}: file $n")
        }
        fun store(b: ByteArray, from: Int, n: Int) {
            seg.scan(b, from, n, written)
            try {
                val sw = seg.switchCandidate()
                if (sw in written..(written + n)) {
                    val k = (sw - written).toInt()
                    if (k > 0) out.write(b, from, k)
                    seg.flush()
                    nextFile(sw)
                    if (n - k > 0) out.write(b, from + k, n - k)
                } else out.write(b, from, n)
                seg.flush()
            } catch (e: IOException) {
                throw DiskError(e.message ?: "can't write")
            }
            written += n
            lastDataAt = SystemClock.elapsedRealtime()
            LiveBuffer.savedSec = seg.totalSec
            trim()
            if (seg.count != loggedParts && (seg.count == 1 || seg.count % 10 == 0)) {
                loggedParts = seg.count
                Log.i("NovaTest", "LIVEBUF parts ${d.title}: ${seg.count} (${seg.totalSec.toLong()} s, file ${seg.fileNo}, ${seg.skips} damaged left out)")
            }
        }
        try {
            while (alive()) {
                try {
                    val c = app.http.newCall(Request.Builder().url(d.url).header("User-Agent", app.storage.userAgent).build())
                    call = c
                    c.execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
                        val input = (resp.body ?: throw IOException("Empty response")).byteStream()
                        seg.newConnection()
                        val start = java.io.ByteArrayOutputStream()
                        var aligned = false
                        var first = true
                        while (alive()) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            if (first) {
                                first = false
                                connections++
                                if (connections > 1 && lastDataAt > 0) Log.i("NovaTest", "LIVEBUF reconnected ${d.title}: gap ${(SystemClock.elapsedRealtime() - lastDataAt) / 1000} s")
                                failures = 0
                            }
                            if (!aligned) {
                                start.write(buf, 0, n)
                                val bytes = start.toByteArray()
                                val at = tsSync(bytes)
                                if (at < 0 && bytes.size < 1024 * 1024) continue
                                aligned = true
                                val from = if (at < 0) 0 else at
                                if (bytes.size - from > 0) store(bytes, from, bytes.size - from)
                                continue
                            }
                            store(buf, 0, n)
                        }
                    }
                } catch (e: Exception) {
                    if (e is DiskError) throw e
                    if (!alive()) break
                    Log.i("NovaTest", "LIVEBUF connection ${d.title}: ${e.message}")
                }
                if (!alive()) break
                failures++
                try { Thread.sleep(minOf(500L * failures, 4000L)) } catch (e: InterruptedException) { break }
            }
        } catch (e: Exception) {
            Log.w("NovaTest", "LIVEBUF failed ${d.title}: ${e.message}")
        } finally {
            runCatching { seg.finish(written) }
            runCatching { out.close() }
            Log.i("NovaTest", "LIVEBUF ended ${d.title}: ${seg.count} parts, ${seg.totalSec.toLong()} s, $dropped deleted, ${seg.fileNo} files, ${seg.skips} damaged left out")
            // Ended by a channel change or Back (not by a pause): the hidden files go.
            if (LiveBuffer.current?.id != d.id) {
                runCatching { file.parentFile?.deleteRecursively() }
                DownloadCenter.remove(d.id, deleteFile = false)
            }
        }
    }

    private fun tsSync(b: ByteArray): Int {
        val last = b.size - 2 * 188 - 1
        for (i in 0..last) if (b[i] == 0x47.toByte() && b[i + 188] == 0x47.toByte() && b[i + 376] == 0x47.toByte()) return i
        return -1
    }
}
