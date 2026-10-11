package com.nova.iptv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nova.iptv.data.Locations
import com.nova.iptv.data.Locations.Kind
import com.nova.iptv.data.Locations.Location
import java.io.File

private fun gb(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    else -> "${bytes / (1024 * 1024)} MB"
}

/** Opens Android's "All files access" screen for Nova. */
fun openAllFilesAccess(context: Context) {
    val pkg = Uri.parse("package:${context.packageName}")
    val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
    }.isSuccess || runCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }.isSuccess
    if (!opened) {
        Toast.makeText(
            context,
            "This box has no 'All files access' screen. Grant it with ADB: " +
                "adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
            Toast.LENGTH_LONG,
        ).show()
    }
}

/** Settings → Storage: locations that work on any box, drive names and order, file organisation. */
@Composable
fun StorageSettings(vm: MainViewModel) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Re-read drives when coming back (e.g. after allowing All files access or plugging a USB stick).
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { version++ } }
    val drives = remember(version) { Locations.drives() }
    val locs = remember(version) { Locations.all() }
    val access = remember(version) { Locations.hasAllFilesAccess() }
    var showSeparate by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf<Kind?>(null) }
    var renaming by remember { mutableStateOf<Locations.Drive?>(null) }
    val offer by vm.moveOffer.collectAsStateWithLifecycle()
    val progress by vm.moveProgress.collectAsStateWithLifecycle()
    val storage = vm.appStorage

    fun apply(kind: Kind, loc: Location) {
        vm.setLocation(kind, loc)
        version++
    }

    SettingsCard("Storage") {
        Hint(
            "Places are saved by meaning (internal, any USB / SD drive, or one particular drive) plus a folder inside it, " +
                "so they keep working on any Android box and with any USB stick. If a drive is missing or full, " +
                "the next drive in your order is used, then internal storage."
        )
        LocationRow(Kind.MAIN, locs.getValue(Kind.MAIN), drives, access, onChange = { apply(Kind.MAIN, it) }, onBrowse = { browsing = Kind.MAIN }, version = version)

        FocusBox(onClick = { showSeparate = !showSeparate }, modifier = Modifier.fillMaxWidth(), selected = showSeparate) { focused ->
            Text(
                (if (showSeparate) "▾  " else "▸  ") + "Separate place for films, series, recordings, backups, buffers",
                modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Medium,
                color = if (showSeparate || focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (showSeparate) {
            listOf(Kind.MOVIES, Kind.SERIES, Kind.RECORDINGS, Kind.BACKUPS, Kind.BUFFERS).forEach { k ->
                LocationRow(k, locs.getValue(k), drives, access, onChange = { apply(k, it) }, onBrowse = { browsing = k }, version = version)
            }
        }
        progress?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }

        if (!access && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Hint(
                "Without “All files access”, Nova can only use each USB / SD drive's own app folder (deleted if Nova is uninstalled) " +
                    "and the Download folder on internal storage. Allow it once on each box to use any folder."
            )
            OutlinedButton(onClick = { openAllFilesAccess(context) }) { Text("Allow All files access") }
        }
    }

    SettingsCard("Drives") {
        Hint("Give drives your own names. With several USB / SD drives, the order decides which one “Any USB / SD drive” uses first.")
        val usb = remember(version) { Locations.usbInOrder() }
        (drives.filter { !it.removable } + usb).forEach { d ->
            val fs = remember(d.id, version) { Locations.fileSystem(d) }
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(Locations.nameOf(d), fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        if (Locations.nameOf(d) != d.systemName) d.systemName else null,
                        "${gb(d.free())} free of ${gb(d.total())}",
                        fs?.let { if (it == "vfat") "FAT32" else it.uppercase() },
                    ).joinToString("  ·  "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fs == "vfat") {
                    Text("⚠ FAT32: files bigger than 4 GB (most 4K films) can't be saved here. exFAT or NTFS drives are fine.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    SmallButton("Rename", { renaming = d })
                    if (d.removable && usb.size > 1) {
                        SmallButton("▲", { Locations.moveDrive(d.id, -1); version++ })
                        SmallButton("▼", { Locations.moveDrive(d.id, 1); version++ })
                    }
                }
            }
        }
        if (usb.isEmpty()) Hint("No USB / SD drive plugged in right now.")
        OutlinedButton(onClick = { version++ }) { Text("Refresh drives") }
    }

    SettingsCard("File organisation") {
        Text("Series downloads", fontWeight = FontWeight.Medium)
        Pills(
            listOf("show_season" to "Show / Season 1 /", "show" to "Show /", "flat" to "All in one folder"),
            remember(version) { storage.seriesLayout },
        ) { vm.setLayout("series", it); version++ }
        Text("Films", fontWeight = FontWeight.Medium)
        Pills(listOf("flat" to "All in one folder", "folder" to "A folder per film"), remember(version) { storage.filmLayout }) {
            vm.setLayout("film", it); version++
        }
        Text("Recordings", fontWeight = FontWeight.Medium)
        Pills(listOf("channel" to "A folder per channel", "flat" to "All in one folder"), remember(version) { storage.recordingLayout }) {
            vm.setLayout("recording", it); version++
        }
    }

    browsing?.let { kind ->
        FolderBrowser(
            kind = kind,
            loc = locs.getValue(kind),
            drives = drives,
            access = access,
            onDismiss = { browsing = null },
        ) { loc ->
            browsing = null
            apply(kind, loc)
        }
    }

    renaming?.let { d ->
        var name by remember(d.id) { mutableStateOf(Locations.nameOf(d)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name for this drive") },
            text = {
                Column {
                    Text(d.systemName + "  ·  " + d.root.absolutePath, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { Locations.rename(d.id, name); renaming = null; version++ }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { Locations.rename(d.id, ""); renaming = null; version++ }) { Text("Use the drive's own name") } },
        )
    }

    offer?.let { o ->
        AlertDialog(
            onDismissRequest = { vm.dismissMove() },
            title = { Text("Move your downloads?") },
            text = { Text("${o.moves.size} downloaded files (${gb(o.bytes)}) are in the old place. Move them to the new one? The Downloads list keeps working either way.") },
            confirmButton = { TextButton(onClick = { vm.moveFiles(o) }) { Text("Move them") } },
            dismissButton = { TextButton(onClick = { vm.dismissMove() }) { Text("Leave them") } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

@Composable
private fun Pills(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> Chip(label, v == selected, { onSelect(v) }) }
    }
}

/** One kind of file: which drive, which folder, where it really goes now. */
@Composable
private fun LocationRow(
    kind: Kind,
    loc: Location,
    drives: List<Locations.Drive>,
    access: Boolean,
    onChange: (Location) -> Unit,
    onBrowse: () -> Unit,
    version: Int,
) {
    val resolved = remember(kind, loc, version) { runCatching { Locations.resolve(kind) }.getOrNull() }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(kind.label, fontWeight = FontWeight.Bold)
        val options = buildList {
            if (kind != Kind.MAIN) add("main" to "Same as main")
            if (kind == Kind.BUFFERS) add("cache" to "App folder (fastest)")
            add("internal" to "Internal storage")
            add("any_usb" to "Any USB / SD drive")
            drives.filter { it.removable }.forEach { add("drive:${it.id}" to Locations.nameOf(it)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                Chip(label, loc.drive == value, {
                    val folder = when {
                        value == loc.drive -> loc.folder
                        value == "internal" -> if (kind == Kind.MAIN) "Download/NovaIPTV" else "Download/NovaIPTV/${kind.defaultSub}"
                        value == "main" || value == "cache" -> ""
                        else -> if (kind == Kind.MAIN) "NovaIPTV" else "NovaIPTV/${kind.defaultSub}"
                    }
                    onChange(Location(value, folder, appFolder = if (value == "internal") false else loc.appFolder))
                })
            }
        }
        if (loc.drive != "main" && loc.drive != "cache") {
            Text("Folder: /" + loc.folder.ifBlank { "(top of the drive)" }, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("Choose folder…", onBrowse)
                if (loc.drive != "internal") {
                    Chip(
                        if (loc.appFolder || !access) "✓ Drive's app folder" else "Drive's app folder",
                        loc.appFolder || !access,
                        { if (access) onChange(loc.copy(appFolder = !loc.appFolder)) },
                    )
                }
            }
        }
        resolved?.let { r ->
            Text(
                "→ " + r.dir.absolutePath + (r.drive?.let { "  ·  ${gb(it.free())} free" } ?: ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            )
            r.note?.let { Text("⚠ $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Browse folders of a drive and pick one (or make a new one). */
@Composable
private fun FolderBrowser(
    kind: Kind,
    loc: Location,
    drives: List<Locations.Drive>,
    access: Boolean,
    onDismiss: () -> Unit,
    onPick: (Location) -> Unit,
) {
    val drive = when {
        loc.drive == "internal" -> drives.first { !it.removable }
        loc.drive.startsWith("drive:") -> drives.firstOrNull { it.id == loc.drive.removePrefix("drive:") }
        else -> Locations.usbInOrder().firstOrNull()
    }
    if (drive == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("No drive") },
            text = { Text("Plug in a USB / SD drive first, then press Refresh drives.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }
    val useApp = drive.removable && (loc.appFolder || !access)
    val base = if (useApp) drive.appDir else drive.root
    var rel by remember { mutableStateOf(loc.folder.trim('/')) }
    var newName by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val here = File(base, rel)
    val subs = remember(rel, refresh) {
        (here.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
            .sortedBy { it.name.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder for ${kind.label.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Locations.nameOf(drive) + (if (useApp) " (app folder)" else ""), fontWeight = FontWeight.Bold)
                Text("/" + rel, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (rel.isNotBlank()) {
                        item {
                            FocusBox(onClick = { rel = rel.substringBeforeLast('/', "") }, modifier = Modifier.fillMaxWidth()) {
                                Text("⬆  Up", modifier = Modifier.padding(10.dp))
                            }
                        }
                    }
                    itemsIndexed(subs) { _, f ->
                        FocusBox(onClick = { rel = listOf(rel, f.name).filter { it.isNotBlank() }.joinToString("/") }, modifier = Modifier.fillMaxWidth()) {
                            Text("📁  " + f.name, modifier = Modifier.padding(10.dp))
                        }
                    }
                    if (subs.isEmpty()) item { Text("(no folders here)", fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
                }
                val n = newName
                if (n == null) {
                    SmallButton("＋ New folder", { newName = "" })
                } else {
                    OutlinedTextField(n, { newName = it }, singleLine = true, label = { Text("New folder name") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("Create", {
                            val clean = com.nova.iptv.data.Folders.safeFileName(n)
                            if (File(here, clean).mkdirs() || File(here, clean).isDirectory) {
                                rel = listOf(rel, clean).filter { it.isNotBlank() }.joinToString("/")
                            }
                            newName = null
                            refresh++
                        })
                        SmallButton("Cancel", { newName = null })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(loc.copy(folder = rel)) }) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
