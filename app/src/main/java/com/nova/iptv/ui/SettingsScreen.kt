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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
            SearchIndexSection(vm)
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
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Fast — start at live", s.liveStart == "fast", { vm.setLiveStart("fast") })
                Chip("Safe — wait for the full cushion", s.liveStart == "safe", { vm.setLiveStart("safe") })
            }
            Text(
                if (s.liveStart == "fast") "Channels start after ~2 s, close to live. The ${s.cushionSeconds} s cushion is then built " +
                    "while you watch by playing 3 % slower for a few minutes (voice pitch unchanged)."
                else "Channels start once the full ${s.cushionSeconds} s cushion is collected: slower zapping, safest start.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            )
            // More protection without pausing.
            val st = vm.appStorage
            var grow by remember { mutableStateOf(st.liveGrowTarget) }
            var slowAction by remember { mutableStateOf(st.liveSlowAction) }
            Text("Grow the cushion while watching", fontWeight = FontWeight.Medium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Off", 30 to "30 s", 60 to "60 s", 120 to "2 min").forEach { (v, l) ->
                    Chip(l, grow == v, { grow = v; st.liveGrowTarget = v })
                }
            }
            Text(
                if (grow == 0) "Off: you stay ${s.cushionSeconds} s behind live."
                else if (s.liveDiskBuffer) "Plays 4 % slower (sound unchanged) until you are ${if (grow >= 60) "${grow / 60} min" else "$grow s"} behind live, " +
                    "then normal speed — the disk keeps up to ${vm.appStorage.liveDiskMinutes} min for rewind and pauses. " +
                    "After a slow moment it refills the same way — no pause, no freeze, never a jump to live."
                else "Plays 4 % slower (sound unchanged) until you are $grow s behind live (at most your read-ahead), then normal speed. " +
                    "After a slow moment it refills the same way — no pause, no freeze.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            )
            Text("When the line is too slow for a channel", fontWeight = FontWeight.Medium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Switch to my other server", slowAction == "switch", { slowAction = "switch"; st.liveSlowAction = "switch" })
                Chip("Propose similar channels", slowAction == "similar", { slowAction = "similar"; st.liveSlowAction = "similar" })
                Chip("Stay", slowAction == "stay", { slowAction = "stay"; st.liveSlowAction = "stay" })
            }
            Text(
                when (slowAction) {
                    "switch" -> "If the signal stays below what the channel needs for ~20 s, Nova plays the same channel from your other server. "
                    "similar" -> "If the signal stays below what the channel needs for ~20 s, a popup proposes the same channel from other " +
                        "groups of the same server, lighter versions (HD / SD) first. Also any time with ≈ Similar in the player. "
                    else -> "Nova stays on the channel (≈ Similar in the player still proposes the same channel from other groups). "
                } + "A warning shows when only 5 s of cushion is left.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            if (!s.liveDiskBuffer) {
                Text("Memory size", fontWeight = FontWeight.Medium)
                Text(
                    "Memory used to hold video ahead. More helps unstable connections; less is safer on boxes with little memory.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(32, 64, 128, 256).forEach { mb -> Chip("$mb MB", s.bufferMb == mb, { vm.setBufferMb(mb) }) }
                }
                Stepper("Read-ahead", "${s.maxBufferSeconds} s", { vm.setMaxBuffer(s.maxBufferSeconds - 10) }, { vm.setMaxBuffer(s.maxBufferSeconds + 10) })
                Text(
                    "${s.bufferMb} MB holds about ${minOf(s.maxBufferSeconds, s.bufferMb * 8 / 5)} s of an HD channel, " +
                        "${minOf(s.maxBufferSeconds, s.bufferMb * 8 / 25)} s of 4K — whichever limit comes first.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                )
            }
            if (s.liveDiskBuffer) {
                var minutes by remember { mutableStateOf(vm.appStorage.liveDiskMinutes) }
                Text("Disk buffer length", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 60).forEach { m -> Chip("$m min", minutes == m, { minutes = m; vm.appStorage.liveDiskMinutes = m }) }
                }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val freeGb = remember(minutes) {
                    runCatching {
                        val d = com.nova.iptv.data.Folders.bufferDir(ctx, com.nova.iptv.data.Folders.BUFFER_LIVE, vm.appStorage.usePublicFolder).apply { mkdirs() }
                        android.os.StatFs(d.absolutePath).availableBytes / 1073741824.0
                    }.getOrDefault(-1.0)
                }
                fun gb(mbPerMin: Double) = minutes * mbPerMin / 1024.0
                Text(
                    "Up to $minutes min of the channel are kept on disk: pause the live, come back later and continue where you stopped " +
                        "(or ⏭ Live to jump to now). Older parts are deleted automatically. " +
                        "Disk space at most: SD ≈ ${"%.1f".format(gb(18.0))} GB · HD ≈ ${"%.1f".format(gb(30.0))} GB · " +
                        "FHD ≈ ${"%.1f".format(gb(60.0))} GB · 4K ≈ ${"%.1f".format(gb(190.0))} GB.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                )
                if (freeGb >= 0) {
                    val enough = freeGb - 0.5 >= gb(60.0)
                    Text(
                        (if (enough) "✓ Free on this device: ${"%.1f".format(freeGb)} GB — enough."
                        else "⚠ Free on this device: ${"%.1f".format(freeGb)} GB — an FHD channel would keep less than $minutes min.") +
                            " If space runs short, Nova keeps less and tells you.",
                        color = if (enough) MaterialTheme.colorScheme.primary else Color(0xFFFFD27A), fontSize = 12.sp,
                    )
                }
                BufferFolderLine(com.nova.iptv.data.Folders.BUFFER_LIVE)
                Text(
                    "💾 Memory size: automatic (64 MB) — the disk holds the protection; the player keeps only a few seconds in memory. " +
                        "Choose Memory above to set it yourself.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.background(Color(0xFF1C2633), RoundedCornerShape(8.dp)).padding(8.dp),
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
            OtherAppSettings(vm)
            var dotsSetting by remember { mutableStateOf(vm.appStorage.statusDots) }
            Text("Status dots", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Text(
                "A dot appears after you open a film, episode or channel: green it played, red it didn't work (with the reason), " +
                    "orange it works but this device can't play its format. Nothing is checked automatically, and films you never " +
                    "opened have no dot. Results are kept 7 days.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("On", dotsSetting, { dotsSetting = true; vm.appStorage.statusDots = true })
                Chip("Off", !dotsSetting, { dotsSetting = false; vm.appStorage.statusDots = false })
            }
        }

        }
        val readAheadCard: @Composable () -> Unit = {
        Card("Films & series buffer (disk read-ahead)") {
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
            BufferFolderLine(com.nova.iptv.data.Folders.BUFFER_MOVIES)
            BufferFolderLine("Buffer/readahead-hls", "Streaming films (HLS)")
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
                "Puts every setting back to Nova's defaults (clock bottom left, disk live buffer 15 min, 15 s cushion (fast start) growing to 2 min, " +
                    "skip channels that don't work after 8 s, propose similar channels when the line is too slow, open in another app: ask (format problems), " +
                    "age filter NC-17 with words, 1 GB disk read-ahead, " +
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
            "🎬  FILMS & DOWNLOADS" to { readAheadCard(); DownloadSettings(vm); WebSettings() },
            "💾  STORAGE" to { StorageSettings(vm); BuffersSummary() },
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
    // Narrow screens (phones): the label above, so − value + always fit.
    androidx.compose.foundation.layout.BoxWithConstraints {
        val narrow = maxWidth < 420.dp
        val buttons: @Composable () -> Unit = {
            OutlinedButton(onClick = onMinus) { Text("−") }
            Text(value, modifier = Modifier.width(90.dp).padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onPlus) { Text("+") }
        }
        if (narrow) {
            androidx.compose.foundation.layout.Column {
                Text(label)
                Row(verticalAlignment = Alignment.CenterVertically) { buttons() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.width(140.dp))
                buttons()
            }
        }
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


// ---------------------------------------------------------------------------- buffers: folder and what they use now

private fun dirBytes(f: java.io.File?): Long =
    f?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

private fun mbText(b: Long): String = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else "${b / 1048576} MB"

/** "Own folder: …/Buffers/live · now 380 MB" (refreshed every few seconds while shown). */
@Composable
fun BufferFolderLine(sub: String, label: String = "Own folder") {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.nova.iptv.NovaApp
    val info by androidx.compose.runtime.produceState("…", sub) {
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val d = com.nova.iptv.data.Folders.bufferDir(context, sub, app.storage.usePublicFolder)
                    val extra = if (sub == com.nova.iptv.data.Folders.BUFFER_LIVE)
                        dirBytes(com.nova.iptv.data.Folders.bufferDir(context, com.nova.iptv.data.Folders.BUFFER_LIVE_HLS, app.storage.usePublicFolder)) else 0L
                    d.absolutePath.removePrefix("/storage/emulated/0/") + " · now " + mbText(dirBytes(d) + extra)
                }.getOrDefault("—")
            }
            kotlinx.coroutines.delay(4000)
        }
    }
    Text("$label: $info", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
}

/** Settings › Storage › Buffers: both buffers at a glance, and "Clear both". */
@Composable
fun BuffersSummary() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.nova.iptv.NovaApp
    var tick by remember { mutableStateOf(0) }
    val text by androidx.compose.runtime.produceState("…", tick) {
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val st = app.storage
                    val live = dirBytes(com.nova.iptv.data.Folders.bufferDir(context, com.nova.iptv.data.Folders.BUFFER_LIVE, st.usePublicFolder)) +
                        dirBytes(com.nova.iptv.data.Folders.bufferDir(context, com.nova.iptv.data.Folders.BUFFER_LIVE_HLS, st.usePublicFolder))
                    val films = dirBytes(com.nova.iptv.data.Folders.bufferDir(context, com.nova.iptv.data.Folders.BUFFER_MOVIES, st.usePublicFolder))
                    val dir = com.nova.iptv.data.Folders.bufferDir(context, com.nova.iptv.data.Folders.BUFFER_LIVE, st.usePublicFolder)
                    val free = android.os.StatFs(dir.absolutePath).availableBytes
                    "📡 Live: ${mbText(live)}" + (if (st.liveDiskBuffer) " (disk, up to ${st.liveDiskMinutes} min)" else " (memory mode)") +
                        "\n🎬 Films & series: ${mbText(films)}" + (if (st.readAheadMb > 0) " (read-ahead ${st.readAheadMb} MB + cache)" else " (cache)") +
                        "\nFree on this device: ${mbText(free)}"
                }.getOrDefault("—")
            }
            kotlinx.coroutines.delay(5000)
        }
    }
    SettingsCard("Buffers") {
        Text(
            "Live TV and films & series use separate folders on disk; each is emptied when you leave the channel / film.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, fontSize = 13.sp)
        OutlinedButton(onClick = {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val st = app.storage
                listOf(com.nova.iptv.data.Folders.BUFFER_LIVE, com.nova.iptv.data.Folders.BUFFER_LIVE_HLS).forEach { sub ->
                    runCatching { com.nova.iptv.data.Folders.bufferDir(context, sub, st.usePublicFolder).listFiles()?.forEach { it.deleteRecursively() } }
                }
                runCatching {
                    java.io.File(com.nova.iptv.data.Folders.bufferDir(context, com.nova.iptv.data.Folders.BUFFER_MOVIES, st.usePublicFolder), "readahead")
                        .listFiles()?.forEach { it.deleteRecursively() }
                }
                // Streaming films: emptied through the cache itself (its files can't simply be deleted while it is open).
                runCatching { app.vodHlsCache.keys.toList().forEach { k -> runCatching { app.vodHlsCache.removeResource(k) } } }
                tick++
            }
            Toast.makeText(context, "Buffers cleared (the film cache is cleared with “Clear cache now”)", Toast.LENGTH_SHORT).show()
        }) { Text("🗑 Clear both") }
    }
}
