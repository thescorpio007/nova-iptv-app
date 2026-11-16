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
        /** How Nova found it (for the report): volumes, app-folders, mounts, storage. */
        val how: String = "",
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
     * backups and buffers "Same as main", films sorted by rating, 1 GB disk read-ahead.
     */
    private fun applyHouseDefaults() {
        if (storage.has(HOUSE_DEFAULTS)) return
        set(Kind.MAIN, Location("internal", "Download/NovaIPTV"))
        set(Kind.BACKUPS, Location("main"))
        set(Kind.BUFFERS, Location("main"))
        storage.setSortFor("movie", "rating")
        storage.readAheadMb = 1000
        storage.markDone(HOUSE_DEFAULTS)
        android.util.Log.i("NovaTest", "DEFAULTS applied: internal storage, rating sort, 1 GB read-ahead")
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

    /**
     * Every drive that isn't the box's own storage, found 4 ways (TV boxes often leave USB drives out of Android's
     * app-folder list): Android's storage volumes, the app-folder list, the system's mount table and the /storage
     * folder. Each drive is listed once. Boot / EFI partitions (e.g. VTOYEFI) and anything under 1 GB are left out.
     */
    fun drives(): List<Drive> {
        val list = mutableListOf<Drive>()
        val internalRoot = Environment.getExternalStorageDirectory()
        val internalApp = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        list += Drive("internal", "Internal storage", internalRoot, internalApp, removable = false)
        val found = LinkedHashMap<String, Drive>()
        val sm = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val pkgDir = "Android/data/${appContext.packageName}/files"
        fun add(root: File, appDir: File?, name: String?, how: String) {
            val path = root.absolutePath.trimEnd('/')
            if (path.isBlank() || path == internalRoot.absolutePath.trimEnd('/') || path.startsWith("/storage/emulated") || path == "/storage/self") return
            val id = root.name
            if (id.isBlank() || id == "emulated" || id == "self") return
            val prev = found[id]
            if (prev != null) {
                // Same drive found again: keep the best name and the app folder Android created.
                found[id] = prev.copy(
                    systemName = if (prev.systemName == "USB / SD drive" && name != null) name else prev.systemName,
                    appDir = if (appDir != null && appDir.isDirectory) appDir else prev.appDir,
                    how = prev.how + "+" + how,
                )
                return
            }
            found[id] = Drive(id, name ?: "USB / SD drive", File(path), appDir ?: File(path, pkgDir), removable = true, how = how)
        }
        // 1. Android's storage volumes (names like "TV-USBDrive").
        if (android.os.Build.VERSION.SDK_INT >= 24) runCatching {
            sm.storageVolumes.forEach { v ->
                if (v.isPrimary) return@forEach
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
                val dir = if (android.os.Build.VERSION.SDK_INT >= 30) v.directory
                else runCatching { v.javaClass.getMethod("getPathFile").invoke(v) as File }.getOrNull()
                    ?: runCatching { File(v.javaClass.getMethod("getPath").invoke(v) as String) }.getOrNull()
                if (dir != null) add(dir, null, runCatching { v.getDescription(appContext) }.getOrNull(), "volumes")
            }
        }
        // 2. The app-folder list (also gives the drive's app folder, usable without "All files access").
        appContext.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { appDir ->
            val root = File(appDir.absolutePath.substringBefore("/Android/"))
            val name = if (android.os.Build.VERSION.SDK_INT >= 24) runCatching { sm.getStorageVolume(appDir)?.getDescription(appContext) }.getOrNull() else null
            add(root, appDir, name, "app-folders")
        }
        // 3. The mount table: /mnt/media_rw/<id> (the real drive) shown to apps as /storage/<id>.
        runCatching {
            File("/proc/mounts").readLines().forEach { line ->
                val p = line.split(" ")
                if (p.size < 3) return@forEach
                val mp = p[1].replace("\\040", " ")
                val id = when {
                    mp.startsWith("/mnt/media_rw/") -> mp.removePrefix("/mnt/media_rw/")
                    mp.startsWith("/storage/") -> mp.removePrefix("/storage/")
                    else -> return@forEach
                }.substringBefore('/')
                if (id.isBlank() || id == "emulated" || id == "self") return@forEach
                val visible = File("/storage", id)
                if (visible.isDirectory) add(visible, null, null, "mounts")
            }
        }
        // 4. The /storage folder itself.
        runCatching {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "emulated" && f.name != "self" && runCatching { f.canonicalPath.startsWith("/storage/emulated") }.getOrDefault(false).not())
                    add(f, null, null, "storage")
            }
        }
        found.values.forEach { d ->
            val total = d.total()
            val name = nameOf(d).uppercase()
            val boot = name.contains("EFI") || name.contains("VTOY") || name == "BOOT" || name == "SYSTEM RESERVED"
            if (boot || (total in 0 until 1024L * 1024 * 1024)) {
                if (leftOutLogged.add(d.id)) android.util.Log.i("NovaTest", "DRIVES left out ${d.id} (${d.systemName}, ${total / 1048576} MB): ${if (boot) "boot partition" else "under 1 GB"}")
                return@forEach
            }
            list += d
        }
        val txt = list.joinToString(" | ") { "${it.id} ${it.systemName} ${fileSystem(it) ?: ""} [${it.how}]" }
        if (txt != lastDrivesLog) { lastDrivesLog = txt; android.util.Log.i("NovaTest", "DRIVES $txt") }
        return list
    }

    @Volatile private var lastDrivesLog = ""
    private val leftOutLogged = java.util.Collections.synchronizedSet(HashSet<String>())
    /** For the playback report: every drive found and how. */
    val drivesReport: String get() = lastDrivesLog

    /** "TV-USBDrive · FAT32 · 412 GB free · ✓ writable" (or what is wrong). */
    fun statusOf(d: Drive): String {
        val fs = fileSystem(d)?.let { fsLabel(it) }
        val free = d.free()
        val freeTxt = if (free >= 0) "%.1f GB free".format(free / 1073741824.0) else null
        val ro = readOnly(d)
        val canRoot = writable(File(d.root, "NovaIPTV"))
        val state = when {
            ro -> "✕ read-only (the box mounted it read-only${if (fs == "NTFS") ": NTFS" else ""}) — not used"
            canRoot || hasAllFilesAccess() -> "✓ writable"
            writable(d.appDir) -> "✓ writable (in the drive's app folder; allow All files access for any folder)"
            else -> "✕ can't write — allow All files access"
        }
        return listOfNotNull(nameOf(d), fs, freeTxt, state).joinToString(" · ") +
            (if (fs == "FAT32") "\nFAT32: 4 GB per file → films over 4 GB go to internal storage; recordings continue in a new file" else "")
    }

    private fun fsLabel(fs: String): String = when (fs.lowercase()) {
        "vfat", "msdos", "fat", "fat32" -> "FAT32"
        "exfat", "texfat", "sdfat" -> "exFAT"
        "ntfs", "ntfs3", "tntfs", "fuseblk", "ufsd" -> "NTFS"
        "ext4", "ext3", "ext2" -> fs.uppercase()
        "f2fs" -> "F2FS"
        else -> fs
    }

    /** Mounted read-only by the box (typical for NTFS on Android). */
    fun readOnly(d: Drive): Boolean = runCatching {
        mountLine(d)?.split(" ")?.getOrNull(3)?.split(",")?.contains("ro") == true
    }.getOrDefault(false)

    private fun mountLine(d: Drive): String? = runCatching {
        val lines = File("/proc/mounts").readLines().filter { l ->
            val p = l.split(" ")
            p.size > 2 && (p[1].endsWith("/${d.id}") || p[0].contains(d.id))
        }
        // The real drive (not the fuse / sdcardfs layer above it).
        lines.firstOrNull { it.split(" ")[2] !in setOf("fuse", "sdcardfs", "esdfs") } ?: lines.firstOrNull()
    }.getOrNull()

    const val FAT32_MAX = 4L * 1024 * 1024 * 1024 - 1

    /** You chose a USB / SD drive for [kind] (directly, or through the main location). */
    fun wantsUsb(kind: Kind): Boolean {
        val loc = get(kind)
        val drive = if (loc.drive == "main" && kind != Kind.MAIN) get(Kind.MAIN).drive else loc.drive
        return drive == "any_usb" || drive.startsWith("drive:")
    }

    fun isFat32(d: Drive): Boolean = d.removable && fileSystem(d)?.let { fsLabel(it) } == "FAT32"

    /** The USB / SD drive a file is on (null: internal storage). */
    fun driveOf(path: String): Drive? = drives().filter { it.removable }.firstOrNull { d ->
        path.startsWith(d.root.absolutePath + "/") || path.startsWith(d.appDir.absolutePath + "/")
    }

    fun isFat32Path(path: String): Boolean = runCatching { driveOf(path)?.let { isFat32(it) } == true }.getOrDefault(false)

    /** The same kind of place on internal storage (for files a FAT32 drive can't hold). */
    fun internalDirFor(kind: Kind, sub: String): File {
        val internal = drives().first { !it.removable }
        val base = File(File(internal.root, "Download/NovaIPTV"), kind.defaultSub)
        val dir = if (sub.isBlank()) base else File(base, sub)
        if (writable(dir)) return dir
        return File(internal.appDir, kind.defaultSub + (if (sub.isBlank()) "" else "/$sub")).apply { mkdirs() }
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
        return mountLine(d)?.split(" ")?.getOrNull(2)
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
