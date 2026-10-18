package com.nova.iptv.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where Nova keeps files, saved by MEANING so it works on any Android box with any USB / SD drive:
 *  - which drive: internal storage, "any USB / SD drive", or one particular drive (remembered by its id);
 *  - which folder inside that drive (relative, e.g. "NovaIPTV/Films").
 * Every kind of file (films, series, recordings, backups, buffers) can have its own location,
 * or follow the main one. If a drive is missing or full, the next one in the drive order is used,
 * then internal storage — nothing ever breaks.
 */
object Locations {

    enum class Kind(val label: String, val defaultSub: String) {
        MAIN("Main location", ""),
        MOVIES("Films", "Movies"),
        SERIES("Series", "Series"),
        RECORDINGS("Recordings", "Recordings"),
        BACKUPS("Backups (.bak)", "Backups"),
        BUFFERS("Buffers (read-ahead, live)", "Buffer"),
    }

    /** drive: "main" (follow the main location), "internal", "any_usb", "cache" (app folder, buffers only) or "drive:<id>". */
    @Serializable
    data class Location(
        val drive: String = "main",
        val folder: String = "",
        /** Use the drive's own app folder (works without "All files access"; removed if Nova is uninstalled). */
        val appFolder: Boolean = false,
    )

    /** A storage volume found now. */
    data class Drive(
        /** "internal" or the volume id (e.g. "64E9-15F8"). */
        val id: String,
        val systemName: String,
        val root: File,
        val appDir: File,
        val removable: Boolean,
    ) {
        fun free(): Long = runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(-1)
        fun total(): Long = runCatching { StatFs(root.absolutePath).totalBytes }.getOrDefault(-1)
    }

