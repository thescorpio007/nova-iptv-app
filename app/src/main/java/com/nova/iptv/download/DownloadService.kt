package com.nova.iptv.download

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nova.iptv.NovaApp
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.ui.MainActivity
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads movies and episodes: one at a time from your IPTV servers (they usually allow few connections)
 * and, at the same time, one at a time from other web links,
 * resuming where it stopped if the connection drops.
 */
class DownloadService : Service() {

    private val main = Handler(Looper.getMainLooper())
    /** One worker per IPTV server ("srv:<account>") and up to 4 for other links ("link#1" …). */
    private val workers = HashMap<String, Thread>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Preparing download…", -1, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        main.post { startWorkers() }
        return START_NOT_STICKY
    }

    /** Main thread only. */
    private fun startWorkers() {
        val queued = DownloadCenter.items.value.filter { it.status == DownloadStatus.QUEUED }
        val lanes = queued.map { DownloadCenter.laneOf(it.url) }
        // Each server: one worker.
        lanes.filter { it.startsWith("srv:") }.distinct().forEach { lane ->
            if (workers[lane] == null) workers[lane] = Thread({ runQueue(lane, lane) }, "NovaDl-$lane").also { it.start() }
        }
        // Links: as many workers as waiting links, up to 4.
        var waitingLinks = lanes.count { it == "link" }
        for (slot in 1..DownloadCenter.LINK_SLOTS) {
            if (waitingLinks <= 0) break
            val key = "link#$slot"
            if (workers[key] == null) {
                workers[key] = Thread({ runQueue(key, "link") }, "NovaDl-$key").also { it.start() }
                waitingLinks--
            }
        }
        if (workers.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun runQueue(key: String, lane: String) {
        val app = application as NovaApp
        while (true) {
            val item = DownloadCenter.claimNext(lane) ?: break
            runCatching { download(app, item) }
        }
        main.post {
            workers.remove(key)
            startWorkers()
        }
    }

    /** The account (IPTV server) a download comes from, or null for other links. */
    private fun accountOf(app: NovaApp, url: String): com.nova.iptv.data.Account? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        return app.storage.accounts.firstOrNull { a ->
            runCatching { java.net.URI(com.nova.iptv.data.XtreamApi.normalizeServer(a.server)).host }.getOrNull() == host
        }
    }

    /** Connections for this download: your choice, but never more than the provider allows (one is kept for watching). */
    private fun connectionsFor(app: NovaApp, url: String): Int {
        val s = app.storage
        val acc = accountOf(app, url) ?: return s.dlConnLinks.coerceIn(1, 16)
        val allowed = s.maxConnections(acc.id)
        val cap = if (allowed > 1) allowed - 1 else 1
        return s.dlConnServers.coerceIn(1, 16).coerceAtMost(cap)
    }

    private fun speedFor(app: NovaApp, url: String): SpeedLimit {
        val mb = if (accountOf(app, url) != null) app.storage.dlSpeedServers else app.storage.dlSpeedLinks
        return SpeedLimit(mb * 1024L * 1024L)
    }

    private fun download(app: NovaApp, first: DownloadItem) {
        // A file left by an earlier install can't be opened again (Permission denied): use another name.
        val usable = Sidecar.usablePath(first.path)
        val start = if (usable == first.path) first else {
            android.util.Log.i("NovaTest", "DOWNLOAD old file not writable → ${File(usable).name}")
            DownloadCenter.update(first.id) { it.copy(path = usable, note = "The old file could not be written (made by an earlier install) → saved as ${File(usable).name}") }
            first.copy(path = usable)
        }
        val conns = connectionsFor(app, start.url)
        val limit = speedFor(app, start.url)
        if (HlsDownload.isHls(start.url)) {
            HlsDownload(app, start, conns, limit) { title, total, done -> notifyProgress(title, total, done) }.run()
            return
        }
        // Several connections: pieces in watching order (falls back to one connection if the server refuses).
        val inPieces = Sidecar.file(start.path, "pieces").exists()
        // Pieces also with ONE connection (one after the other): the film's beginning and end come first,
        // so "Watch now" can start even when the index of an MP4 is at the end of the file.
        val refused = start.note?.contains("refused") == true
        if ((inPieces || !refused) &&
            PieceDownload(app, start, conns, limit) { title, total, done -> notifyProgress(title, total, done) }.run()
        ) return
        DownloadCenter.update(start.id, persist = false) { it.copy(connections = 1) }
        val id = start.id
        val file = File(start.path)
        file.parentFile?.mkdirs()
        var attempt = 0
        DownloadCenter.update(id) { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }

        while (true) {
            if (DownloadCenter.get(id)?.status != DownloadStatus.DOWNLOADING) return
            val have = if (file.exists()) file.length() else 0L
            try {
                val request = Request.Builder()
                    .url(start.url)
                    .header("User-Agent", app.storage.userAgent)
                    .apply { if (have > 0) header("Range", "bytes=$have-") }
                    .build()
                app.http.newCall(request).execute().use { resp ->
                    if (resp.code == 416 && have > 0) {
                        DownloadCenter.update(id) {
                            it.copy(status = DownloadStatus.DONE, doneBytes = have, totalBytes = have, error = null)
                        }
                        return
                    }
                    if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty response")
                    val append = resp.code == 206 && have > 0
                    var done = if (append) have else 0L
                    val length = body.contentLength()
                    val total = if (length > 0) done + length else -1L

                    FileOutputStream(file, append).use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(128 * 1024)
                        var lastUi = 0L
                        var lastDone = done
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            limit.take(n)
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 1000) {
                                lastUi = now
                                if (DownloadCenter.get(id)?.status != DownloadStatus.DOWNLOADING) return
                                val speedNow = done - lastDone
                                lastDone = done
                                DownloadCenter.update(id, persist = false) {
                                    it.copy(doneBytes = done, totalBytes = total, speed = speedNow, error = null)
                                }
                                notifyProgress(start.title, total, done)
                            }
                        }
                    }
                    if (total > 0 && done < total) throw IOException("Connection closed early")
                    DownloadCenter.update(id) {
                        it.copy(status = DownloadStatus.DONE, doneBytes = done, totalBytes = if (total > 0) total else done, error = null)
                    }
                    return
                }
            } catch (e: Exception) {
                if (DownloadCenter.get(id)?.status != DownloadStatus.DOWNLOADING) return
                attempt++
                val message = e.message ?: "Download failed"
                if (attempt >= 8) {
                    DownloadCenter.update(id) {
                        it.copy(status = DownloadStatus.FAILED, error = message, doneBytes = file.length())
                    }
                    return
                }
                DownloadCenter.update(id) { it.copy(error = "Retrying… ($message)", doneBytes = file.length()) }
                try {
                    Thread.sleep(minOf(2000L * attempt, 15_000L))
                } catch (ie: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun notification(text: String, total: Long, done: Long) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Nova IPTV download")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .apply {
                if (total > 0) setProgress(1000, (done * 1000 / total).toInt(), false)
                else setProgress(0, 0, true)
            }
            .build()

    private fun notifyProgress(title: String, total: Long, done: Long) {
        val text = if (total > 0) "$title · ${done * 100 / total}%" else "$title · ${done / (1024 * 1024)} MB"
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text, total, done))
        }
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
