package com.nova.iptv.ui

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nova.iptv.data.RemoteKeys
import com.nova.iptv.data.Storage

/**
 * Settings › Remote control › 🎮 Your remote: a drawn remote with an arrow from every button to what it does, one tab
 * per place (live TV, films & series, lists). Select a label — or press that button on the real remote — to change it.
 */
private data class RemoteButton(
    val code: Int,
    val name: String,
    val x: Float, val y: Float,
    val left: Boolean,
    /** null = can be changed here; otherwise the fixed text shown. */
    val fixed: String? = null,
)

private val ACCENT = Color(0xFF5FF2D6)

private fun buttons(storage: Storage, tab: RemoteKeys.Area): List<RemoteButton> {
    val back = if (tab == RemoteKeys.Area.LISTS) "Back (Settings › Remote control)"
    else "Leave · " + (if (if (tab == RemoteKeys.Area.LIVE) storage.doubleBackLive else storage.doubleBackVod) "press twice" else "press once")
    val digits = if (tab == RemoteKeys.Area.LIVE && storage.numberKeys) "Type a channel number" else "Not used"
    return listOf(
        // left column (top to bottom)
        RemoteButton(KeyEvent.KEYCODE_VOLUME_MUTE, "🔇 Mute", 455f, 100f, true, "Sound off (Android)"),
        RemoteButton(KeyEvent.KEYCODE_DPAD_UP, "▲", 500f, 177f, true),
        RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT, "◀", 462f, 215f, true),
        RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER, "OK", 500f, 215f, true),
        RemoteButton(KeyEvent.KEYCODE_BACK, "↩ Back", 450f, 300f, true, back),
        RemoteButton(KeyEvent.KEYCODE_VOLUME_UP, "VOL ±", 455f, 380f, true, "Volume (Android)"),
        RemoteButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "⏯", 500f, 380f, true),
        RemoteButton(KeyEvent.KEYCODE_MEDIA_REWIND, "⏪", 458f, 455f, true),
        RemoteButton(KeyEvent.KEYCODE_MEDIA_RECORD, "● Rec", 500f, 455f, true),
        RemoteButton(KeyEvent.KEYCODE_PROG_RED, "🔴 Red", 449f, 495f, true),
        RemoteButton(KeyEvent.KEYCODE_PROG_GREEN, "🟢 Green", 483f, 495f, true),
        RemoteButton(KeyEvent.KEYCODE_1, "0–9", 465f, 535f, true, digits),
        // right column
        RemoteButton(KeyEvent.KEYCODE_POWER, "⏻", 545f, 100f, false, "Android (not changeable)"),
        RemoteButton(KeyEvent.KEYCODE_DPAD_RIGHT, "▶", 538f, 215f, false),
        RemoteButton(KeyEvent.KEYCODE_DPAD_DOWN, "▼", 500f, 253f, false),
        RemoteButton(KeyEvent.KEYCODE_HOME, "⌂ Home", 500f, 306f, false, "Android (not changeable)"),
        RemoteButton(KeyEvent.KEYCODE_MENU, "☰ Menu", 550f, 300f, false),
        RemoteButton(KeyEvent.KEYCODE_CHANNEL_UP, "CH +", 545f, 360f, false),
        RemoteButton(KeyEvent.KEYCODE_CHANNEL_DOWN, "CH −", 545f, 400f, false),
        RemoteButton(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "⏩", 542f, 455f, false),
        RemoteButton(KeyEvent.KEYCODE_PROG_YELLOW, "🟡 Yellow", 517f, 495f, false),
        RemoteButton(KeyEvent.KEYCODE_PROG_BLUE, "🔵 Blue", 551f, 495f, false),
    )
}

/** "⬛ Hide / show the picture (shows a fake error) · OK twice…" → "⬛ Hide / show the picture". */
private fun shortName(label: String) = label.substringBefore(" · ").substringBefore(" (").trim()

