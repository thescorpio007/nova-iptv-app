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
 * Downloads movies and episodes one at a time (IPTV accounts usually allow few connections),
 * resuming where it stopped if the connection drops.
 */
class DownloadService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

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
        if (worker == null) startWorker()
        return START_NOT_STICKY
    }

    private fun startWorker() {
        worker = Thread({ runQueue() }, "NovaDownloads").also { it.start() }
    }

    private fun runQueue() {
        val app = application as NovaApp
        while (true) {
            val item = DownloadCenter.nextQueued() ?: break
            download(app, item)
        }
        main.post {
            worker = null
            if (DownloadCenter.nextQueued() != null) {
                startWorker()
            } else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun download(app: NovaApp, start: DownloadItem) {
        if (HlsDownload.isHls(start.url)) {
            HlsDownload(app, start) { title, total, done -> notifyProgress(title, total, done) }.run()
            return
        }
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
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 1000) {
                                lastUi = now
                                if (DownloadCenter.get(id)?.status != DownloadStatus.DOWNLOADING) return
                                DownloadCenter.update(id, persist = false) {
                                    it.copy(doneBytes = done, totalBytes = total, error = null)
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
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, total, done))
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
