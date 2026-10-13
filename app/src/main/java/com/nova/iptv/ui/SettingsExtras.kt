package com.nova.iptv.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsCard(title: String, summary: String? = null, content: @Composable () -> Unit) {
    // Folded sections: one line each (▸ name · current choice); OK / click opens it, opening one closes the other.
    val key = title.substringBefore(" (")
    val open = SettingsFold.open == key
    // Opening a section closes the one above it, which can push this title off the top of the screen:
    // bring it back into view, so what you just opened is always in front of you.
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var clicked by remember { mutableStateOf(false) }
    LaunchedEffect(open, clicked) {
        if (open && clicked) {
            kotlinx.coroutines.delay(80)
            runCatching { bring.bringIntoView() }
            clicked = false
        }
    }
    Column(
        Modifier
            .bringIntoViewRequester(bring)
            .fillMaxWidth()
            .widthIn(max = 900.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FocusBox(
            onClick = {
                SettingsFold.open = if (open) null else key
                clicked = !open
                android.util.Log.i("NovaTest", "SETTINGS ${if (open) "closed" else "opened"} $key")
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (open) "▾" else "▸", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, modifier = Modifier.width(24.dp))
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                if (summary != null && !open) {
                    Text(
                        "   $summary", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (open) {
            Column(
                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp).pinGuard(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

/** Which Settings section is open (Accounts at first). */
object SettingsFold {
    var open by mutableStateOf<String?>("Accounts")
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    // Wraps onto a second line on a narrow screen (nothing is cut off).
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) -> Chip(label, value == selected, { onSelect(value) }) }
    }
}

// =============================================================================== DISPLAY & START-UP

@Composable
fun DisplaySettings(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()

    SettingsCard("Display") {
        Text("Clock on screen", fontWeight = FontWeight.Medium)
        ChoiceRow(
            listOf("off" to "Off", "tl" to "Top left", "tr" to "Top right", "bl" to "Bottom left", "br" to "Bottom right"),
            s.clockPosition, vm::setClockPosition,
        )
        Text("Group list", fontWeight = FontWeight.Medium)
        Hint("On TV the full group list is always on the left. On phones you can keep it on the left too, instead of behind the ☰ button.")
        ChoiceRow(
            listOf("left" to "Always on the left", "button" to "Behind ☰ button (phones)"),
            if (s.groupsAlwaysLeft) "left" else "button",
        ) { vm.setGroupsAlwaysLeft(it == "left") }
        Hint("Open a group as soon as you move onto it with the remote, so its channels appear on the right without pressing OK.")
        ChoiceRow(
            listOf("on" to "Open while moving", "off" to "Open with OK only"),
            if (s.openGroupOnFocus) "on" else "off",
        ) { vm.setOpenGroupOnFocus(it == "on") }
    }

    SettingsCard("Sorting") {
        Hint("How lists are sorted. You can also change it with the ⇅ button above each list. “My order” keeps the order you set with ✎ Edit.")
        Text("Live TV", fontWeight = FontWeight.Medium)
        ChoiceRow(Sorts.live, s.sorts["live"] ?: "custom") { vm.setSort("live", it) }
        Text("Films", fontWeight = FontWeight.Medium)
        ChoiceRow(Sorts.vod, s.sorts["movie"] ?: "custom") { vm.setSort("movie", it) }
        Text("Series", fontWeight = FontWeight.Medium)
        ChoiceRow(Sorts.vod, s.sorts["series"] ?: "custom") { vm.setSort("series", it) }
    }

    SettingsCard("Films & series view") {
        Hint("Number of poster columns. “Auto” fits as many as the poster size allows; the number of rows follows the screen size.")
        Text("Columns on phones", fontWeight = FontWeight.Medium)
        ChoiceRow(
            listOf("0" to "Auto", "2" to "2", "3" to "3", "4" to "4", "5" to "5", "6" to "6"),
            s.posterColumnsPhone.toString(),
        ) { vm.setPosterColumns(false, it.toInt()) }
        Text("Columns on TV & tablets", fontWeight = FontWeight.Medium)
        ChoiceRow(
            listOf("0" to "Auto", "4" to "4", "5" to "5", "6" to "6", "7" to "7", "8" to "8", "9" to "9", "10" to "10"),
            s.posterColumnsTv.toString(),
        ) { vm.setPosterColumns(true, it.toInt()) }
        Text("Poster size (with Auto columns)", fontWeight = FontWeight.Medium)
        ChoiceRow(listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), s.posterSize, vm::setPosterSize)
        Text("Titles under posters", fontWeight = FontWeight.Medium)
        ChoiceRow(listOf("on" to "Show", "off" to "Hide"), if (s.posterTitles) "on" else "off") { vm.setPosterTitles(it == "on") }
    }

    SettingsCard("Live TV view") {
        Text("Row height", fontWeight = FontWeight.Medium)
        Hint("Compact shows more channels on the screen; Large is easier to read from the sofa.")
        ChoiceRow(listOf("compact" to "Compact", "normal" to "Normal", "large" to "Large"), s.liveRowSize, vm::setLiveRowSize)
        Text("Channel logos", fontWeight = FontWeight.Medium)
        ChoiceRow(listOf("on" to "Show", "off" to "Hide"), if (s.liveLogos) "on" else "off") { vm.setLiveLogos(it == "on") }
        Text("Programme on air under each channel", fontWeight = FontWeight.Medium)
        Hint("Asks the provider's guide for every channel on screen, so lists can take a little longer to fill in.")
        ChoiceRow(listOf("on" to "Show", "off" to "Hide"), if (s.liveNowLine) "on" else "off") { vm.setLiveNowLine(it == "on") }
        Text("See-through level of the lists over the video", fontWeight = FontWeight.Medium)
        Hint("For the ◀ groups & channels list and the recently watched bar shown while a channel plays.")
        ChoiceRow(
            listOf("0" to "Solid", "15" to "15 %", "25" to "25 %", "40" to "40 %", "55" to "55 %", "70" to "70 %"),
            s.overlayTransparency.toString(),
        ) { vm.setOverlayTransparency(it.toInt()) }
        Text("Channel list columns (TV & tablets)", fontWeight = FontWeight.Medium)
        ChoiceRow(listOf("1" to "1 column", "2" to "2 columns"), s.liveColumns.toString()) { vm.setLiveColumns(it.toInt()) }
    }

    SettingsCard("Start-up") {
        Hint("Start the last channel or video you watched automatically when the app opens.")
        ChoiceRow(
            listOf("on" to "Play last watched", "off" to "Open the menu"),
            if (s.autoPlayLast) "on" else "off",
        ) { vm.setAutoPlayLast(it == "on") }
    }
}

// =============================================================================== PARENTAL CONTROL

@Composable
fun ParentalSettings(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val o by vm.org.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hidden = remember(o) { vm.hiddenEntries(o) }

    var askPinFor by remember { mutableStateOf<String?>(null) } // "list", "change", "remove"
    var setNewPin by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var pendingAge by remember { mutableStateOf<(() -> Unit)?>(null) }

    SettingsCard("Hidden items & parental control", summary = "${hidden.size} hidden  ·  " + if (s.hasPin) "PIN on" else "no PIN") {
        Hint(
            if (s.hasPin) "A PIN protects your hidden groups and channels. They can only be shown again from the list below, after entering the PIN."
            else "Set a PIN so hidden groups and channels can only be shown again with it. Hide things with ✎ Edit in Live TV, Films or Series."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.hasPin) {
                OutlinedButton(onClick = { if (PinGate.unlocked(true)) setNewPin = true else askPinFor = "change" }) { Text("Change PIN") }
                OutlinedButton(onClick = {
                    if (PinGate.unlocked(true)) {
                        vm.setPin(null)
                        PinGate.lock()
                        Toast.makeText(context, "PIN removed", Toast.LENGTH_SHORT).show()
                    } else askPinFor = "remove"
                }) { Text("Remove PIN") }
            } else {
                Button(onClick = { setNewPin = true }) { Text("Set PIN") }
            }
        }
        Button(onClick = { if (s.hasPin && !PinGate.unlocked(true)) askPinFor = "list" else showList = true }) {
            Text("Hidden items (${hidden.size})")
        }
        AgeFilterSettings(vm, guard = { action -> if (s.hasPin && !PinGate.unlocked(true)) { pendingAge = action; askPinFor = "age" } else action() })
    }

    askPinFor?.let { purpose ->
        PinDialog(
            title = "Enter your PIN",
            onDismiss = { askPinFor = null },
        ) { pin ->
            if (vm.checkPin(pin)) {
                askPinFor = null
                PinGate.unlockNow()
                when (purpose) {
                    "list" -> showList = true
                    "age" -> { pendingAge?.invoke(); pendingAge = null }
                    "change" -> setNewPin = true
                    "remove" -> {
                        vm.setPin(null)
                        Toast.makeText(context, "PIN removed", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            } else {
                false
            }
        }
    }

    if (setNewPin) {
        NewPinDialog(onDismiss = { setNewPin = false }) { pin ->
            vm.setPin(pin)
            PinGate.unlockNow() // you just chose it: stay unlocked while you are in Settings
            setNewPin = false
            Toast.makeText(context, "PIN saved", Toast.LENGTH_SHORT).show()
        }
    }

    if (showList) {
        HiddenItemsDialog(vm, hidden) { showList = false }
    }
}

/** Asks for a PIN; [onPin] returns false when it is wrong. */
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TvTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8); wrong = false },
                    singleLine = true,
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (wrong) Text("Wrong PIN", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { wrong = !onPin(pin) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewPinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8); error = null },
                    singleLine = true, label = { Text("PIN (4 to 8 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                TvTextField(
                    value = again, onValueChange = { again = it.filter(Char::isDigit).take(8); error = null },
                    singleLine = true, label = { Text("Repeat PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    pin.length < 4 -> "Use at least 4 digits"
                    pin != again -> "The two PINs are different"
                    else -> null
                }
                if (error == null) onSave(pin)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HiddenItemsDialog(vm: MainViewModel, entries: List<HiddenEntry>, onDismiss: () -> Unit) {
    val zones = listOf("Live TV", "Films", "Series")
    var zone by remember { mutableStateOf(zones.firstOrNull { z -> entries.any { it.zoneLabel == z } } ?: "Live TV") }
    val shown = entries.filter { it.zoneLabel == zone }
    var confirm by remember { mutableStateOf<HiddenEntry?>(null) }
    val context = LocalContext.current
    confirm?.let { e ->
        val noFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Show “${e.name.substringBefore("  ")}” again?") },
            text = {
                Text(
                    if (e.isGroup) "This group will appear again in ${e.zoneLabel}."
                    else "This will appear again in ${e.zoneLabel}" + (Regex("""\(([^)]*)\)$""").find(e.name)?.let { m -> ", in the group “${m.groupValues[1]}”." } ?: ".")
                )
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { noFocus.requestFocus() } }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }, modifier = Modifier.focusRequester(noFocus)) { Text("No, keep it hidden") }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.unhide(e)
                    android.util.Log.i("NovaTest", "UNHIDDEN ${e.name}")
                    Toast.makeText(context, "“${e.name.substringBefore("  ")}” is shown again", Toast.LENGTH_SHORT).show()
                    confirm = null
                }) { Text("Yes, show it") }
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden items (${entries.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Hidden groups and channels appear only here. “Show again” puts one back in its list (after a Yes / No question).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    zones.forEach { z -> Chip("$z (${entries.count { it.zoneLabel == z }})", z == zone, { zone = z }) }
                }
                if (shown.isEmpty()) {
                    Text("Nothing hidden in $zone.", modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        itemsIndexed(shown, key = { _, e -> e.orgKey }) { _, e ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, maxLines = 2)
                                    Text(
                                        if (e.isGroup) "Group" else "Channel / title", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                SmallButton("Show again", { confirm = e })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// =============================================================================== BACKUP

@Composable
fun BackupSettings(vm: MainViewModel) {
    val context = LocalContext.current
    var savePassword by remember { mutableStateOf<String?>(null) }
    var showSave by remember { mutableStateOf(false) }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(vm.backupText(savePassword).toByteArray()) }
            }.isSuccess
            Toast.makeText(context, if (ok) "Backup saved" else "Could not save the backup", Toast.LENGTH_LONG).show()
        }
    }

    SettingsCard("Backup & restore") {
        Hint(
            "A .bak file keeps all servers (with passwords), favorites, hidden items, order and settings. " +
                "Keep it private, or protect it with a password. Restoring replaces everything currently in the app."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showSave = true }) { Text("Save backup") }
            RestoreBackupButton(vm, "Restore a backup")
        }
        ListsBackup(vm)
    }
    if (showSave) SaveBackupDialog(
        vm,
        onSaveTo = { pw -> savePassword = pw; saveTo.launch(vm.backupFileName()) },
        onDismiss = { showSave = false },
    )
}

/** A button that opens "Restore my backup" (from the box / USB / SD or any web link). */
@Composable
fun RestoreBackupButton(vm: MainViewModel, label: String, modifier: Modifier = Modifier, filled: Boolean = false) {
    var show by remember { mutableStateOf(false) }
    if (filled) androidx.compose.material3.Button(onClick = { show = true }, modifier = modifier) { Text(label, fontWeight = FontWeight.Bold) }
    else OutlinedButton(onClick = { show = true }, modifier = modifier) { Text(label) }
    if (show) RestoreDialog(vm) { show = false }
}

@Suppress("unused")
private fun keep(f: File) = f

// =============================================================================== PLAYERS

@Composable
fun PlayerSettings(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val apps = remember { com.nova.iptv.player.ExternalPlayer.installed(context) }
    val choices = listOf("builtin" to "Built-in player", "ask" to "Ask each time") + apps.map { it.packageName to it.label }

    SettingsCard("Player selection") {
        Hint(
            "Choose the player for each kind of content. External players (VLC, MX Player, Just Player, Kodi…) " +
                "appear here once installed on the box."
        )
        listOf(
            "live" to "Live TV",
            "movie" to "Films",
            "series" to "Series",
            "catchup" to "Catch-up",
            "recordings" to "Recordings & downloads",
        ).forEach { (kind, label) ->
            Text(label, fontWeight = FontWeight.Medium)
            val current = s.players[kind] ?: "builtin"
            ChoiceRow(
                if (choices.any { it.first == current }) choices else choices + (current to current),
                current,
            ) { vm.setPlayerFor(kind, it) }
        }
        Text("When the built-in player can't play a format", fontWeight = FontWeight.Medium)
        ChoiceRow(
            listOf("on" to "Open the external player", "off" to "Show a message"),
            if (s.externalOnError) "on" else "off",
        ) { vm.setExternalOnError(it == "on") }
        if (apps.isEmpty()) {
            Hint("No external player is installed yet. Install VLC, MX Player or Just Player from the store, then come back.")
        }
    }

    SettingsCard("Built-in player") {
        Text("Video decoder", fontWeight = FontWeight.Medium)
        Hint("Hardware uses the box's chip (needed for 4K, HEVC and HDR). Software can help with a few unusual SD/HD streams but is slow for 4K.")
        ChoiceRow(listOf("hardware" to "Hardware decoder", "software" to "Software decoder"), s.videoDecoder, vm::setVideoDecoder)

        Text("Audio decoding", fontWeight = FontWeight.Medium)
        Hint("Box first keeps Dolby / DTS going to a soundbar or receiver. App decoders first plays more audio formats (DTS, AC3, E-AC3, TrueHD) on any TV.")
        ChoiceRow(listOf("device" to "Box first (passthrough)", "ffmpeg" to "App decoders first"), s.audioDecoder, vm::setAudioDecoder)

        Text("Buffer size limit", fontWeight = FontWeight.Medium)
        Hint("Memory used to hold video ahead. More helps unstable connections; less is safer on boxes with little memory.")
        ChoiceRow(
            listOf(32, 64, 128, 256).map { it.toString() to "$it MB" },
            s.bufferMb.toString(),
        ) { vm.setBufferMb(it.toInt()) }

        Text("Hardware-accelerated audio", fontWeight = FontWeight.Medium)
        Hint("Sends audio to the box's audio processor (audio offload). Saves power; switch off if sound stutters or is missing.")
        ChoiceRow(listOf("off" to "Off", "on" to "On"), if (s.audioOffload) "on" else "off") { vm.setAudioOffload(it == "on") }

        Text("Video output", fontWeight = FontWeight.Medium)
        Hint("Standard is best for 4K, HDR and tunneled playback. OpenGL texture can fix black or green video on some TVs.")
        ChoiceRow(listOf("surface" to "Standard surface", "texture" to "OpenGL texture"), s.videoSurface, vm::setVideoSurface)
    }
}

// =============================================================================== VPN

@Composable
fun VpnSettings(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var active by remember { mutableStateOf(com.nova.iptv.player.Vpn.isActive(context)) }
    val apps = remember { com.nova.iptv.player.Vpn.installedApps(context) }
    val pickProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !com.nova.iptv.player.Vpn.importProfile(context, uri)) {
            Toast.makeText(context, "Install OpenVPN for Android or WireGuard first, then import the profile.", Toast.LENGTH_LONG).show()
        }
    }

    SettingsCard("VPN") {
        Text(
            if (active) "● VPN connected" else "○ No VPN connected",
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Hint(
            "Nova uses the VPN apps on your box (OpenVPN for Android, OpenVPN Connect, WireGuard). " +
                "Import your provider's profile (.ovpn or .conf) into one of them, connect, and everything — including Nova — goes through the VPN." +
                if (apps.isEmpty()) " No VPN app is installed yet." else " Installed: " + apps.joinToString { it.second } + "."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickProfile.launch(arrayOf("*/*")) }) { Text("Import VPN profile") }
            OutlinedButton(onClick = { com.nova.iptv.player.Vpn.openApp(context) }) { Text("Open VPN app") }
            OutlinedButton(onClick = { active = com.nova.iptv.player.Vpn.isActive(context) }) { Text("Check") }
        }
        Text("Only play when a VPN is connected", fontWeight = FontWeight.Medium)
        ChoiceRow(listOf("off" to "Off", "on" to "On"), if (s.requireVpn) "on" else "off") { vm.setRequireVpn(it == "on") }
    }
}

