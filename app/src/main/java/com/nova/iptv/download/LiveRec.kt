package com.nova.iptv.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nova.iptv.NovaApp
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.data.Folders
import com.nova.iptv.data.SavedItem
import com.nova.iptv.player.CatWatch
import com.nova.iptv.player.PlayItem
import com.nova.iptv.ui.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ● Background recording of a live channel.
 *
 * The recording has its own connection to the provider and runs in a foreground service, so it keeps going when
 * you change channel, watch films, go to the home screen, turn the TV off or leave home.
 * It is saved as one .ts file plus a list of its parts (cut at key pictures, about every 4 s), so the player can
 * watch it while it records: from the start, from where you left, or the newest part — jumping anywhere inside.
 * Damaged parts found by 🐈 CATWATCH are left out of that list (the player steps over them).
 */
object LiveRec {
    data class State(
        val id: String,
        val title: String,
        val url: String,
        val accountId: String,
        val icon: String?,
        val startedAt: Long,
        val endsAt: Long,
        val bytes: Long = 0L,
        val seconds: Long = 0L,
        val gaps: Int = 0,
        /** > 0: the recording time is up and Nova asks "Keep recording?" (since this time). */
        val askingSince: Long = 0L,
        /** ⏰ A scheduled recording: it ends at [endsAt] without asking, then [then] follows. */
        val fixedEnd: Boolean = false,
        val then: String = "none",
    )

    val state = MutableStateFlow<State?>(null)

    /** Short notes for the player ("● Connection lost — recording reconnected (gap 4 s)"). */
    val notes = MutableSharedFlow<String>(extraBufferCapacity = 8)

    // ---------------------------------------------------------------- settings
    private fun prefs(c: Context) = c.getSharedPreferences("liverec", Context.MODE_PRIVATE)
    fun hours(c: Context): Int = prefs(c).getInt("hours", 4).coerceIn(1, 4)
    fun setHours(c: Context, v: Int) = prefs(c).edit().putInt("hours", v.coerceIn(1, 4)).apply()
    fun fromRecording(c: Context): Boolean = prefs(c).getBoolean("from_rec", true)
    fun setFromRecording(c: Context, v: Boolean) = prefs(c).edit().putBoolean("from_rec", v).apply()
    fun minFreeGb(c: Context): Int = prefs(c).getInt("min_free_gb", 2)
    fun setMinFreeGb(c: Context, v: Int) = prefs(c).edit().putInt("min_free_gb", v).apply()
    fun continueAfterBoot(c: Context): Boolean = prefs(c).getBoolean("boot_continue", false)
    fun setContinueAfterBoot(c: Context, v: Boolean) = prefs(c).edit().putBoolean("boot_continue", v).apply()