@Composable
fun RemoteMapDialog(storage: Storage, onClose: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(RemoteKeys.Area.LIVE) }
    var version by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<RemoteButton?>(null) }
    var clash by remember { mutableStateOf<Triple<RemoteButton, RemoteKeys.Action, List<RemoteKeys.Action>>?>(null) }
    val list = remember(tab, version) { buttons(storage, tab) }

    fun labelFor(b: RemoteButton): Pair<String, String?> {
        b.fixed?.let { return it to null }
        val a = RemoteKeys.actionInTab(storage, tab, b.code) ?: return "Not used" to null
        val extra = if (a.id == "play_hide") "OK twice = player buttons · hold = Rec / Download" else null
        return shortName(a.label) to extra
    }

    fun apply(b: RemoteButton, a: RemoteKeys.Action) {
        RemoteKeys.assign(storage, a.id, b.code)
        version++
        Toast.makeText(context, "${b.name} → ${shortName(a.label)}", Toast.LENGTH_SHORT).show()
    }

    fun choose(b: RemoteButton, a: RemoteKeys.Action) {
        val current = RemoteKeys.actionInTab(storage, tab, b.code)
        // The action shown on this tab is simply replaced; anything else using the button (other player…) is asked first.
        val others = RemoteKeys.conflicts(storage, a.id, b.code).filter { it.id != current?.id || it.area != tab }
        if (others.isEmpty()) apply(b, a) else clash = Triple(b, a, others)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0F1D3A))
                .onPreviewKeyEvent { e ->
                    // Press a button of the real remote: its list opens (arrows / OK / Back still move around the page).
                    if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount != 0 || picking != null) return@onPreviewKeyEvent false
                    val code = e.nativeKeyEvent.keyCode
                    val nav = code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)
                    if (nav) return@onPreviewKeyEvent false
                    val b = list.firstOrNull { it.code == code && it.fixed == null } ?: return@onPreviewKeyEvent false
                    picking = b
                    true
                },
        ) {
            val s = minOf(maxWidth.value / 1000f, maxHeight.value / 700f)
            fun d(v: Float) = (v * s).dp
            val top = 60f
            // ---- the remote and the arrows
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / (maxWidth.value).coerceAtLeast(1f) * s // px per design unit
                fun p(x: Float, y: Float) = Offset(x * k, (y + top) * k)
                val body = Color(0xFF2A2F3A)
                drawRoundRect(body, p(420f, 70f), Size(160f * k, 540f * k), CornerRadius(50f * k))
                drawRoundRect(Color(0xFF4B5568), p(420f, 70f), Size(160f * k, 540f * k), CornerRadius(50f * k), style = Stroke(2f * k))
                val key = Color(0xFF3A4152)
                drawCircle(key, 52f * k, p(500f, 215f))
                drawCircle(ACCENT, 22f * k, p(500f, 215f))
                listOf(455f to 100f, 545f to 100f, 450f to 300f, 500f to 306f, 550f to 300f, 500f to 380f, 458f to 455f, 500f to 455f, 542f to 455f)
                    .forEach { (x, y) -> drawCircle(if (x == 545f && y == 100f) Color(0xFFC0392B) else key, 13f * k, p(x, y)) }
                drawRoundRect(key, p(440f, 345f), Size(30f * k, 75f * k), CornerRadius(15f * k))
                drawRoundRect(key, p(530f, 345f), Size(30f * k, 75f * k), CornerRadius(15f * k))
                listOf(Color(0xFFE74C3C), Color(0xFF2ECC71), Color(0xFFF1C40F), Color(0xFF3498DB)).forEachIndexed { i, c ->
                    drawRoundRect(c, p(436f + i * 34f, 489f), Size(26f * k, 12f * k), CornerRadius(6f * k))
                }
                for (r in 0 until 4) for (c in 0 until 3) {
                    if (r == 3 && c != 1) continue
                    drawCircle(key, 11f * k, p(465f + c * 35f, 535f + r * 22f))
                }
                // arrows: label → button
                val n = list.groupBy { it.left }
                n.forEach { (left, items) ->
                    items.forEachIndexed { i, b ->
                        val ly = 30f + i * (560f / items.size.coerceAtLeast(1)) + 14f
                        val from = if (left) p(372f, ly) else p(628f, ly)
                        val to = p(b.x, b.y)
                        drawLine(ACCENT, from, to, 1.5f * k)
                        val dx = to.x - from.x; val dy = to.y - from.y
                        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                        val ux = dx / len; val uy = dy / len
                        val head = Path().apply {
                            moveTo(to.x, to.y)
                            lineTo(to.x - ux * 9f * k - uy * 5f * k, to.y - uy * 9f * k + ux * 5f * k)
                            lineTo(to.x - ux * 9f * k + uy * 5f * k, to.y - uy * 9f * k - ux * 5f * k)
                            close()
                        }
                        drawPath(head, ACCENT)
                    }
                }
            }
            // ---- title, tabs, close
            Column(Modifier.padding(start = d(20f), top = d(10f))) {
                Text("🎮 Your remote", fontSize = (20f * s).sp, fontWeight = FontWeight.Bold, color = ACCENT)
                Row(horizontalArrangement = Arrangement.spacedBy(d(8f)), modifier = Modifier.padding(top = d(4f))) {
                    listOf(RemoteKeys.Area.LIVE to "Live TV", RemoteKeys.Area.VOD to "Films & series", RemoteKeys.Area.LISTS to "Lists & menus")
                        .forEach { (a, l) -> Chip(l, tab == a, { tab = a }) }
                    Chip("✕ Close", false, onClose)
                }
            }
            Text(
                "Select a label — or press that button on your remote — to choose what it does.\nNot every remote has every button.",
                fontSize = (11f * s).sp, color = Color(0xFF9FB3E0), textAlign = TextAlign.End,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = d(12f), end = d(20f)).widthIn(max = d(330f)),
            )
            // ---- labels
            list.groupBy { it.left }.forEach { (left, items) ->
                items.forEachIndexed { i, b ->
                    val ly = 30f + i * (560f / items.size.coerceAtLeast(1)) + top
                    val (text, extra) = labelFor(b)
                    val modifier = Modifier.offset(x = if (left) d(32f) else d(632f), y = d(ly)).width(d(336f))
                    Box(modifier, contentAlignment = if (left) Alignment.CenterEnd else Alignment.CenterStart) {
                        FocusBox(onClick = { if (b.fixed == null) picking = b }, shape = RoundedCornerShape(d(9f))) { focused ->
                            Column(
                                Modifier
                                    .background(if (focused) Color(0xFF2F4B7C) else Color(0xFF16264A), RoundedCornerShape(d(9f)))
                                    .border(1.dp, if (focused) ACCENT else Color(0xFF2F4B7C), RoundedCornerShape(d(9f)))
                                    .padding(horizontal = d(9f), vertical = d(4f)),
                                horizontalAlignment = if (left) Alignment.End else Alignment.Start,
                            ) {
                                Text(
                                    b.name + "  " + text, fontSize = (13f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (b.fixed != null) Color(0xFF9FB3E0) else Color.White,
                                )
                                extra?.let { Text(it, fontSize = (10f * s).sp, color = Color(0xFF9FB3E0), maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- choose an action for a button
    picking?.let { b ->
        val current = RemoteKeys.actionInTab(storage, tab, b.code)
        Dialog(onDismissRequest = { picking = null }) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .background(Color(0xFF16264A), RoundedCornerShape(16.dp))
                    .border(2.dp, ACCENT, RoundedCornerShape(16.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val place = when (tab) { RemoteKeys.Area.LIVE -> "Live TV player"; RemoteKeys.Area.VOD -> "Films & series player"; else -> "Lists & menus" }
                Text("${b.name} button — $place", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ACCENT)
                Text("Choose what this button does. ▲▼ to move · OK to choose", fontSize = 13.sp, color = Color(0xFF9FB3E0))
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(RemoteKeys.actionsForTab(tab)) { a ->
                        FocusBox(onClick = { picking = null; choose(b, a) }, modifier = Modifier.fillMaxWidth()) { f ->
                            Text(
                                shortName(a.label) + if (a.id == current?.id) "  (now)" else "",
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (f) Color.White else Color(0xFF1F3159), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                color = if (f) Color(0xFF0D2236) else if (a.id == current?.id) Color(0xFF7FE8B0) else Color.White,
                                fontWeight = if (f) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Nothing", { RemoteKeys.clearKeyInTab(storage, tab, b.code); version++; picking = null })
                    SmallButton("Default", { RemoteKeys.defaultKeyInTab(storage, tab, b.code); version++; picking = null })
                    SmallButton("Cancel", { picking = null })
                }
            }
        }
    }

    // ---- the button is also used elsewhere: ask first (cancels by itself after 10 s)
    clash?.let { (b, a, others) ->
        var left by remember(clash) { mutableIntStateOf(10) }
        LaunchedEffect(clash) {
            while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
            clash = null
        }
        AlertDialog(
            onDismissRequest = { clash = null },
            title = { Text("⚠ This button is already used", color = Color(0xFFFFB347)) },
            text = {
                Text(
                    "${b.name} is also set to:\n" + others.joinToString("\n") { "• ${shortName(it.label)}  (${it.area.label})" } +
                        "\n\nUse it for “${shortName(a.label)}” instead?\nCancels by itself in $left s.",
                )
            },
            confirmButton = { TextButton(onClick = { clash = null; apply(b, a) }) { Text("Use it (remove the other)") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { clash = null; picking = b }) { Text("Choose another action") }
                    TextButton(onClick = { clash = null }) { Text("Cancel") }
                }
            },
        )
    }
}
