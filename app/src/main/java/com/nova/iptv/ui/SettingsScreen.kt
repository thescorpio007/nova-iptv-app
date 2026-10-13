package com.nova.iptv.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.nova.iptv.data.Account
import com.nova.iptv.data.Folders
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val infos by vm.userInfos.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ua by remember(s.userAgent) { mutableStateOf(s.userAgent) }
    var renaming by remember { mutableStateOf<Account?>(null) }
    var removing by remember { mutableStateOf<Account?>(null) }
    var editing by remember { mutableStateOf<Account?>(null) }

    val settingsScroll = rememberScrollState()
    val wideSettings = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    androidx.compose.runtime.CompositionLocalProvider(LocalPinSet provides s.hasPin) {
    Column(
        Modifier.fillMaxSize().scrollBar(settingsScroll).verticalScroll(settingsScroll).padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (s.hasPin) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (PinGate.isOpen) {
                    Text("🔓 Unlocked — changes allowed", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    OutlinedButton(onClick = { PinGate.lock() }) { Text("Lock now") }
                } else {
                    Text("🔒 Protected by the parental PIN — you can look; changes ask for the PIN.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        val accountsCard: @Composable () -> Unit = {
        Card("Accounts (${accounts.size})", summary = accounts.joinToString("  ·  ") { it.name }) {
            Text(
                "All accounts are shown together in Live TV, Films and Series, each as its own section of groups. " +
                    "Search looks in all of them.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            accounts.forEach { acc ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(acc.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (acc.fromList) Text(
                            "🔗 from your list", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text("${acc.server}  ·  ${acc.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    val i = infos[acc.id]
                    if (i != null) {
                        val exp = i.expDate?.let { DateFormat.getDateInstance().format(Date(it * 1000)) } ?: "Never"
                        Text("Status: ${i.status}${if (i.isTrial) " (trial)" else ""}  ·  Expires: $exp", fontSize = 13.sp)
                        Text("Connections: ${i.activeConnections} in use of ${i.maxConnections}", fontSize = 13.sp)
                    } else {
                        Text(
                            if (acc.id in infos) "Account not reachable right now" else "Checking…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        )
                    }
                    // Three buttons sharing the row, so they fit on a phone too.
                    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        OutlinedButton(onClick = { renaming = acc }, modifier = Modifier.weight(1f), contentPadding = pad) {
                            Text("Rename", maxLines = 1)
                        }
                        OutlinedButton(onClick = { removing = acc }, modifier = Modifier.weight(1f), contentPadding = pad) {
                            Text("Remove", color = MaterialTheme.colorScheme.error, maxLines = 1)
                        }
                        OutlinedButton(onClick = { editing = acc }, modifier = Modifier.weight(1f), contentPadding = pad) {
                            Text("Edit", maxLines = 1)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.openLogin(true) }) { Text("+ Add account") }
                OutlinedButton(onClick = { vm.loadUserInfo() }) { Text("Refresh status") }
            }
            AccountsLinkSection(vm)
        }
        }
        val liveFormatCard: @Composable () -> Unit = {
        Card("Live stream format") {
            Text(
                "MPEG-TS starts fastest on most servers. Try HLS if channels stutter or won't start.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("MPEG-TS (.ts)", s.liveFormat == "ts", { vm.setLiveFormat("ts") })
                Chip("HLS (.m3u8)", s.liveFormat == "m3u8", { vm.setLiveFormat("m3u8") })
            }
        }

        }
        val rollingCard: @Composable () -> Unit = {
        Card("Rolling buffer", summary = "Cushion ${s.cushionSeconds} s  ·  " + if (s.liveStart == "fast") "Fast start" else "Safe start") {
            Text(
                "Safety cushion: seconds of video collected before playback starts. Live TV plays this far " +
                    "behind real time, and that video keeps playing if the internet drops briefly. " +
                    "A bigger cushion means fewer freezes but slower channel changes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Stepper("Safety cushion", "${s.cushionSeconds} s", { vm.setCushion(s.cushionSeconds - 1) }, { vm.setCushion(s.cushionSeconds + 1) })
            Text("Channel start", fontWeight = FontWeight.Medium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Fast — start at live", s.liveStart == "fast", { vm.setLiveStart("fast") })
                Chip("Safe — wait for the full cushion", s.liveStart == "safe", { vm.setLiveStart("safe") })
            }
            Text(
                if (s.liveStart == "fast") "Channels start after ~2 s, close to live. The ${s.cushionSeconds} s cushion is then built " +
                    "while you watch by playing 3 % slower for a few minutes (voice pitch unchanged)."
                else "Channels start once the full ${s.cushionSeconds} s cushion is collected: slower zapping, safest start.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Read-ahead: how much the player keeps downloading ahead (held in memory, max ~128 MB).",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Stepper("Read-ahead", "${s.maxBufferSeconds} s", { vm.setMaxBuffer(s.maxBufferSeconds - 10) }, { vm.setMaxBuffer(s.maxBufferSeconds + 10) })
        }

        }
        val liveBufferCard: @Composable () -> Unit = {
        Card("Live TV buffer") {
            Text(
                "Memory: fastest channel changes. Disk: live TV is stored on the device while you watch, " +
                    "so you can pause it, rewind (⏪ 30s) and jump back to live, and it rides out longer internet drops. " +
                    "Disk mode works best with the MPEG-TS format.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Memory", !s.liveDiskBuffer, { vm.setLiveDiskBuffer(false) })
                Chip("Disk (pause & rewind)", s.liveDiskBuffer, { vm.setLiveDiskBuffer(true) })
            }
            if (s.liveDiskBuffer) {
                Stepper("Disk buffer size", "${s.liveDiskMb} MB", { vm.setLiveDiskMb(s.liveDiskMb - 200) }, { vm.setLiveDiskMb(s.liveDiskMb + 200) })
                Text(
                    "About ${s.liveDiskMb / 37} minutes of a typical HD channel (5 Mbps). Older parts are deleted automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                )
            }
        }

        }
        val engineCard: @Composable () -> Unit = {
        Card("Playback engine") {
            Text(
                "Tunneled playback sends video and audio through the box's hardware path together. On many Amlogic " +
                    "boxes (like Ugoos) it gives smoother motion and better lip-sync. If a channel shows a black " +
                    "screen or no sound with it on, switch it off.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Standard", !s.tunneling, { vm.setTunneling(false) })
                Chip("Tunneled", s.tunneling, { vm.setTunneling(true) })
            }
        }

        }
        val channelsCard: @Composable () -> Unit = {
        Card("Channels that don't work") {
            Text(
                "When a live channel doesn't start, or drops and can't reconnect, move to the next channel in the list automatically.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Skip automatically", s.autoSkip, { vm.setAutoSkip(true) })
                Chip("Stay and retry", !s.autoSkip, { vm.setAutoSkip(false) })
            }
            Stepper("Wait before skipping", "${s.skipTimeoutSec} s", { vm.setSkipTimeout(s.skipTimeoutSec - 2) }, { vm.setSkipTimeout(s.skipTimeoutSec + 2) })
        }

        }
        val readAheadCard: @Composable () -> Unit = {
        Card("Films & series: disk read-ahead") {
            Text(
                "One connection downloads the film ahead of you into small pieces on disk, and the player reads " +
                    "from there, so short internet drops don't stop the picture. Pieces you have watched are deleted " +
                    "as you go, and everything is deleted when you leave the film. With it on, only ~20 s are kept in memory.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0 to "Off", 50 to "50 MB", 100 to "100 MB", 200 to "200 MB", 300 to "300 MB", 500 to "500 MB", 1000 to "1 GB", 2000 to "2 GB")
                    .forEach { (mb, label) -> Chip(label, s.readAheadMb == mb, { vm.setReadAheadMb(mb) }) }
            }
            Text(
                when (s.readAheadMb) {
                    0 -> "Off: films stream as before, through the normal cache below."
                    else -> {
                        val mb = s.readAheadMb
                        "About ${mb * 8 / 5 / 60} min of an HD film (5 Mbit/s), ${mb * 8 / 10 / 60} min Full HD, " +
                            "${mb * 8 / 25} s of 4K. ~80 % is kept ahead of you, ~20 % behind for quick rewinds."
                    }
                },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            )
            if (s.readAheadMb == 0) {
                Stepper("Normal cache size", "${s.vodCacheMb} MB", { vm.setVodCache(s.vodCacheMb - 256) }, { vm.setVodCache(s.vodCacheMb + 256) })
                OutlinedButton(onClick = {
                    vm.clearVodCache { Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show() }
                }) { Text("Clear cache now") }
            }
        }

        }
        val advancedCard: @Composable () -> Unit = {
        Card("Advanced") {
            Text(
                "User-Agent sent to the server. Change only if your provider asks for a specific one.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            TvTextField(value = ua, onValueChange = { ua = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                vm.setUserAgent(ua)
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }) { Text("Save User-Agent") }
        }

        }
        val resetCard: @Composable () -> Unit = {
        var confirmReset by remember { mutableStateOf(false) }
        Card("Reset") {
            Text(
                "Puts every setting back to Nova's defaults (clock bottom left, disk live buffer, 15 s cushion (safe start), stay and retry on channels that don't work, age filter NC-17 with words, 500 MB disk read-ahead, " +
                    "app audio decoders, Back twice to leave live TV, films sorted by rating, storage in the box's Download/NovaIPTV folder…). " +
                    "Your servers, favorites, order, hidden items, PIN and downloads are kept.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            OutlinedButton(onClick = { confirmReset = true }) { Text("Reset all settings to Nova defaults") }
        }
        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Reset all settings?") },
                text = { Text("Servers, favorites, order, hidden items, PIN and downloads stay as they are.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.resetSettings()
                        confirmReset = false
                        Toast.makeText(context, "Settings reset to Nova defaults", Toast.LENGTH_SHORT).show()
                    }) { Text("Reset") }
                },
                dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
            )
        }

        }
        // Settings grouped by use, in this order (on a TV: two columns, groups 1–3 left, 4–7 right).
        val groups: List<Pair<String, @Composable () -> Unit>> = listOf(
            "👤  ACCOUNTS & SAFETY" to { accountsCard(); BackupSettings(vm); ParentalSettings(vm); VpnSettings(vm) },
            "📡  LIVE TV" to { liveBufferCard(); rollingCard(); WatchdogSettings(vm); channelsCard() },
            "▶  PLAYER" to { PlayerSettings(vm); engineCard(); liveFormatCard() },
            "🎬  FILMS & DOWNLOADS" to { readAheadCard(); DownloadSettings(vm) },
            "💾  STORAGE" to { StorageSettings(vm) },
            "🖥  SCREEN & REMOTE" to { DisplaySettings(vm); RemoteSettings(vm) },
            "🛠  SYSTEM" to {
                advancedCard(); LastProblemSettings(); resetCard()
                Text("Nova IPTV " + appVersion(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            },
        )
        @Composable
        fun Group(n: Int) {
            val (title, content) = groups[n]
            Text(
                "${n + 1}  $title", color = Color(0xFF9FC3FF), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            )
            content()
        }
        if (wideSettings) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { (0..2).forEach { Group(it) } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { (3..6).forEach { Group(it) } }
            }
        } else {
            groups.indices.forEach { Group(it) }
        }

    }
    }

    renaming?.let { acc ->
        var name by remember(acc.id) { mutableStateOf(acc.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename account") },
            text = { TvTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameAccount(acc, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    editing?.let { acc -> EditAccountDialog(vm, acc) { editing = null } }
    removing?.let { acc ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${acc.name}?") },
            text = { Text("Its channels, films and series disappear from the lists, with its favorites and history. Downloads stay on the device.") },
            confirmButton = {
                TextButton(onClick = { vm.removeAccount(acc); removing = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Card(title: String, summary: String? = null, content: @Composable () -> Unit) = SettingsCard(title, summary, content)

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(140.dp))
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(value, modifier = Modifier.width(90.dp).padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

/** Change any detail of a server: name, address, username, password. */
@Composable
fun EditAccountDialog(vm: MainViewModel, acc: Account, onDone: () -> Unit) {
    var name by remember(acc.id) { mutableStateOf(acc.name) }
    var server by remember(acc.id) { mutableStateOf(acc.server) }
    var user by remember(acc.id) { mutableStateOf(acc.username) }
    var pass by remember(acc.id) { mutableStateOf(acc.password) }
    var showPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current

    fun save(check: Boolean) {
        if (server.isBlank() || user.isBlank() || pass.isBlank()) {
            error = "Please fill in server, username and password."
            return
        }
        val updated = vm.editedAccount(acc, name, server, user, pass)
        if (!check) {
            vm.saveAccount(updated)
            Toast.makeText(context, "Account saved", Toast.LENGTH_SHORT).show()
            onDone()
            return
        }
        busy = true
        error = null
        scope.launch {
            val problem = vm.testAccount(updated)
            busy = false
            if (problem == null) {
                vm.saveAccount(updated)
                Toast.makeText(context, "Account saved and connected", Toast.LENGTH_SHORT).show()
                onDone()
            } else {
                error = "Couldn't connect with these details: $problem\nCheck them, or use “Save anyway” (e.g. if your VPN is off)."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Edit account") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TvTextField(server, { server = it }, label = { Text("Server URL (or full M3U link)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TvTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TvTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Hide password" else "Show password") }
                Text(
                    "Favorites, order and hidden items of this account are kept.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) Text("Checking the connection…", color = MaterialTheme.colorScheme.primary)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Row {
                if (error != null && !busy) TextButton(onClick = { save(false) }) { Text("Save anyway") }
                TextButton(onClick = { save(true) }, enabled = !busy) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") } },
    )
}
