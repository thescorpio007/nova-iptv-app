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

    /** One app in Settings › External players: [player] = a real video player; [visible] = shown in "Play with…". */
    data class Entry(val app: App, val player: Boolean, val visible: Boolean)

    private fun storage(context: Context) = (context.applicationContext as com.nova.iptv.NovaApp).storage

    /** Video player apps installed on the device, in your order, without the ones you hid (no downloaders unless you allowed them). */
    fun installed(context: Context): List<App> = entries(context).filter { it.visible }.map { it.app }

    /** Every app that says it opens videos, in your order: real players first by default, others hidden unless switched on. */
    fun entries(context: Context): List<Entry> {
        val st = storage(context)
        val hidden = st.extHidden
        val shown = st.extShown
        val all = candidates(context).map { a ->
            val player = isPlayer(a)
            Entry(a, player, if (player) a.packageName !in hidden else a.packageName in shown)
        }
        val order = st.extOrder
        return all.sortedWith(
            compareBy<Entry>(
                { order.indexOf(it.app.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                { !it.player },
                { !it.app.label.contains("vlc", ignoreCase = true) },
                { it.app.label.lowercase() },
            )
        )
    }

    /** Moves an app one place up (-1) or down (+1) in your order. */
    fun move(context: Context, pkg: String, delta: Int) {
        val list = entries(context).map { it.app.packageName }.toMutableList()
        val i = list.indexOf(pkg); val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.removeAt(i); list.add(j, pkg)
        storage(context).extOrder = list
    }

    /** Puts an app at the top of your order ("Always use this one"). */
    fun makeFirst(context: Context, pkg: String) {
        val list = entries(context).map { it.app.packageName }.filter { it != pkg }
        storage(context).extOrder = listOf(pkg) + list
    }

    fun setVisible(context: Context, e: Entry, visible: Boolean) {
        val st = storage(context)
        val pkg = e.app.packageName
        if (e.player) st.extHidden = if (visible) st.extHidden - pkg else st.extHidden + pkg
        else st.extShown = if (visible) st.extShown + pkg else st.extShown - pkg
    }

    /** Every installed app that answers "open a video" (players, downloaders, file managers…), Nova excluded. */
    private fun candidates(context: Context): List<App> {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
        val pm = context.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        }
        // Players also registered for streaming lists (.m3u8) and .ts files, which some apps only declare for those.
        val more = listOf("application/x-mpegURL", "video/mp2t").flatMap { type ->
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://example.com/video"), type)
            runCatching {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
            }.getOrDefault(emptyList())
        }
        return (list + more)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString().trim()) }
            .filter { it.packageName != context.packageName && it.label.isNotBlank() && it.label.lowercase() != "none" }
            .distinctBy { it.packageName }
            .distinctBy { it.label.lowercase() }
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
                // Nova's own "Play with…" list (your order, only real players) instead of Android's chooser,
                // which opens a downloader (1DM…) directly when it is the only app accepting the video.
                val apps = installed(context)
                when {
                    apps.isEmpty() -> {
                        Toast.makeText(context, "No video player is installed — install VLC or Just Player (Settings › Players › External players).", Toast.LENGTH_LONG).show()
                        return false
                    }
                    storage(context).extMode == "first" -> return open(context, item, apps.first().packageName)
                    else -> {
                        PlayWithActivity.pending = item
                        context.startActivity(Intent(context, PlayWithActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            true
        } catch (e: ActivityNotFoundException) {
            if (packageName != null) {
                // The chosen app was removed: fall back to the "Play with…" list.
                return open(context, item, null)
            }
            Toast.makeText(context, "No video player is installed — install VLC or Just Player.", Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open the external player: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
}