// =============================================================================== STREAM WATCHDOG

@Composable
fun WatchdogSettings(vm: MainViewModel) {
    val storage = vm.appStorage
    var version by remember { mutableStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    val on = remember(version) { storage.watchdog }
    SettingsCard(
        "Stream watchdog",
        summary = remember(version) { if (!storage.watchdog) "Off" else if (storage.watchdogMode == "live") "Live button only" else "Full repair" },
    ) {
        Hint(
            "Checks every second that a live channel is still moving: data arriving, picture changing, clock running. " +
                "If it stops without an error (a common provider / VPN problem), Nova reconnects by itself — usually before you notice. " +
                "A weak signal is not a dead one: Nova then pauses and refills instead of reconnecting. " +
                "If a channel keeps failing: safer player settings, then the same channel on your other account."
        )
        val mode = remember(version) { if (!storage.watchdog) "off" else storage.watchdogMode }
        ChoiceRow(
            listOf("live" to "Live button only", "full" to "Full repair", "off" to "Off"),
            mode,
        ) {
            if (it == "off") storage.watchdog = false else { storage.watchdog = true; storage.watchdogMode = it }
            version++
        }
        Hint(
            when (mode) {
                "live" -> "When the picture or clock gets stuck for 10 s, Nova does what the Live ⏭ button does: jumps to live inside the " +
                    "disk buffer and restarts the picture there — nothing else, at most once every 20 s. Only if no video arrives at all " +
                    "for 30 s does it renew the connection (the stored video is kept)."
                "full" -> "Every repair: jump / restart, pause & refill on a weak signal, safer player settings, then your other account."
                else -> "No automatic repairs. The Live ⏭ button (or the remote button you give it) still fixes a stuck picture."
            }
        )
        if (on && mode == "full") {
            Text("Sensitivity", fontWeight = FontWeight.Medium)
            ChoiceRow(
                listOf("5" to "Fast (5 s)", "8" to "Normal (8 s)", "15" to "Relaxed (15 s, slow internet)"),
                remember(version) { storage.watchdogSecs.toString() },
            ) { storage.watchdogSecs = it.toInt(); version++ }
            Text("On a weak signal (video still arriving, but too slowly)", fontWeight = FontWeight.Medium)
            ChoiceRow(
                listOf("15" to "Pause & refill 15 s", "30" to "Pause & refill 30 s", "60" to "Pause & refill 60 s", "0" to "Off"),
                remember(version) { storage.weakRefillSecs.toString() },
            ) { storage.weakRefillSecs = it.toInt(); version++ }
            Hint(
                "Instead of reconnecting (which throws away what is stored), Nova pauses the picture, keeps the video " +
                    "on disk and plays on once this much is stored. The channel then keeps a bigger cushion until you change channel."
            )
            Text("If a channel keeps failing", fontWeight = FontWeight.Medium)
            ChoiceRow(
                listOf("on" to "Try it on my other account", "off" to "Stay on this account"),
                if (remember(version) { storage.watchdogOtherAccount }) "on" else "off",
            ) { storage.watchdogOtherAccount = it == "on"; version++ }
            Text("Note on screen when it acts", fontWeight = FontWeight.Medium)
            ChoiceRow(listOf("on" to "Show", "off" to "Hide"), if (remember(version) { storage.watchdogNotes }) "on" else "off") {
                storage.watchdogNotes = it == "on"; version++
            }
        }
        OutlinedButton(onClick = { showLog = true }) { Text("Stream health log (${storage.healthLog.size})") }
    }
    if (showLog) {
        val events = remember(version) { storage.healthLog }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Stream health log") },
            text = {
                if (events.isEmpty()) Text("Nothing happened yet — all streams ran smoothly.")
                else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(events) { _, e ->
                        val p = e.split('|')
                        Column(Modifier.padding(vertical = 5.dp)) {
                            Text("${p.getOrNull(0).orEmpty()}  ·  ${p.getOrNull(1).orEmpty()}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text("${p.getOrNull(2).orEmpty()} → ${p.getOrNull(3).orEmpty()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } },
            dismissButton = {
                if (events.isNotEmpty()) TextButton(onClick = { storage.healthLog = emptyList(); version++ }) { Text("Clear") }
            },
        )
    }
}


/**
 * Parental PIN for changes: Edit / Reorder and every change in Settings ask for it (Settings can still be viewed).
 * One correct PIN unlocks for 5 minutes of use, until you leave Settings or finish editing.
 */
object PinGate {
    private const val UNLOCK_MS = 5 * 60_000L
    private var unlockedUntil by mutableStateOf(0L)
    var asking by mutableStateOf(false)
    private var pending: (() -> Unit)? = null

    private fun now() = android.os.SystemClock.elapsedRealtime()
    fun unlocked(hasPin: Boolean) = !hasPin || now() < unlockedUntil
    val isOpen: Boolean get() = unlockedUntil > 0 && now() < unlockedUntil
    fun unlockNow() { unlockedUntil = now() + UNLOCK_MS }
    fun lock() { unlockedUntil = 0L }
    fun touch() { if (isOpen) unlockedUntil = now() + UNLOCK_MS }

    /** Runs [action] now, or after the PIN is entered. */
    fun require(hasPin: Boolean, action: () -> Unit) {
        if (unlocked(hasPin)) { touch(); action(); return }
        android.util.Log.i("NovaTest", "PIN asked")
        pending = action
        asking = true
    }

    fun ask() = require(true) {}

    fun entered() {
        unlockNow()
        asking = false
        val p = pending
        pending = null
        p?.invoke()
    }

    fun cancel() { asking = false; pending = null }
}

/** Shows the PIN pop-up when something asked for it (put once on each screen root). */
@Composable
fun PinGateHost(vm: MainViewModel) {
    if (!PinGate.asking) return
    PinDialog(title = "Parental PIN", onDismiss = { PinGate.cancel() }) { pin ->
        if (vm.appStorage.hasPin && vm.checkPin(pin)) {
            android.util.Log.i("NovaTest", "PIN accepted")
            PinGate.entered()
            true
        } else false
    }
}

/** With a PIN set: any tap / OK inside asks for the PIN first (scrolling and reading still work). */
@Composable
private fun Modifier.pinGuard(): Modifier {
    val hasPin = LocalPinSet.current
    if (!hasPin) return this
    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (PinGate.unlocked(true)) { PinGate.touch(); return@awaitEachGesture }
                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return@awaitEachGesture
                if ((up.position - down.position).getDistance() < 24f) {
                    up.consume()
                    PinGate.ask()
                }
            }
        }
        .onPreviewKeyEvent { e ->
            val code = e.nativeKeyEvent.keyCode
            val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
                code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!ok) return@onPreviewKeyEvent false
            if (PinGate.unlocked(true)) { PinGate.touch(); return@onPreviewKeyEvent false }
            if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) PinGate.ask()
            true
        }
}