    fun freeBytes(c: Context): Long = runCatching {
        val dir = com.nova.iptv.data.Locations.dirFor(com.nova.iptv.data.Locations.Kind.RECORDINGS, "")
        dir.mkdirs()
        android.os.StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    /** Saved while recording, so a box restart can continue it (Settings › After a power cut). */
    private fun saveActive(c: Context, s: State?) {
        val e = prefs(c).edit()
        if (s == null) e.remove("active") else e.putString("active", JSONObject().apply {
            put("title", s.title); put("url", s.url); put("account", s.accountId); put("icon", s.icon ?: ""); put("ends", s.endsAt)
            put("fixed", s.fixedEnd); put("then", s.then)
        }.toString())
        e.apply()
    }

    // ---------------------------------------------------------------- control

    fun isRecording(url: String?): Boolean = url != null && state.value?.url == url

    /** FAT32 (4 GB per file): a recording continues in a new file at the first part boundary after 3.8 GB. */
    const val FAT32_PART = 3_800L * 1024 * 1024
    const val FAT32_HARD = 4_000L * 1024 * 1024

    /** File [n] of a recording: the file itself, then "… (part 2).ts", "… (part 3).ts" … */
    fun partPath(path: String, n: Int): String =
        if (n <= 1) path else path.substringBeforeLast('.') + " (part $n)." + path.substringAfterLast('.', "ts")

    /** Starts recording [url] in the background. Null when another channel is already recording. */
    fun start(
        c: Context, title: String, url: String, accountId: String, icon: String?, endsAt: Long = 0L,
        fixedEnd: Boolean = false, then: String = "none", programme: String? = null,
    ): DownloadItem? {
        if (state.value != null) return null
        // You chose a USB / SD drive but none is plugged in: the recording starts anyway (on internal storage).
        val kindRec = com.nova.iptv.data.Locations.Kind.RECORDINGS
        if (com.nova.iptv.data.Locations.wantsUsb(kindRec) && com.nova.iptv.data.Locations.resolve(kindRec).drive?.removable != true) {
            notes.tryEmit("● USB drive not found — recording on internal storage")
            Log.i("NovaTest", "LIVEREC $title: USB not found → internal storage")
        }
        val dir = com.nova.iptv.data.Locations.dirFor(
            com.nova.iptv.data.Locations.Kind.RECORDINGS,
            com.nova.iptv.data.Locations.subFolderFor(com.nova.iptv.data.Locations.Kind.RECORDINGS, title = title),
        )
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(now))
        val f = Folders.uniqueFile(dir, "$title $stamp", "ts")
        val ends = if (endsAt > now) endsAt else now + hours(c) * 3_600_000L
        val item = DownloadItem(
            id = UUID.randomUUID().toString(), title = "$title · " + (programme?.let { "$it · " } ?: "") + stamp, kind = "recording", url = url,
            path = f.absolutePath, icon = icon, status = DownloadStatus.RECORDING, createdAt = now,
            endsAt = ends, accountId = accountId,
        )
        DownloadCenter.add(item)
        val s = State(item.id, title, url, accountId, icon, now, ends, fixedEnd = fixedEnd, then = then)
        state.value = s
        saveActive(c, s)
        Log.i("NovaTest", "LIVEREC start $title (${(ends - now) / 60_000} min)")
        ContextCompat.startForegroundService(c, Intent(c, RecordService::class.java).setAction(RecordService.ACTION_START).putExtra("id", item.id))
        return item
    }

    fun stop(c: Context, reason: String) {
        if (state.value == null) return
        ContextCompat.startForegroundService(c, Intent(c, RecordService::class.java).setAction(RecordService.ACTION_STOP).putExtra("reason", reason))
    }

    /** Adds [hours] (from now when the time is already up). */
    fun extend(c: Context, hours: Int) {
        if (state.value == null) return
        ContextCompat.startForegroundService(c, Intent(c, RecordService::class.java).setAction(RecordService.ACTION_EXTEND).putExtra("hours", hours))
    }

    /** What the player opens for a recording: the list of its saved parts (or the plain file for old recordings). */
    fun playItem(d: DownloadItem, startAt: Long): PlayItem? {
        val f = File(d.path)
        if (!f.exists()) return null
        val saved = SavedItem("file", d.id, d.title, d.icon)
        val hasParts = HlsDownload.indexFile(d.path).let { it.exists() && it.length() > 0 }
        return if (hasParts || d.status == DownloadStatus.RECORDING) {
            PlayItem(saved, HlsDownload.playUrl(d.id), isLive = false, downloadId = d.id, startAt = startAt)
        } else {
            PlayItem(saved, android.net.Uri.fromFile(f).toString(), isLive = false, startAt = startAt)
        }
    }

    fun isRecordingItem(item: PlayItem?): Boolean {
        val id = item?.downloadId ?: return false
        return DownloadCenter.get(id)?.kind == "recording"
    }

    /** After the box started: continue the recording that was running (only when chosen in Settings). */
    fun onBoot(c: Context) {
        val raw = prefs(c).getString("active", null) ?: return
        saveActive(c, null)
        if (!continueAfterBoot(c)) { Log.i("NovaTest", "LIVEREC boot: not continued (setting)"); return }
        runCatching {
            val o = JSONObject(raw)
            val ends = o.optLong("ends")
            if (ends <= System.currentTimeMillis() + 60_000) return
            start(c, o.getString("title"), o.getString("url"), o.optString("account"), o.optString("icon").ifBlank { null }, ends,
                fixedEnd = o.optBoolean("fixed"), then = o.optString("then", "none"))
            Log.i("NovaTest", "LIVEREC boot: continued ${o.getString("title")}")
        }
    }

