package com.nova.iptv.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Where files go.
 *
 *  Download/NovaIPTV/
 *      Buffer/live        rolling live-TV buffer (when "Download folder" is chosen)
 *      Buffer/live-hls    rolling HLS segment cache
 *      Buffer/movies      rolling movie / episode cache
 *      Movies/            downloaded movies
 *      Series/            downloaded episodes
 *      Recordings/        recorded live channels
 *
 * The same layout can live on a USB drive / SD card instead (Settings → Storage folder).
 * If the chosen folder can't be written (drive unplugged, permission refused),
 * everything falls back to internal storage so nothing breaks.
 */
object Folders {

    const val BUFFER_LIVE = "Buffer/live"
    const val BUFFER_LIVE_HLS = "Buffer/live-hls"
    const val BUFFER_MOVIES = "Buffer/movies"
    const val MOVIES = "Movies"
    const val SERIES = "Series"
    const val RECORDINGS = "Recordings"

    fun publicRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NovaIPTV")

    /** Android 9 and older (and 10 in legacy mode) need the storage permission for the Download folder. */
    fun allFilesAccessIntent(context: Context): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:" + context.packageName))

    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Storage folder chosen in Settings (e.g. a USB drive). Null = Download/NovaIPTV on internal storage.
     * Set by the app at start-up and when the setting changes.
     */
    @Volatile
    var customRoot: String? = null

    /** The root actually in use right now (custom folder if it works, otherwise Download/NovaIPTV). */
    fun activeRoot(context: Context): File = Locations.dir(Locations.Kind.MAIN)

    private fun writable(dir: File): Boolean = try {
        if (!dir.isDirectory) dir.mkdirs()
        if (!dir.isDirectory) {
            false
        } else {
            // Prove we can really write here (canWrite() is unreliable on some devices).
            val probe = File(dir, ".probe")
            probe.writeText("ok")
            probe.delete()
            true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * A writable sub-folder of the chosen storage folder (USB drive or Download/NovaIPTV),
     * or null if neither can be written.
     */
    fun publicDir(context: Context, sub: String): File? {
        val custom = customRoot
        if (custom != null) {
            val dir = File(custom, sub)
            if (writable(dir)) return dir
            // Drive unplugged or not writable: fall back to internal storage.
        }
        if (needsStoragePermission(context)) return null
        val dir = File(publicRoot(), sub)
        return if (writable(dir)) dir else null
    }

    /** One choice in the "Storage folder" setting. [path] null = internal Download/NovaIPTV. */
    data class StorageOption(val label: String, val path: String?, val note: String)

    /** True when the app may write anywhere on USB drives / SD cards ("All files access"). */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Internal storage plus every USB drive / SD card that is plugged in now. */
    fun storageOptions(context: Context): List<StorageOption> {
        val list = mutableListOf(
            StorageOption("Internal storage", null, publicRoot().absolutePath)
        )
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        val dirs = context.getExternalFilesDirs(null)
        dirs.drop(1).filterNotNull().forEach { appDir ->
            val removable = runCatching { Environment.isExternalStorageRemovable(appDir) }.getOrDefault(true)
            if (!removable) return@forEach
            val name = (if (android.os.Build.VERSION.SDK_INT >= 24) runCatching { sm.getStorageVolume(appDir)?.getDescription(context) }.getOrNull() else null) ?: "USB / SD drive"
            val driveRoot = appDir.absolutePath.substringBefore("/Android/")
            if (hasAllFilesAccess()) {
                list += StorageOption("$name – NovaIPTV folder", File(driveRoot, "NovaIPTV").absolutePath, "$driveRoot/NovaIPTV")
            }
            list += StorageOption(
                "$name – app folder",
                File(appDir, "NovaIPTV").absolutePath,
                "Works without extra permission. Removed if the app is uninstalled (not on updates).",
            )
        }
        return list
    }

    private fun privateDir(context: Context, sub: String, cache: Boolean): File {
        val base = if (cache) context.cacheDir else (context.getExternalFilesDir(null) ?: context.filesDir)
        return File(base, sub).apply { mkdirs() }
    }

    /** Folder for a rolling buffer / cache, in Download/NovaIPTV if [usePublic] and possible. */
    fun bufferDir(context: Context, sub: String, usePublic: Boolean): File =
        runCatching { File(Locations.dir(Locations.Kind.BUFFERS), sub.removePrefix("Buffer/")).apply { mkdirs() } }
            .getOrNull()?.takeIf { it.isDirectory } ?: privateDir(context, sub, cache = true)

    /** Folder for files the user keeps (downloads, recordings). Always tries Download/NovaIPTV first. */
    fun keepDir(context: Context, sub: String): File = when (sub) {
        MOVIES -> Locations.dir(Locations.Kind.MOVIES)
        SERIES -> Locations.dir(Locations.Kind.SERIES)
        RECORDINGS -> Locations.dir(Locations.Kind.RECORDINGS)
        "Backups" -> Locations.dir(Locations.Kind.BACKUPS)
        "" -> Locations.dir(Locations.Kind.MAIN)
        else -> File(Locations.dir(Locations.Kind.MAIN), sub).apply { mkdirs() }
    }

    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "video" }

    /** A file name in [dir] that doesn't exist yet: "Name.mp4", "Name (2).mp4", … */
    fun uniqueFile(dir: File, baseName: String, ext: String): File {
        val base = safeFileName(baseName)
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) {
            f = File(dir, "$base ($n).$ext")
            n++
        }
        return f
    }
}
