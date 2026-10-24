package com.nova.iptv.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.RemoteKeys

/** Settings → Remote control: Back behaviour, number keys, and every action's buttons (TiviMate style). */
@Composable
fun RemoteSettings(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val storage = vm.appStorage
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val map = remember(version) { RemoteKeys.map(storage) }
    var openArea by remember { mutableStateOf<RemoteKeys.Area?>(null) }
    var assigning by remember { mutableStateOf<RemoteKeys.Action?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    /** A button already used elsewhere: action wanted, button, actions using it now. */
    var clash by remember { mutableStateOf<Triple<RemoteKeys.Action, Int, List<RemoteKeys.Action>>?>(null) }

    SettingsCard("Remote control") {
        Text(
            "Choose what each remote button does. Buttons work straight away, even while the player's menu is on screen. " +
                "Menus you open (channel list, audio & subtitles) still use the arrows to move inside them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )

        Text("Back button in live TV", fontWeight = FontWeight.Medium)
        ChoicePills(
            listOf("once" to "Press once to leave", "twice" to "Press twice to leave"),
            if (s.doubleBackLive) "twice" else "once",
        ) { vm.setDoubleBackLive(it == "twice") }
        Text("Back button in films & series", fontWeight = FontWeight.Medium)
        var vodTwice by remember { mutableStateOf(storage.doubleBackVod) }
        ChoicePills(
            listOf("twice" to "Press twice to stop the video", "once" to "Press once to stop"),
            if (vodTwice) "twice" else "once",
        ) { storage.doubleBackVod = it == "twice"; vodTwice = it == "twice" }
        Text("Back button on the main screen", fontWeight = FontWeight.Medium)
        ChoicePills(
            listOf("twice" to "Press twice to close Nova", "once" to "Press once to close"),
            if (s.doubleBackExit) "twice" else "once",
        ) { vm.setDoubleBackExit(it == "twice") }
        Text("▲ ▼ in live TV", fontWeight = FontWeight.Medium)
        ChoicePills(
            listOf("tv" to "▲ next channel · ▼ previous", "list" to "▲ previous · ▼ next channel"),
            if (remember(version) { storage.tvArrows }) "tv" else "list",
        ) { RemoteKeys.setTvArrows(storage, it == "tv"); version++ }
        Text("Films & series posters (TV)", fontWeight = FontWeight.Medium)
        ChoicePills(
            listOf("stay" to "Stay inside the poster list (Back to leave)", "free" to "Move freely to groups and tabs"),
            if (s.stayInPosters) "stay" else "free",
        ) { vm.setStayInPosters(it == "stay") }
        Text("Number buttons (0–9) in live TV", fontWeight = FontWeight.Medium)
        ChoicePills(
            listOf("on" to "Type a channel number", "off" to "Not used"),
            if (s.numberKeys) "on" else "off",
        ) { vm.setNumberKeys(it == "on") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { testing = true }) { Text("Test a button") }
            OutlinedButton(onClick = { confirmReset = true }) { Text("Reset all buttons") }
        }

        Text("Buttons for each action", fontWeight = FontWeight.Medium)
        RemoteKeys.Area.entries.forEach { area ->
            FocusBox(
                onClick = { openArea = if (openArea == area) null else area },
                modifier = Modifier.fillMaxWidth(),
                selected = openArea == area,
            ) { focused ->
                Text(
                    (if (openArea == area) "▾  " else "▸  ") + area.label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    fontWeight = FontWeight.Bold,
                    color = if (openArea == area || focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (openArea == area) {
                RemoteKeys.actions.filter { it.area == area }.forEach { a ->
                    val keys = map[a.id].orEmpty()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(a.label, fontWeight = FontWeight.Medium)
                        Text(
                            if (keys.isEmpty()) "No button" else keys.joinToString("  ·  ") { RemoteKeys.keyName(it) },
                            fontSize = 13.sp,
                            color = if (keys.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                            SmallButton("＋ Button", { assigning = a })
                            if (keys.isNotEmpty()) SmallButton("Clear", { RemoteKeys.clear(storage, a.id); version++ }, warn = true)
                            if (keys != RemoteKeys.defaultsOf(storage, a)) SmallButton("Default", { RemoteKeys.resetAction(storage, a.id); version++ })
                        }
                    }
                }
            }
        }
    }

    assigning?.let { a ->
        KeyCaptureDialog(
            title = a.label,
            message = "Press the remote button you want for “${a.label}”.\nBack cancels.",
            onDismiss = { assigning = null },
        ) { code ->
            assigning = null
            if (RemoteKeys.isReserved(code)) {
                Toast.makeText(context, "${RemoteKeys.keyName(code)} is kept for Android and can't be changed", Toast.LENGTH_LONG).show()
            } else {
                val others = RemoteKeys.conflicts(storage, a.id, code)
                if (others.isNotEmpty()) {
                    clash = Triple(a, code, others)
                } else {
                    RemoteKeys.assign(storage, a.id, code)
                    version++
                    Toast.makeText(context, "${RemoteKeys.keyName(code)} → ${a.label}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    clash?.let { (a, code, others) ->
        // ⚠ Already used: replace, choose another button, or cancel (cancels by itself after 10 s).
        var left by remember(clash) { mutableIntStateOf(10) }
        LaunchedEffect(clash) {
            while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
            clash = null
        }
        AlertDialog(
            onDismissRequest = { clash = null },
            title = { Text("⚠ This button is already used", color = androidx.compose.ui.graphics.Color(0xFFFFB347)) },
            text = {
                Text(
                    "${RemoteKeys.keyName(code)} is already set to:\n" +
                        others.joinToString("\n") { "• ${it.label}  (${it.area.label})" } +
                        "\n\nUse it for “${a.label}” instead?\nCancels by itself in $left s.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RemoteKeys.assign(storage, a.id, code)
                    version++
                    clash = null
                    Toast.makeText(context, "${RemoteKeys.keyName(code)} → ${a.label} (removed from: ${others.joinToString { it.label }})", Toast.LENGTH_LONG).show()
                }) { Text("Use it (remove the other)") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { clash = null; assigning = a }) { Text("Choose another button") }
                    TextButton(onClick = { clash = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (testing) {
        var last by remember { mutableStateOf<Int?>(null) }
        val lastCode = last
        KeyCaptureDialog(
            title = "Test a button",
            message = if (lastCode == null) "Press any button on your remote.\nBack closes this window."
            else {
                val uses = RemoteKeys.actions.filter { lastCode in map[it.id].orEmpty() }
                    .joinToString("\n") { "• ${it.area.label}: ${it.label}" }
                "${RemoteKeys.keyName(lastCode)}  (code $lastCode)\n\n" +
                    (uses.ifBlank { "Not used by Nova yet." }) + "\n\nPress another button, or Back to close."
            },
            onDismiss = { testing = false },
            keepOpen = true,
        ) { code -> last = code }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all buttons?") },
            text = { Text("Every action gets its original buttons back.") },
            confirmButton = {
                TextButton(onClick = { RemoteKeys.resetAll(storage); version++; confirmReset = false }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/** Waits for a remote button. Back closes it (Back itself can't be assigned). */
@Composable
private fun KeyCaptureDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    keepOpen: Boolean = false,
    onKey: (Int) -> Unit,
) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                            val code = e.nativeKeyEvent.keyCode
                            if (code == android.view.KeyEvent.KEYCODE_BACK) onDismiss() else onKey(code)
                        }
                        true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(message, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (keepOpen) "Close" else "Cancel") } },
    )
}

@Composable
private fun ChoicePills(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> Chip(label, value == selected, { onSelect(value) }) }
    }
}
