package com.nova.iptv.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Opens channels and videos in another player app (VLC, MX Player, Just Player, Kodi, …). */
object ExternalPlayer {

    data class App(val packageName: String, val label: String)

    /** Video player apps installed on the device. */
    fun installed(context: Context): List<App> {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
        val pm = context.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        }
        return list
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString().trim()) }
            .filter { it.packageName != context.packageName && isPlayer(it) }
            .distinctBy { it.packageName }
            .distinctBy { it.label.lowercase() }
            .sortedWith(compareBy({ !it.label.contains("vlc", ignoreCase = true) }, { it.label.lowercase() }))
    }

    /** Words in the package of apps that say they open videos but are downloaders, stores, file managers, browsers… */
    private val NOT_PLAYERS = listOf(
        "download", "idm", "apkpure", "aptoide", "ghisler", "totalcmd", "filemanager", "file.manager", "fileexplorer",
        "explorer", "x-plore", "lonelycatgames", "solidexplorer", "cast.webvideo", "instantbits", "browser", "chrome",
        "firefox", "gallery", "photos", "mixplorer", "es.file", "estrongs", "share", "bluetooth", "nfc", "drive",
        "dropbox", "telegram", "whatsapp", "gmail", "email", "messag",
    )

    /** Only real video players: no downloaders, stores, file managers, "None" or a second nameless "Video player". */
    private fun isPlayer(a: App): Boolean {
        val pkg = a.packageName.lowercase()
        val label = a.label.lowercase()
        if (label.isBlank() || label == "none" || label == "video player" || label == "video") return false
        return NOT_PLAYERS.none { pkg.contains(it) }
    }

    fun open(context: Context, item: PlayItem, packageName: String?): Boolean {
        val uri = when {
            item.url.startsWith("file:") -> fileUri(context, File(Uri.parse(item.url).path ?: return false))
            item.url.startsWith("/") -> fileUri(context, File(item.url))
            else -> Uri.parse(item.url)
        }
        val mime = when {
            item.isHls -> "application/x-mpegURL"
            item.url.substringBefore('?').endsWith(".ts", true) -> "video/mp2t"
            else -> "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .putExtra("title", item.title) // VLC, MX Player, Just Player
            .putExtra(Intent.EXTRA_TITLE, item.title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            if (packageName != null) {
                intent.setPackage(packageName)
                context.startActivity(intent)
            } else {
                context.startActivity(
                    Intent.createChooser(intent, "Play with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            true
        } catch (e: ActivityNotFoundException) {
            if (packageName != null) {
                // The chosen app was removed: fall back to Android's chooser.
                return open(context, item, null)
            }
            Toast.makeText(context, "No external video player is installed (try VLC or Just Player).", Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open the external player: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
}