    fun clock(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
    fun words(sec: Long): String = if (sec >= 3600) "${sec / 3600} h ${"%02d".format(sec % 3600 / 60)} min" else "${sec / 60} min"
    fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    fun size(bytes: Long): String = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "${bytes / 1048576} MB"

    internal fun saveActiveFromService(c: Context, s: State?) = saveActive(c, s)
}

/** Runs the recording (its own connection), the time limit, the free-space check and the notification. */
class RecordService : Service() {
    companion object {
        const val ACTION_START = "com.nova.iptv.REC_START"
        const val ACTION_STOP = "com.nova.iptv.REC_STOP"
        const val ACTION_EXTEND = "com.nova.iptv.REC_EXTEND"
        private const val CHANNEL = "recording"
        private const val CHANNEL_ASK = "recording_ask"
        private const val NOTIFICATION_ID = 77
        private const val ASK_ID = 78
        private const val ASK_SECONDS = 10
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var call: Call? = null
    private var worker: Thread? = null
    private var itemId: String? = null
    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null
    private var lastSpaceCheck = 0L
    private var lastNotify = 0L
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ASK, "Recording time is up", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra("id"))
            ACTION_STOP -> finish(intent.getStringExtra("reason") ?: "stopped")
            ACTION_EXTEND -> extend(intent.getIntExtra("hours", 1))
            else -> if (!running) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun begin(id: String?) {
        if (running) return
        val d = id?.let { DownloadCenter.get(it) }
        if (d == null) { LiveRec.state.value = null; stopSelf(); return }
        itemId = d.id
        running = true
        finishing = false
        runCatching {
            wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Nova:recording").apply { setReferenceCounted(false); acquire() }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Nova:recording").apply { setReferenceCounted(false); acquire() }
        }
        worker = Thread({ record(d) }, "LiveRec").apply { isDaemon = true; start() }
        main.post(tick)
    }

    // ------------------------------------------------------------ the time limit, free space, notification

    private val tick = object : Runnable {
        override fun run() {
            val s = LiveRec.state.value
            if (!running || s == null) return
            val now = System.currentTimeMillis()
            if (s.fixedEnd && now >= s.endsAt) {
                // ⏰ A scheduled recording ends at its time (no question), then its action follows.
                finish("scheduled end")
                RecSchedule.afterEnd(this@RecordService, s.then, s.title, s.seconds)
                return
            } else if (!s.fixedEnd && s.askingSince == 0L && now >= s.endsAt) {
                LiveRec.state.value = s.copy(askingSince = now)
                Log.i("NovaTest", "LIVEREC time up ${s.title}: asking")
                showAsk(s)
            } else if (s.askingSince > 0 && now - s.askingSince >= (ASK_SECONDS + 1) * 1000L) {
                finish("time is up — stopped and saved")
                return
            }
            if (now - lastSpaceCheck > 30_000) {
                lastSpaceCheck = now
                val free = LiveRec.freeBytes(this@RecordService)
                val min = LiveRec.minFreeGb(this@RecordService) * 1073741824L
                if (free in 0 until min) {
                    val txt = "Low space (${"%.1f".format(free / 1073741824.0)} GB) — recording stopped and saved"
                    LiveRec.notes.tryEmit("● $txt")
                    finish(txt)
                    return
                }
            }
            if (now - lastNotify > 5000) { lastNotify = now; notifyNow() }
            main.postDelayed(this, 1000)
        }
    }

    private fun extend(hours: Int) {
        val s = LiveRec.state.value ?: return
        val now = System.currentTimeMillis()
        val ends = maxOf(now, s.endsAt) + hours * 3_600_000L
        LiveRec.state.value = s.copy(endsAt = ends, askingSince = 0L)
        LiveRec.saveActiveFromService(this, LiveRec.state.value)
        itemId?.let { id -> DownloadCenter.update(id) { it.copy(endsAt = ends) } }
        runCatching { getSystemService(NotificationManager::class.java).cancel(ASK_ID) }
        Log.i("NovaTest", "LIVEREC extended ${s.title} +$hours h (until ${LiveRec.hhmm(ends)})")
        notifyNow()
    }