/** Whether a parental PIN is set (provided by the Settings screen). */
val LocalPinSet = androidx.compose.runtime.staticCompositionLocalOf { false }


// =============================================================================== AGE FILTER (USA ratings)

private class AgeStats(val summary: String, val groupCounts: Map<String, Int>)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AgeFilterSettings(vm: MainViewModel, guard: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val storage = (context.applicationContext as com.nova.iptv.NovaApp).storage
    val AF = com.nova.iptv.data.AgeFilter
    val ageV by AF.version.collectAsStateWithLifecycle()
    val checking by AF.busy.collectAsStateWithLifecycle()
    val status by vm.ageStatus.collectAsStateWithLifecycle()
    val diagnosis by AF.diagnosis.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val level = remember(ageV) { storage.ageLevel }
    val source = remember(ageV) { storage.ageSource }
    val hideUnknown = remember(ageV) { storage.ageHideUnknown }
    val liveMode = remember(ageV) { storage.ageLiveMode }
    var key by remember { mutableStateOf(storage.tmdbKey) }
    var showHidden by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf<com.nova.iptv.data.AgeFilter.WordGroup?>(null) }
    // Counts are worked out in the background (tens of thousands of titles).
    val stats by androidx.compose.runtime.produceState(AgeStats("", emptyMap()), ageV, checking, accounts) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            AgeStats(
                AF.summary(accounts),
                AF.WORD_GROUPS.associate { g -> g.id to AF.countWords(AF.wordsOf(storage, g), accounts).first },
            )
        }
    }
    val green = androidx.compose.ui.graphics.Color(0xFF2EE66B)
    val nf = remember { java.text.NumberFormat.getIntegerInstance() }

    Text("🛡 Age filter — USA ratings (MPA)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    Hint("The highest rating allowed: everything above it is hidden from lists, Search, Recent and Favourites. " +
        "G = all ages · PG = parental guidance · PG-13 = not for under 13 · R = under 17 with a parent · NC-17 = adults (only 18+ / X hidden).")
    ChoiceRow(AF.LEVELS.mapIndexed { i, n -> i.toString() to n }, level.toString()) { v ->
        guard { vm.setAge { it.ageLevel = v.toInt() } }
    }
    Hint("Series use the US TV ratings: G = TV-Y · TV-Y7 · TV-G  |  PG = TV-PG  |  PG-13 = TV-14  |  R / NC-17 = TV-MA")
    if (level == 0) return

    Text("Where the rating comes from", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    ChoiceRow(listOf("tmdb" to "Film database (TMDB) · US rating", "rules" to "Genre & name rules"), source) { v ->
        guard { vm.setAge { it.ageSource = v } }
    }
    if (source == "tmdb") {
        val setKey = { v: String -> key = v.trim(); storage.tmdbKey = key; AF.configure(storage) }
        TvTextField(
            value = key, onValueChange = { setKey(it) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("TMDB key") },
            placeholder = { Text(if (AF.defaultKey.isNotBlank()) "Default key (built in)" else "No default key — paste yours") },
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PasteButton { setKey(it) }
                    if (key.isNotEmpty() && AF.defaultKey.isNotBlank()) TextButton(onClick = { setKey("") }) { Text("↺ Default") }
                }
            },
        )
        Hint("Empty = Nova's default key. You can use your own free key: themoviedb.org → Settings → API. " +
            "French titles and names like “FR_Le Roi Lion_4K (2019)” are cleaned and matched with the year.")
    }

    Text("Titles with no age found", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    ChoiceRow(listOf("show" to "Show them", "hide" to "Hide them (stricter)"), if (hideUnknown) "hide" else "show") { v ->
        guard { vm.setAge { it.ageHideUnknown = v == "hide" } }
    }

    Text("Live channels", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    ChoiceRow(listOf("name" to "Hide by name (Adult, XXX, Horror…)", "allow" to "Only groups I allow"), liveMode) { v ->
        guard { vm.setAge { it.ageLiveMode = v } }
    }
    if (liveMode == "allow") {
        val groups = remember(ageV) { vm.liveGroupsForAge() }
        val allowed = remember(ageV) { storage.ageAllowedGroups }
        val multi = accounts.size > 1
        Hint("Tick the live groups the children may watch (${allowed.size} allowed). All other groups are hidden.")
        if (groups.isEmpty()) Hint("Open Live TV once so the groups are loaded.")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(groups) { _, g ->
                val on = g.key in allowed
                FocusBox(onClick = {
                    guard { vm.setAge { st -> st.ageAllowedGroups = if (on) st.ageAllowedGroups - g.key else st.ageAllowedGroups + g.key } }
                }, selected = on, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        (if (on) "☑  " else "☐  ") + g.name + (if (multi) "  ·  " + vm.accountName(g.accountId) else ""),
                        color = if (on) green else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }

    // ---- words in the name ----
    Text("🔤 Hide films & series with these words in the name", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    val hasPin = vm.settings.collectAsStateWithLifecycle().value.hasPin
    var wordsOpen by remember { mutableStateOf(false) }
    if (hasPin && !wordsOpen) {
        // Locked: the words are not shown to the children.
        val onGroups = remember(ageV) { AF.WORD_GROUPS.filter { AF.groupOn(storage, it) } }
        val count = remember(ageV) { onGroups.sumOf { AF.wordsOf(storage, it).size } }
        Text(
            "🔒 Word list locked — ${onGroups.size} groups on · $count words" +
                (stats.groupCounts.filterKeys { k -> onGroups.any { it.id == k } }.values.sum().takeIf { it > 0 }?.let { " · ${nf.format(it)} titles hidden" } ?: ""),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { guard { wordsOpen = true } }) { Text("🔓 Show / edit the words (PIN)") }
    } else {
    if (hasPin) TextButton(onClick = { wordsOpen = false }) { Text("🔒 Hide the words again") }
    Hint("Upper/lower case and accents ignored · also found in “FR_Love_Story_4K” · plurals too (kiss → kisses) · ✕ removes a word.")
    AF.WORD_GROUPS.forEach { g ->
        val on = remember(ageV) { AF.groupOn(storage, g) }
        val words = remember(ageV) { AF.wordsOf(storage, g) }
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        if (!on) "off" else stats.groupCounts[g.id]?.let { "${nf.format(it)} titles" } ?: "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Chip(if (on) "● On" else "○ Off", on, {
                    guard {
                        vm.setAge { st ->
                            val cur = AF.WORD_GROUPS.filter { AF.groupOn(st, it) }.map { it.id }.toSet()
                            st.ageWordGroupsOn = if (on) cur - g.id else cur + g.id
                        }
                    }
                })
            }
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                words.forEach { w ->
                    FocusBox(onClick = { guard { vm.setAge { st -> st.setAgeWords(g.id, AF.wordsOf(st, g) - w) } } }, shape = RoundedCornerShape(12.dp)) {
                        Text("$w ✕", fontSize = 12.sp, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                FocusBox(onClick = { guard { addTo = g } }, shape = RoundedCornerShape(12.dp)) {
                    Text(if (words.isEmpty()) "＋ add a word" else "＋ add", fontSize = 12.sp, color = green, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                if (AF.wordsOf(storage, g) != g.defaults && g.defaults.isNotEmpty()) {
                    FocusBox(onClick = { guard { vm.setAge { st -> st.setAgeWords(g.id, null) } } }, shape = RoundedCornerShape(12.dp)) {
                        Text("↺ Nova's list", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }

    }

    // ---- status ----
    Text(
        when {
            checking && status.isNotBlank() -> status
            status.startsWith("Age check stopped") || status.startsWith("Add your") -> status
            stats.summary.isBlank() -> "Counting…"
            else -> stats.summary
        },
        fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFBFF5D3),
        modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF0F2A1C), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
    )
    if (source == "tmdb" && diagnosis.isNotBlank()) {
        Text(
            diagnosis, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFFFE2A8),
            modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF2A2112), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { guard { showHidden = true } }) { Text("👁 Show hidden by age") }
        if (source == "tmdb") {
            OutlinedButton(onClick = { vm.checkAges() }, enabled = !checking) { Text(if (checking) "Checking…" else "⟳ Check now") }
            TextButton(onClick = { guard { AF.forget(context); vm.checkAges() } }, enabled = !checking) { Text("Check all again") }
        }
    }
    Text("🔒 Changes ask for your PIN" + if (!vm.settings.value.hasPin) " (set a PIN above first)" else "",
        fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFFFD27A))

    if (showHidden) AgeHiddenDialog(vm) { showHidden = false }
    addTo?.let { g -> AddWordDialog(g, accounts, onDismiss = { addTo = null }) { w ->
        vm.setAge { st -> st.setAgeWords(g.id, (AF.wordsOf(st, g) + w).distinct()) }
        addTo = null
    } }
}

@Composable
private fun AddWordDialog(
    g: com.nova.iptv.data.AgeFilter.WordGroup,
    accounts: List<com.nova.iptv.data.Account>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var word by remember { mutableStateOf("") }
    val preview by androidx.compose.runtime.produceState<Pair<Int, List<String>>?>(null, word) {
        val w = word.trim()
        if (w.length < 2) { value = null; return@produceState }
        kotlinx.coroutines.delay(400)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.nova.iptv.data.AgeFilter.countWord(w, accounts) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a word") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Group: " + g.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TvTextField(
                    value = word, onValueChange = { word = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Word") }, trailingIcon = { PasteButton { word = it.trim() } },
                )
                preview?.let { (n, ex) ->
                    Text(
                        "Will hide ${java.text.NumberFormat.getIntegerInstance().format(n)} titles" +
                            (if (ex.isNotEmpty()) ", e.g. " + ex.joinToString(", ") { "“$it”" } else "") + ".",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (word.trim().length >= 2) onAdd(word.trim()) }, enabled = word.trim().length >= 2) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "👁 Hidden by age": everything the filter hides, ☑ hidden / ☐ allowed anyway (an exception that stays). */
@Composable
private fun AgeHiddenDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val AF = com.nova.iptv.data.AgeFilter
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val ageV by AF.version.collectAsStateWithLifecycle()
    val names = remember { vm.ageGroupNames() }
    val lists by androidx.compose.runtime.produceState<Map<String, List<com.nova.iptv.data.AgeFilter.HiddenRow>>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { AF.hiddenList(accounts, names) }
    }
    var tab by remember { mutableStateOf("movie") }
    var query by remember { mutableStateOf("") }
    var onlyAllowed by remember { mutableStateOf(false) }
    var infoFor by remember { mutableStateOf<com.nova.iptv.data.AgeFilter.HiddenRow?>(null) }
    infoFor?.let { r -> AgeInfoDialog(vm, r) { infoFor = null } }
    val exceptions = remember(ageV) { AF.exceptions }
    val nf = remember { java.text.NumberFormat.getIntegerInstance() }
    val green = androidx.compose.ui.graphics.Color(0xFF2EE66B)
    val rows = remember(lists, tab, query, onlyAllowed, exceptions) {
        val q = AF.normalize(query.trim())
        lists?.get(tab).orEmpty().filter { r ->
            (q.isEmpty() || AF.normalize(r.name).contains(q)) && (!onlyAllowed || r.exKey in exceptions)
        }
    }
    val multi = accounts.size > 1
    val state = androidx.compose.foundation.lazy.rememberLazyListState()

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color(0xFF1B2029),
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 900.dp).fillMaxHeight(0.92f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("👁 Hidden by age · up to " + AF.LEVELS.getOrElse(AF.level) { "" }, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("☑ hidden · ☐ allowed anyway (an exception that stays). OK / tap to switch · ▶ ℹ Info = the film page.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceRow(
                    listOf("movie" to "🎬 Films", "series" to "🎞 Series", "live" to "📺 Channels").map { (k, l) ->
                        k to (l + (lists?.get(k)?.let { " " + nf.format(it.size) } ?: ""))
                    }, tab,
                ) { tab = it }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("🔍 Search a title…") })
                    Chip("All", !onlyAllowed, { onlyAllowed = false })
                    Chip("Allowed only (${exceptions.count { it.startsWith("$tab|") }})", onlyAllowed, { onlyAllowed = true })
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        lists == null -> Text("Looking through all titles…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                        rows.isEmpty() -> Text(if (onlyAllowed) "No exceptions here." else "Nothing hidden here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                        else -> LazyColumn(
                            Modifier.fillMaxSize().scrollBar(state), state = state,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 14.dp),
                        ) {
                            items(rows.size, key = { rows[it].exKey }) { i ->
                                val r = rows[i]
                                val allowed = r.exKey in exceptions
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FocusBox(
                                    onClick = { vm.toggleAgeException(r.exKey) },
                                    modifier = Modifier.weight(1f).background(
                                        if (allowed) androidx.compose.ui.graphics.Color(0xFF1D3326) else androidx.compose.ui.graphics.Color(0xFF232A35), RoundedCornerShape(10.dp),
                                    ),
                                ) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (allowed) "☐" else "☑", fontSize = 18.sp, color = if (allowed) MaterialTheme.colorScheme.onSurfaceVariant else green, modifier = Modifier.width(28.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(r.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            Text(
                                                (if (multi) vm.accountName(r.accountId) else "") + (if (allowed) (if (multi) " · " else "") + "allowed (exception)" else ""),
                                                fontSize = 11.5.sp, color = if (allowed) green else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                            )
                                        }
                                        val bad = r.reason.startsWith("R") || r.reason.startsWith("NC") || r.reason.startsWith("18") || r.reason.contains("adult")
                                        Text(
                                            r.reason, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                                            color = if (bad) androidx.compose.ui.graphics.Color(0xFFFFB3AD) else androidx.compose.ui.graphics.Color(0xFFFFD27A),
                                            modifier = Modifier.background(androidx.compose.ui.graphics.Color(0x33FFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (r.code != "live") FocusBox(onClick = { infoFor = r }, shape = RoundedCornerShape(12.dp)) {
                                    Text("ℹ Info", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF9FC3FF),
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                                }
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${exceptions.size} exceptions · Back = close", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Button(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}


/** Settings › 7 System: why Nova closed by itself last time (to send to the developer). */
@Composable
fun LastProblemSettings() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(com.nova.iptv.data.CrashLog.last(context)) }
    LaunchedEffect(Unit) { com.nova.iptv.data.CrashLog.seen(context) }
    SettingsCard("Last problem", summary = if (text == null) "none" else text!!.lineSequence().drop(1).firstOrNull()?.take(60)) {
        if (text == null) {
            Hint("Nova has not closed by itself since this was cleared.")
        } else {
            Hint("Why Nova closed by itself last time. Share it so the problem can be fixed (also saved in Download/NovaIPTV/Logs).")
            Text(text!!.take(1500), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, text)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share the problem").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Share") }
                OutlinedButton(onClick = { com.nova.iptv.data.CrashLog.clear(context); text = null }) { Text("Clear") }
            }
        }
    }
}


// =============================================================================== LISTS BACKUP (_list.bakL)

@Composable
private fun ListsBackup(vm: MainViewModel) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    var chooser by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Pair<String, String>?>(null) } // (file name, text)
    var askPin by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (text == null) Toast.makeText(context, "Could not read this file", Toast.LENGTH_LONG).show()
            else ask = (uri.lastPathSegment?.substringAfterLast('/') ?: "file") to text
        }
    }
    Text("📋 My lists only (_list.bakL)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    Hint("No servers, no passwords, no settings — only how you arranged your lists: group & item order (channels, films, series), " +
        "favorites, the groups and items you hid yourself, and open / closed server sections. Restoring replaces only your lists.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            scope.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { vm.saveLists() }.getOrNull() }
                Toast.makeText(context, when {
                    r == null -> "Could not save the lists"
                    r.first == null -> r.second
                    else -> "Lists saved (${r.second}): Download/NovaIPTV/Backups/${r.first!!.name}"
                }, Toast.LENGTH_LONG).show()
            }
        }) { Text("📋 Save my lists") }
        // Restoring can bring hidden items back: it asks for the PIN.
        OutlinedButton(onClick = { if (s.hasPin && !PinGate.unlocked(true)) askPin = true else chooser = true }) { Text("Restore my lists") }
    }
    if (askPin) PinDialog("Enter your PIN", onDismiss = { askPin = false }) { pin ->
        if (vm.checkPin(pin)) { PinGate.unlockNow(); askPin = false; chooser = true; true } else false
    }
    if (chooser) {
        val files = remember { vm.listsFiles() }
        AlertDialog(
            onDismissRequest = { chooser = false },
            title = { Text("Restore my lists") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (files.isEmpty()) Hint("No _list.bakL file in Download/NovaIPTV/Backups.")
                    LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(files) { _, f ->
                            val what = remember(f) { vm.listsSummary(f) }
                            FocusBox(onClick = { chooser = false; ask = f.name to f.readText() }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    Text("📋 " + f.name, fontSize = 14.sp)
                                    Text(what.ifBlank { "(empty — nothing to restore)" }, fontSize = 12.sp,
                                        color = if (what.isBlank()) androidx.compose.ui.graphics.Color(0xFFFF8A80) else MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    AllFilesNotice()
                }
            },
            confirmButton = { TextButton(onClick = { chooser = false; pick.launch(arrayOf("*/*")) }) { Text("Other file…") } },
            dismissButton = { TextButton(onClick = { chooser = false }) { Text("Cancel") } },
        )
    }
    ask?.let { (name, text) ->
        ConfirmOverlayDialog(
            title = "Restore these lists?",
            text = "$name\nYour current order, favorites and hidden items are replaced. Servers and settings stay.",
            yes = "Yes, restore",
            onYes = {
                runCatching { vm.restoreLists(text) }
                    .onSuccess { Toast.makeText(context, "Lists restored", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, it.message ?: "Could not restore", Toast.LENGTH_LONG).show() }
                ask = null
            },
            onDismiss = { ask = null },
        )
    }
}

@Composable
private fun ConfirmOverlayDialog(title: String, text: String, yes: String, onYes: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text(yes) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =============================================================================== FILM PAGE (Hidden by age › ℹ Info)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AgeInfoDialog(vm: MainViewModel, row: com.nova.iptv.data.AgeFilter.HiddenRow, onDismiss: () -> Unit) {
    val AF = com.nova.iptv.data.AgeFilter
    val ageV by AF.version.collectAsStateWithLifecycle()
    val allowed = remember(ageV) { row.exKey in AF.exceptions }
    val info by androidx.compose.runtime.produceState<Pair<com.nova.iptv.data.MediaDetails, String?>?>(null, row.key) {
        value = runCatching { vm.ageInfo(row.code, row.key) }.getOrNull() ?: (com.nova.iptv.data.MediaDetails() to null)
    }
    val green = androidx.compose.ui.graphics.Color(0xFF2EE66B)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp), color = androidx.compose.ui.graphics.Color(0xFF20262F),
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 760.dp),
        ) {
            BoxWithConstraintsInfo { wide ->
                val d = info?.first
                val poster: @Composable (Modifier) -> Unit = { m ->
                    Logo(info?.second, row.name, m.background(androidx.compose.ui.graphics.Color(0xFF2B3D5C), RoundedCornerShape(10.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                val text: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val year = d?.releaseDate?.take(4)?.takeIf { it.all(Char::isDigit) }
                        Text(row.name + (year?.let { " ($it)" } ?: ""), fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(
                            listOfNotNull(d?.genre, d?.duration, d?.rating?.let { "★ %.1f".format(it) }, vm.accountName(row.accountId)).joinToString(" · "),
                            fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when {
                                info == null -> "Loading the page from your server…"
                                d?.plot.isNullOrBlank() -> "Your server has no synopsis for this title."
                                else -> d!!.plot!!
                            },
                            fontSize = 13.5.sp, lineHeight = 20.sp, maxLines = 10,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            "Hidden because: ${row.reason}" + if (allowed) " · now allowed (exception)" else "",
                            fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFFFE2A8),
                            modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF2A2112), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { if (!allowed) vm.toggleAgeException(row.exKey); onDismiss() }) { Text("☐ Allow it (show to the children)") }
                            OutlinedButton(onClick = { if (allowed) vm.toggleAgeException(row.exKey); onDismiss() }) { Text("Keep hidden", color = green) }
                            TextButton(onClick = onDismiss) { Text("Close") }
                        }
                    }
                }
                if (wide) Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    poster(Modifier.width(150.dp).height(220.dp)); Box(Modifier.weight(1f)) { text() }
                } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    poster(Modifier.fillMaxWidth().height(170.dp)); text()
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsInfo(content: @Composable (wide: Boolean) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints { content(maxWidth >= 560.dp) }
}


/** Files made by an earlier install of Nova are hidden by Android until "All files access" is allowed. */
@Composable
fun AllFilesNotice() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || com.nova.iptv.data.Folders.hasAllFilesAccess()) return
    val context = LocalContext.current
    Text(
        "Files saved by an earlier install of Nova are hidden by Android. Allow “All files access” once to see them all — or use “Other file…”.",
        fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFFFD27A),
    )
    TextButton(onClick = {
        runCatching { context.startActivity(com.nova.iptv.data.Folders.allFilesAccessIntent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "Open Android Settings › Apps › Nova IPTV › All files access", Toast.LENGTH_LONG).show() }
    }) { Text("🔓 Allow all files access") }
}