    private lateinit var storage: Storage
    private lateinit var appContext: Context
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), Location.serializer())

    fun init(context: Context, storage: Storage) {
        this.appContext = context.applicationContext
        this.storage = storage
        migrate()
        applyHouseDefaults()
    }

    /**
     * Once (also after restoring an older backup): internal storage Download/NovaIPTV as the main place,
     * backups and buffers "Same as main", films sorted by rating, 100 MB disk read-ahead.
     */
    private fun applyHouseDefaults() {
        if (storage.has(HOUSE_DEFAULTS)) return
        set(Kind.MAIN, Location("internal", "Download/NovaIPTV"))
        set(Kind.BACKUPS, Location("main"))
        set(Kind.BUFFERS, Location("main"))
        storage.setSortFor("movie", "rating")
        storage.readAheadMb = 100
        storage.markDone(HOUSE_DEFAULTS)
        android.util.Log.i("NovaTest", "DEFAULTS applied: internal storage, rating sort, 100 MB read-ahead")
    }

    private const val HOUSE_DEFAULTS = "house_defaults_1"

    // ------------------------------------------------------------------ saved settings

    fun all(): Map<Kind, Location> {
        val raw = storage.locationsJson
        val saved = raw?.let { runCatching { json.decodeFromString(mapSer, it) }.getOrNull() }.orEmpty()
        return Kind.entries.associateWith { k -> saved[k.name] ?: defaultFor(k) }
    }

    fun get(kind: Kind): Location = all().getValue(kind)

    fun set(kind: Kind, loc: Location) {
        val m = all().toMutableMap()
        m[kind] = loc
        storage.locationsJson = json.encodeToString(mapSer, m.mapKeys { it.key.name })
    }

    private fun defaultFor(k: Kind): Location = when (k) {
        // Nova's default: the box's internal storage, Download/NovaIPTV; everything else follows it.
        Kind.MAIN -> Location("internal", "Download/NovaIPTV")
        else -> Location("main")
    }

    /** Older versions saved one exact path; turn it into a location that works with any drive. */
    private fun migrate() {
        if (storage.locationsJson != null) return
        // Buffers: keep the older "App storage / Storage folder" choice if it was made.
        if (storage.has("use_public_folder")) set(Kind.BUFFERS, Location(if (storage.usePublicFolder) "main" else "cache"))
        val old = storage.storageRoot ?: return
        val main = when {
            old.contains("/Android/data/") -> Location("any_usb", "NovaIPTV", appFolder = true)
            old.startsWith("/storage/emulated/") -> Location("internal", old.substringAfter("/storage/emulated/0/", "Download/NovaIPTV"))
            else -> Location("any_usb", old.substringAfter("/storage/").substringAfter('/', "NovaIPTV").ifBlank { "NovaIPTV" })
        }
        set(Kind.MAIN, main)
    }

    // ------------------------------------------------------------------ drives

    fun drives(): List<Drive> {
        val list = mutableListOf<Drive>()
        val internalRoot = Environment.getExternalStorageDirectory()
        val internalApp = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        list += Drive("internal", "Internal storage", internalRoot, internalApp, removable = false)
        val sm = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        appContext.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { appDir ->
            val removable = runCatching { Environment.isExternalStorageRemovable(appDir) }.getOrDefault(true)
            if (!removable) return@forEach
            val root = File(appDir.absolutePath.substringBefore("/Android/"))
            // Android 5 / 6 can't name the drive: its folder name is used instead.
            val vol = if (android.os.Build.VERSION.SDK_INT >= 24) runCatching { sm.getStorageVolume(appDir) }.getOrNull() else null
            val id = (if (android.os.Build.VERSION.SDK_INT >= 24) vol?.uuid else null) ?: root.name
            val sys = (if (android.os.Build.VERSION.SDK_INT >= 24) runCatching { vol?.getDescription(appContext) }.getOrNull() else null) ?: "USB / SD drive"
            list += Drive(id, sys, root, appDir, removable = true)
        }
        return list
    }

    /** Your name for a drive ("Films 1TB"), or the system's ("SanDisk SD card"). */
    fun nameOf(d: Drive): String = storage.driveNames[d.id] ?: d.systemName

    fun rename(id: String, name: String) {
        storage.driveNames = if (name.isBlank()) storage.driveNames - id else storage.driveNames + (id to name.trim())
    }

    /** USB / SD drives in your order (drives never ordered come after, as Android lists them). */
    fun usbInOrder(): List<Drive> {
        val usb = drives().filter { it.removable }
        val order = storage.driveOrder
        return usb.sortedBy { d -> order.indexOf(d.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    fun moveDrive(id: String, delta: Int) {
        val ids = usbInOrder().map { it.id }.toMutableList()
        storage.driveOrder.filter { it !in ids }.let { ids += it }
        val i = ids.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= ids.size) return
        val t = ids[i]; ids[i] = ids[j]; ids[j] = t
        storage.driveOrder = ids
    }

    /** "vfat" = FAT32 (max 4 GB per file), "exfat", "ntfs"… (null if unknown). */
    fun fileSystem(d: Drive): String? {
        if (!d.removable) return null
        return runCatching {
            File("/proc/mounts").readLines().firstOrNull { line ->
                val parts = line.split(" ")
                parts.size > 2 && (parts[1].endsWith("/${d.id}") || parts[0].contains(d.id))
            }?.split(" ")?.getOrNull(2)
        }.getOrNull()
    }

    fun hasAllFilesAccess(): Boolean = Folders.hasAllFilesAccess()

    // ------------------------------------------------------------------ resolving

    data class Resolved(val dir: File, val drive: Drive?, val fellBack: Boolean, val note: String?)

    /** The folder actually used for [kind] right now (created if needed). */
    fun dir(kind: Kind): File = resolve(kind).dir

    fun resolve(kind: Kind): Resolved {
        val loc = get(kind)
        if (kind == Kind.BUFFERS && loc.drive == "cache") {
            return Resolved(File(appContext.cacheDir, "Buffer").apply { mkdirs() }, null, false, null)
        }
        // "Same as main": the main folder + the usual sub-folder (Movies, Series…).
        val (drive, folder, appFolder) = if (loc.drive == "main" && kind != Kind.MAIN) {
            val main = get(Kind.MAIN)
            Triple(main.drive, joinPath(main.folder, kind.defaultSub), main.appFolder)
        } else {
            Triple(loc.drive, loc.folder, loc.appFolder)
        }
        val drives = drives()
        val wanted: List<Drive> = when {
            drive == "internal" -> drives.filter { !it.removable }
            drive == "any_usb" -> usbInOrder()
            drive.startsWith("drive:") -> {
                val id = drive.removePrefix("drive:")
                drives.filter { it.id == id } + usbInOrder().filter { it.id != id }
            }
            else -> drives.filter { !it.removable }
        }
        wanted.forEachIndexed { i, d ->
            val useApp = appFolder || (d.removable && !hasAllFilesAccess())
            val dir = if (useApp) File(d.appDir, folder.ifBlank { "NovaIPTV" }) else File(d.root, folder)
            if (writable(dir)) {
                val note = if (i > 0) "Using ${nameOf(d)} (the chosen drive isn't available)" else null
                return Resolved(dir, d, i > 0, note)
            }
        }
        // Last resort: internal Download/NovaIPTV, then the app's own folder.
        val internal = drives.first { !it.removable }
        val sub = if (kind == Kind.MAIN) "" else kind.defaultSub
        val fallback = File(File(internal.root, "Download/NovaIPTV"), sub)
        val why = if (wanted.isEmpty()) "No USB / SD drive plugged in — using internal storage" else "Using internal storage (the chosen place can't be written)"
        if (writable(fallback)) return Resolved(fallback, internal, true, why)
        val private = File(internal.appDir, sub).apply { mkdirs() }
        return Resolved(private, internal, true, "Using the app folder on internal storage")
    }

    private fun joinPath(a: String, b: String) = listOf(a.trim('/'), b.trim('/')).filter { it.isNotBlank() }.joinToString("/")

    fun writable(dir: File): Boolean = try {
        if (!dir.isDirectory) dir.mkdirs()
        if (!dir.isDirectory) false else {
            val probe = File(dir, ".probe")
            probe.writeText("ok")
            probe.delete()
            true
        }
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ file organisation

    /** Sub-folder for a download, from Settings → Storage → File organisation. */
    fun subFolderFor(kind: Kind, show: String? = null, season: Int? = null, title: String? = null): String {
        val safe = { s: String -> Folders.safeFileName(s) }
        return when (kind) {
            Kind.SERIES -> when (storage.seriesLayout) {
                "show" -> show?.let { safe(it) }.orEmpty()
                "show_season" -> listOfNotNull(show?.let { safe(it) }, season?.let { "Season $it" }).joinToString("/")
                else -> ""
            }
            Kind.MOVIES -> if (storage.filmLayout == "folder" && title != null) safe(title) else ""
            Kind.RECORDINGS -> if (storage.recordingLayout == "channel" && title != null) safe(title) else ""
            else -> ""
        }
    }

    fun dirFor(kind: Kind, sub: String): File {
        val base = dir(kind)
        return if (sub.isBlank()) base else File(base, sub).apply { mkdirs() }
    }
}