    private fun pending(action: String, code: Int, hours: Int = 0): PendingIntent {
        val i = Intent(this, RecordService::class.java).setAction(action).putExtra("hours", hours).putExtra("reason", "stopped by you")
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(this, code, i, flags)
        else PendingIntent.getService(this, code, i, flags)
    }

    private fun watchIntent(): PendingIntent = PendingIntent.getActivity(
        this, 7, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_WATCH_REC)
            .putExtra("id", itemId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(): Notification {
        val s = LiveRec.state.value
        val text = if (s == null) "Preparing the recording…"
        else "${LiveRec.words(s.seconds)} · ${LiveRec.size(s.bytes)} · ends ${LiveRec.hhmm(s.endsAt)}" + (if (s.gaps > 0) " · ${s.gaps} reconnection${if (s.gaps == 1) "" else "s"}" else "")
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("● Recording ${s?.title ?: ""}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(watchIntent())
            .addAction(0, "▶ Watch", watchIntent())
            .addAction(0, "+1 h", pending(ACTION_EXTEND, 11, 1))
            .addAction(0, "■ Stop", pending(ACTION_STOP, 12))
            .build()
    }

    private fun notifyNow() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    private fun showAsk(s: LiveRec.State) {
        val h = LiveRec.hours(this)
        val n = NotificationCompat.Builder(this, CHANNEL_ASK)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("⏰ Recording time is up")
            .setContentText("${s.title} has recorded ${LiveRec.words(s.seconds)}. Keep recording? (stops in $ASK_SECONDS s)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setTimeoutAfter((ASK_SECONDS + 2) * 1000L)
            .setAutoCancel(true)
            .addAction(0, "+$h h", pending(ACTION_EXTEND, 21, h))
            .addAction(0, "+1 h", pending(ACTION_EXTEND, 22, 1))
            .addAction(0, "■ Stop", pending(ACTION_STOP, 23))
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ASK_ID, n) }
    }

    private fun finish(reason: String) {
        if (finishing) return
        finishing = true
        val s = LiveRec.state.value
        running = false
        runCatching { call?.cancel() }
        main.removeCallbacks(tick)
        runCatching { getSystemService(NotificationManager::class.java).cancel(ASK_ID) }
        val w = worker
        Thread {
            runCatching { w?.join(8000) }
            main.post {
                Log.i("NovaTest", "LIVEREC stopped ${s?.title}: $reason")
                LiveRec.state.value = null
                LiveRec.saveActiveFromService(this, null)
                runCatching { wake?.release() }
                runCatching { wifi?.release() }
                wake = null; wifi = null
                worker = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    override fun onDestroy() {
        running = false
        runCatching { call?.cancel() }
        main.removeCallbacks(tick)
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------------ the recording itself

    private fun record(d: DownloadItem) {
        val app = applicationContext as NovaApp
        val file = File(d.path)
        file.parentFile?.mkdirs()
        val index = HlsDownload.indexFile(d.path)
        runCatching { index.delete() }
        val seg = Segmenter(index)
        var written = 0L
        var failures = 0
        var connections = 0
        var lastDataAt = 0L
        var gaps = 0
        var gapSec = 0L
        val buf = ByteArray(64 * 1024)
        var out = FileOutputStream(file, true)
        // FAT32 drive (USB / SD): 4 GB per file at most → the recording continues in "… (part 2).ts" before that.
        val fat32 = com.nova.iptv.data.Locations.isFat32Path(d.path)
        if (fat32) seg.splitAt = LiveRec.FAT32_PART
        if (fat32) Log.i("NovaTest", "LIVEREC ${d.title}: FAT32 drive → new file every ${LiveRec.FAT32_PART / 1048576} MB")
        var lastUi = 0L
        var loggedParts = 0
        fun nextFile(at: Long) {
            val n = seg.fileNo + 1
            out.close()
            out = FileOutputStream(File(LiveRec.partPath(d.path, n)), true)
            seg.startFile(at, n)
            Log.i("NovaTest", "LIVEREC ${d.title}: file part $n (FAT32 limit)")
        }
        fun store(b: ByteArray, from: Int, n: Int) {
            seg.scan(b, from, n, written)
            try {
                val sw = seg.switchCandidate()
                if (fat32 && sw in written..(written + n)) {
                    val k = (sw - written).toInt()
                    if (k > 0) out.write(b, from, k)
                    seg.flush()
                    nextFile(sw)
                    if (n - k > 0) out.write(b, from + k, n - k)
                } else if (fat32 && written + n - seg.fileStart > LiveRec.FAT32_HARD) {
                    // No part boundary in time (no key pictures): start the next file here anyway.
                    seg.flush()
                    nextFile(written)
                    out.write(b, from, n)
                } else {
                    out.write(b, from, n)
                }
                seg.flush()
            } catch (e: IOException) {
                throw DiskError(e.message ?: "can't write the file")
            }
            written += n
            lastDataAt = SystemClock.elapsedRealtime()
            if (seg.count != loggedParts && (seg.count == 1 || seg.count % 5 == 0)) {
                loggedParts = seg.count
                Log.i("NovaTest", "LIVEREC parts ${d.title}: ${seg.count} (${seg.totalSec.toLong()} s, ${seg.skips} damaged left out)")
            }
            if (lastDataAt - lastUi >= 2000) {
                lastUi = lastDataAt
                val s = LiveRec.state.value
                if (s != null && s.id == d.id) LiveRec.state.value = s.copy(bytes = written, seconds = seg.totalSec.toLong(), gaps = gaps)
                DownloadCenter.update(d.id, persist = false) { it.copy(doneBytes = written, durationSec = seg.totalSec.toLong(), gaps = gaps, gapSec = gapSec, parts = seg.count, partsDone = seg.count) }
            }
        }
        try {
            while (running) {
                try {
                    val request = Request.Builder().url(d.url).header("User-Agent", app.storage.userAgent).build()
                    val c = app.http.newCall(request)
                    call = c
                    c.execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
                        val input = (resp.body ?: throw IOException("Empty response")).byteStream()
                        seg.newConnection()
                        val start = java.io.ByteArrayOutputStream()
                        var aligned = false
                        var first = true
                        while (running) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            if (first) {
                                first = false
                                connections++
                                if (connections > 1 && lastDataAt > 0) {
                                    val g = ((SystemClock.elapsedRealtime() - lastDataAt) / 1000).coerceAtLeast(1)
                                    gaps++; gapSec += g
                                    Log.i("NovaTest", "LIVEREC reconnected ${d.title}: gap $g s")
                                    LiveRec.notes.tryEmit("● Connection lost — recording reconnected (gap $g s)")
                                }
                                failures = 0
                            }
                            if (!aligned) {
                                // Keep only what starts at the first real MPEG-TS packet of each connection.
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
                    if (!running) break
                    Log.i("NovaTest", "LIVEREC connection ${d.title}: ${e.message}")
                }
                if (!running) break
                failures++
                try { Thread.sleep(minOf(500L * failures, 4000L)) } catch (e: InterruptedException) { break }
            }
        } catch (e: Exception) {
            Log.w("NovaTest", "LIVEREC failed ${d.title}: ${e.message}")
            val st = LiveRec.state.value
            // The USB / SD drive was removed: what was saved stays; the recording continues on internal storage.
            val driveGone = e is DiskError && File(d.path).parentFile?.exists() != true
            LiveRec.notes.tryEmit(if (driveGone) "● USB drive removed — the recording continues on internal storage" else "● Recording stopped: ${e.message}")
            main.post {
                finish(if (driveGone) "drive removed" else "error: ${e.message}")
                if (driveGone && st != null && st.endsAt > System.currentTimeMillis() + 60_000) main.postDelayed({
                    Log.i("NovaTest", "LIVEREC ${st.title}: drive removed → continues on internal storage")
                    LiveRec.start(this, st.title, st.url, st.accountId, st.icon, st.endsAt, fixedEnd = st.fixedEnd, then = st.then)
                }, 9500)
            }
        } finally {
            runCatching { seg.finish(written) }
            runCatching { out.close() }
            val size = (1..seg.fileNo).sumOf { File(LiveRec.partPath(d.path, it)).length() }
            DownloadCenter.update(d.id) {
                it.copy(status = DownloadStatus.DONE, doneBytes = size, totalBytes = size, durationSec = seg.totalSec.toLong(),
                    gaps = gaps, gapSec = gapSec, parts = seg.count, partsDone = seg.count)
            }
            Log.i("NovaTest", "LIVEREC saved ${d.title}: ${seg.count} parts, ${seg.totalSec.toLong()} s, ${size / 1024} KB, ${seg.skips} damaged parts left out")
        }
    }

    private class DiskError(msg: String) : Exception(msg)

    private fun tsSync(b: ByteArray): Int {
        val last = b.size - 2 * 188 - 1
        for (i in 0..last) if (b[i] == 0x47.toByte() && b[i + 188] == 0x47.toByte() && b[i + 376] == 0x47.toByte()) return i
        return -1
    }
}

/**
 * Cuts the recording into parts at key pictures (about every 4 s) and writes "<offset> <length> <seconds>" lines,
 * so the player knows how long the recording is and can jump anywhere. A part damaged by lost packets ends where the
 * damage starts; the next part begins at the next good key picture (the broken bit is left out).
 */
internal class Segmenter(private val indexFile: File) {
    private val cat = CatWatch()
    private var segStart = -1L
    private var segPts = -1L
    private var segWall = SystemClock.elapsedRealtime()
    private var pendingDisc = false
    private class Line(val off: Long, val len: Long, val sec: Double, val disc: Boolean)
    private val lines = ArrayList<Line>()
    /** Where each file of the recording starts in the stream (FAT32: a new file before 4 GB), and its number. */
    private val files = arrayListOf(0L to 1)
    private var lastEnd = -1L
    /** FAT32 drive: a new file is started at a part boundary once the current file reaches this size. */
    var splitAt = Long.MAX_VALUE
    /** Part length (seconds) once the first 3 short parts are written: 4 s for recordings, 2 s for the Live engine. */
    var partSec = 4.0
    /** 📼 Live engine: a new file every this many seconds (30 min), at a part boundary. */
    var splitSec = Double.MAX_VALUE
    private var fileStartSec = 0.0
    /** Told of each part written to the list: its file number, seconds, and whether it starts a new timeline. */
    var onPart: ((fileNo: Int, sec: Double, disc: Boolean) -> Unit)? = null
    private var keysSeen = 0
    private var bytesPerSec = 0.0
    var totalSec = 0.0
        private set
    var count = 0
        private set
    var skips = 0
        private set

    init {
        cat.onKeyPicture = { at, pts, skipFrom -> onKey(at, pts, skipFrom) }
    }

    fun newConnection() {
        cat.newConnection()
    }

    fun scan(b: ByteArray, from: Int, n: Int, base: Long) {
        runCatching { cat.scan(b, from, n, base) }
        // A video Nova can't read the pictures of: cut by time (about every 6 s) at packet boundaries.
        if (keysSeen == 0 && base + n > 2L * 1024 * 1024) {
            if (segStart < 0) { segStart = 0; segWall = SystemClock.elapsedRealtime() }
            val wall = (SystemClock.elapsedRealtime() - segWall) / 1000.0
            if (wall >= 6.0) {
                val cut = segStart + ((base + n - segStart) / 188) * 188
                if (cut > segStart) { emit(segStart, cut - segStart, wall); segStart = cut; segWall = SystemClock.elapsedRealtime() }
            }
        }
    }

    /** Seconds between two PTS, or -1 when the clock jumped (a long gap that the arrival time doesn't confirm). */
    private fun ptsSecs(a: Long, b: Long, wall: Double): Double {
        if (a < 0 || b < 0) return -1.0
        val d = (b - a) and ((1L shl 33) - 1)
        val s = d / 90_000.0
        if (s <= 30.0) return s
        // Far apart key pictures (long picture groups): fine when about that much time really passed.
        return if (s <= 120.0 && kotlin.math.abs(s - wall) < 10.0) s else -1.0
    }

    /** Start parts at the PAT just before the key picture: each part then carries PAT/PMT (and SPS) itself. */
    private fun cutFor(at: Long): Long {
        val pat = cat.lastPatAt
        return if (pat in (segStart + 1) until at && at - pat <= 64L * 1024) pat else at
    }

    private fun estimate(bytes: Long, wall: Double): Double =
        if (bytesPerSec > 0) bytes / bytesPerSec else wall.coerceIn(0.5, 20.0)

    private fun onKey(at: Long, pts: Long, skipFrom: Long) {
        keysSeen++
        val now = SystemClock.elapsedRealtime()
        if (segStart < 0) { // the part before the first key picture is left out
            val pat = cat.lastPatAt
            segStart = if (pat in 0 until at && at - pat <= 64L * 1024) pat else at
            segPts = pts; segWall = now; return
        }
        if (at <= segStart) return
        val wall = (now - segWall) / 1000.0
        val dur = ptsSecs(segPts, pts, wall)
        val cut = cutFor(at)
        if (skipFrom > segStart) {
            // Damaged: this part ends where the damage starts, the broken bit up to this key picture is left out.
            val total = if (dur >= 0) dur else estimate(at - segStart, wall)
            emit(segStart, skipFrom - segStart, total * (skipFrom - segStart) / (at - segStart))
            skips++
            if (dur < 0) pendingDisc = true
            segStart = if (cut > skipFrom) cut else at; segPts = pts; segWall = now
            return
        }
        if (dur < 0 && segPts >= 0 && pts >= 0) {
            // The clock jumped (new connection, the channel restarted): the next part starts a new timeline.
            emit(segStart, cut - segStart, estimate(cut - segStart, wall))
            pendingDisc = true
            segStart = cut; segPts = pts; segWall = now
            return
        }
        val secs = if (dur >= 0) dur else wall
        // The first parts are short (1 s), so playback can start sooner; then about 4 s each.
        if (secs >= (if (count < 3) 1.0 else partSec)) {
            if (dur > 0) bytesPerSec = if (bytesPerSec == 0.0) (cut - segStart) / dur else bytesPerSec * 0.8 + (cut - segStart) / dur * 0.2
            emit(segStart, cut - segStart, secs)
            segStart = cut; segPts = pts; segWall = now
        }
    }

    private fun emit(off: Long, len: Long, secs: Double) {
        if (len <= 0) return
        val s = secs.coerceIn(0.1, 30.0)
        lines += Line(off, len, s, pendingDisc && count > 0)
        lastEnd = off + len
        pendingDisc = false
        count++
        totalSec += s
    }

    /** Appends the finished parts (call once their bytes are in the file). */
    /** Where a new file could start now (the end of the last part, once the current file is big enough), or -1. */
    fun switchCandidate(): Long =
        if (lastEnd >= 0 && (lastEnd - files.last().first >= splitAt || totalSec - fileStartSec >= splitSec) && lastEnd > files.last().first) lastEnd else -1L

    val fileNo: Int get() = files.last().second
    val fileStart: Long get() = files.last().first

    /** The bytes from stream offset [at] go into file [n]. */
    fun startFile(at: Long, n: Int) { files += at to n; fileStartSec = totalSec }

    fun flush() {
        if (lines.isEmpty()) return
        FileWriter(indexFile, true).use { w ->
            lines.forEach { l ->
                val (start, n) = files.last { it.first <= l.off }
                w.write(String.format(Locale.US, "%d %d %.3f", l.off - start, l.len, l.sec) + (if (l.disc) " d" else "") + (if (n > 1) " p$n" else ""))
                w.write("\n")
                onPart?.invoke(n, l.sec, l.disc)
            }
        }
        lines.clear()
    }

    /** The last part, up to the end of the file. */
    fun finish(end: Long) {
        if (segStart in 0 until end) emit(segStart, end - segStart, estimate(end - segStart, (SystemClock.elapsedRealtime() - segWall) / 1000.0))
        segStart = end
        flush()
    }
}
