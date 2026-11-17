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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.height
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
    /** A button whose code Nova learns by one press (□ △ ○ ★, app keys): its id; code 0 until learnt. */
    val learn: String? = null,
)

private val ACCENT = Color(0xFF5FF2D6)

private fun buttons(c: android.content.Context, storage: Storage, tab: RemoteKeys.Area): List<RemoteButton> {
    val style = RemoteKeys.style(c)
    if (style == "full") return fullButtons(storage, tab)
    val back = if (tab == RemoteKeys.Area.LISTS) "One step back"
    else "Close · leave: " + (if (if (tab == RemoteKeys.Area.LIVE) storage.doubleBackLive else storage.doubleBackVod) "press twice" else "press once")
    fun L(id: String, name: String, x: Float, y: Float, left: Boolean, app: Boolean = false): RemoteButton {
        val code = RemoteKeys.learnt(c, "$style.$id")
        return RemoteButton(if (code != 0) code else -1 - id.hashCode().and(0xFFFF), name, x, y, left,
            fixed = if (code == 0 && app) "Opens an app (Android) · select to teach Nova" else null, learn = "$style.$id")
    }
    val pad = listOf(
        RemoteButton(KeyEvent.KEYCODE_DPAD_UP, "▲", 500f, 177f, true),
        RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT, "◀", 462f, 215f, true),
        RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER, "OK", 500f, 215f, true),
        RemoteButton(KeyEvent.KEYCODE_DPAD_RIGHT, "▶", 538f, 215f, false),
        RemoteButton(KeyEvent.KEYCODE_DPAD_DOWN, "▼", 500f, 253f, false),
    )
    return when (style) {
        "stick" -> listOf(
            RemoteButton(KeyEvent.KEYCODE_POWER, "⏻", 500f, 95f, true, "Android (not changeable)"),
            RemoteButton(KeyEvent.KEYCODE_SEARCH, "🎤", 500f, 135f, false, "Voice (Android)"),
        ) + pad + listOf(
            L("star", "★", 458f, 310f, true),
            RemoteButton(KeyEvent.KEYCODE_BACK, "↩ Back", 500f, 310f, true, back),
            RemoteButton(KeyEvent.KEYCODE_HOME, "⌂ Home", 542f, 310f, false, "Android (not changeable)"),
            L("app1", "App 1", 475f, 350f, true, app = true), L("app2", "App 2", 525f, 350f, false, app = true),
            L("app3", "App 3", 475f, 380f, true, app = true), L("app4", "App 4", 525f, 380f, false, app = true),
            RemoteButton(KeyEvent.KEYCODE_VOLUME_UP, "VOL ±", 500f, 440f, false, "Volume (Android)"),
        )
        "air" -> listOf(
            RemoteButton(KeyEvent.KEYCODE_POWER, "⏻", 455f, 100f, true, "Android (not changeable)"),
            L("square", "□", 455f, 140f, true), L("triangle", "△", 500f, 140f, true),
            RemoteButton(KeyEvent.KEYCODE_VOLUME_MUTE, "🔇 Mute", 545f, 100f, false, "Sound off (Android)"),
            L("circle", "○", 545f, 140f, false),
        ) + pad.map { it.copy(y = it.y + 20f) } + listOf(
            RemoteButton(KeyEvent.KEYCODE_DEL, "⌫", 455f, 325f, true),
            RemoteButton(KeyEvent.KEYCODE_VOLUME_DOWN, "VOL −", 470f, 375f, true, "Volume (Android)"),
            RemoteButton(0, "⦿ Mouse", 500f, 325f, false, "Mouse pointer (the remote itself)"),
            RemoteButton(KeyEvent.KEYCODE_BACK, "↩ Back", 545f, 325f, false, back),
            RemoteButton(KeyEvent.KEYCODE_VOLUME_UP, "VOL +", 530f, 375f, false, "Volume (Android)"),
        )
        else -> listOf( // "round"
            RemoteButton(KeyEvent.KEYCODE_POWER, "⏻", 470f, 100f, true, "Android (not changeable)"),
            RemoteButton(KeyEvent.KEYCODE_SEARCH, "🎤", 530f, 100f, false, "Voice (Android)"),
        ) + pad + listOf(
            RemoteButton(KeyEvent.KEYCODE_BACK, "↩ Back", 455f, 310f, true, back),
            RemoteButton(KeyEvent.KEYCODE_VOLUME_UP, "VOL ±", 500f, 380f, true, "Volume (Android)"),
            RemoteButton(KeyEvent.KEYCODE_HOME, "⌂ Home", 500f, 310f, false, "Android (not changeable)"),
            RemoteButton(KeyEvent.KEYCODE_MENU, "☰ Menu", 545f, 310f, false),
        )
    }
}

/** Draws remote [style] (design units: x 420–580, y 70–610) — also used for the small pictures in Settings. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRemote(style: String, p: (Float, Float) -> Offset, k: Float) {
    val body = Color(0xFF2A2F3A); val edge = Color(0xFF4B5568); val key = Color(0xFF3A4152); val red = Color(0xFFC0392B)
    fun bodyRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        drawRoundRect(body, p(x, y), Size(w * k, h * k), CornerRadius(r * k))
        drawRoundRect(edge, p(x, y), Size(w * k, h * k), CornerRadius(r * k), style = Stroke(2f * k))
    }
    fun dot(x: Float, y: Float, r: Float = 13f, c: Color = key) = drawCircle(c, r * k, p(x, y))
    fun pad(cy: Float) { drawCircle(key, 52f * k, p(500f, cy)); drawCircle(ACCENT, 22f * k, p(500f, cy)) }
    when (style) {
        "stick" -> {
            bodyRect(445f, 70f, 110f, 400f, 40f)
            dot(500f, 95f, 12f, red); dot(500f, 135f, 12f); pad(225f)
            dot(458f, 310f, 11f); dot(500f, 310f, 11f); dot(542f, 310f, 11f)
            listOf(475f to 350f, 525f to 350f, 475f to 380f, 525f to 380f).forEach { (x, y) ->
                drawRoundRect(key, p(x - 20f, y - 9f), Size(40f * k, 18f * k), CornerRadius(9f * k))
            }
            drawRoundRect(key, p(487f, 410f), Size(26f * k, 55f * k), CornerRadius(13f * k))
        }
        "air" -> {
            bodyRect(425f, 70f, 150f, 340f, 45f)
            dot(455f, 100f, 13f, red); dot(545f, 100f); dot(455f, 140f); dot(500f, 140f); dot(545f, 140f)
            pad(235f)
            dot(455f, 325f); dot(500f, 325f); dot(545f, 325f)
            drawRoundRect(key, p(445f, 362f), Size(110f * k, 26f * k), CornerRadius(13f * k))
        }
        "round" -> {
            bodyRect(420f, 70f, 160f, 360f, 50f)
            dot(470f, 100f, 13f, red); dot(530f, 100f); pad(215f)
            dot(455f, 310f); dot(500f, 310f); dot(545f, 310f)
            drawRoundRect(key, p(487f, 345f), Size(26f * k, 70f * k), CornerRadius(13f * k))
        }
        else -> {
            bodyRect(420f, 70f, 160f, 540f, 50f)
            drawCircle(key, 52f * k, p(500f, 215f))
            drawCircle(ACCENT, 22f * k, p(500f, 215f))
            listOf(455f to 100f, 545f to 100f, 450f to 300f, 500f to 306f, 550f to 300f, 500f to 380f, 458f to 455f, 500f to 455f, 542f to 455f)
                .forEach { (x, y) -> drawCircle(if (x == 545f && y == 100f) red else key, 13f * k, p(x, y)) }
            drawRoundRect(key, p(440f, 345f), Size(30f * k, 75f * k), CornerRadius(15f * k))
            drawRoundRect(key, p(530f, 345f), Size(30f * k, 75f * k), CornerRadius(15f * k))
            listOf(Color(0xFFE74C3C), Color(0xFF2ECC71), Color(0xFFF1C40F), Color(0xFF3498DB)).forEachIndexed { i, c ->
                drawRoundRect(c, p(436f + i * 34f, 489f), Size(26f * k, 12f * k), CornerRadius(6f * k))
            }
            for (r in 0 until 4) for (c in 0 until 3) {
                if (r == 3 && c != 1) continue
                drawCircle(key, 11f * k, p(465f + c * 35f, 535f + r * 22f))
            }
        }
    }
}

/**
 * Settings › Remote control: "🎮 Simple remote ▾" (3 drawings — choose the nearest to yours) or "🎛 Full remote",
 * and the button layout: 👴 Easy · Nova standard · My own.
 */
@Composable
fun RemoteStylePicker(version: Int, onChange: () -> Unit) {
    val c = LocalContext.current
    val storage = (c.applicationContext as com.nova.iptv.NovaApp).storage
    var style by remember(version) { mutableStateOf(RemoteKeys.style(c)) }
    var layout by remember(version) { mutableStateOf(RemoteKeys.layoutName(c)) }
    var open by remember { mutableStateOf(false) }
    Text("Your remote", fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("🎮 Simple remote " + if (open) "▴" else "▾", style != "full", { open = !open })
        Chip("🎛 Full remote (colours, numbers)", style == "full", { style = "full"; RemoteKeys.setStyle(c, "full"); open = false; onChange() })
    }
    if (open || style != "full") Row(
        Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf("round" to "A · Round", "stick" to "B · Slim stick", "air" to "C · Air-mouse").forEach { (id, name) ->
            FocusBox(onClick = {
                style = id; RemoteKeys.setStyle(c, id); open = false
                android.util.Log.i("NovaTest", "REMOTE style $id")
                onChange()
            }, shape = RoundedCornerShape(12.dp)) { f ->
                Column(
                    Modifier.width(120.dp)
                        .background(if (style == id) Color(0xFF22335A) else Color(0xFF16264A), RoundedCornerShape(12.dp))
                        .border(if (style == id || f) 3.dp else 1.dp, if (style == id || f) ACCENT else Color(0xFF2F4B7C), RoundedCornerShape(12.dp))
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.width(100.dp).height(150.dp)) {
                        val k = size.height / 560f
                        val ox = size.width / 2f - 500f * k
                        drawRemote(id, { x, y -> Offset(ox + x * k, (y - 60f) * k) }, k)
                    }
                    Text((if (style == id) "✓ " else "") + name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
    Text("Button layout", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("👴 Easy", layout == "easy", {
            RemoteKeys.applyEasy(storage); RemoteKeys.setLayoutName(c, "easy"); layout = "easy"
            android.util.Log.i("NovaTest", "REMOTE layout easy")
            Toast.makeText(c, "👴 Easy: ▲▼ channels · OK list · ◀ last channel · ▶ info · ☰ menu", Toast.LENGTH_LONG).show(); onChange()
        })
        Chip("Nova standard", layout == "standard", { RemoteKeys.resetAll(storage); RemoteKeys.setLayoutName(c, "standard"); layout = "standard"; onChange() })
        if (layout == "own") Chip("My own (as changed)", true, { })
    }
}

private fun fullButtons(storage: Storage, tab: RemoteKeys.Area): List<RemoteButton> {
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
    val list = remember(tab, version) { buttons(context, storage, tab) }
    var learning by remember { mutableStateOf<RemoteButton?>(null) }
    val style = remember(version) { RemoteKeys.style(context) }

    fun labelFor(b: RemoteButton): Pair<String, String?> {
        b.fixed?.let { return it to null }
        if (b.learn != null && b.code < 0 && b.fixed == null) return "Select, then press it once to teach Nova" to null
        val ok = b.code == KeyEvent.KEYCODE_DPAD_CENTER || b.code == KeyEvent.KEYCODE_ENTER
        val a = RemoteKeys.actionInTab(storage, tab, b.code) ?: return when {
            ok && tab == RemoteKeys.Area.LIVE -> "Channel list" to "OK twice = info & buttons · hold = Rec"
            ok && tab == RemoteKeys.Area.VOD -> "Player buttons" to "OK twice = info & buttons · hold = Download"
            else -> "Not used" to null
        }
        val extra = if (a.id == "play_hide") "Fake “video signal error” + sound off · press again to show" else null
        return shortName(a.label) to extra
    }

    fun apply(b: RemoteButton, a: RemoteKeys.Action, note: String = "") {
        RemoteKeys.assign(storage, a.id, b.code)
        RemoteKeys.setLayoutName(context, "own")
        version++
        Toast.makeText(context, "${b.name} → ${shortName(a.label)}$note", if (note.isEmpty()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }

    fun choose(b: RemoteButton, a: RemoteKeys.Action) {
        val current = RemoteKeys.actionInTab(storage, tab, b.code)
        // The action shown on this tab is simply replaced; anything else using the button (other player…) is asked first.
        // Never blocked: only a note when the command is already on another button, or the button loses another command.
        val others = RemoteKeys.conflicts(storage, a.id, b.code).filter { it.id != current?.id }
        val alsoOn = RemoteKeys.keysFor(storage, a.id).filter { it != b.code }.mapNotNull { k -> list.firstOrNull { it.code == k }?.name }
        val note = buildString {
            if (alsoOn.isNotEmpty()) append("\nℹ Just so you know: also on ${alsoOn.joinToString(" · ")} — both do the same")
            if (others.isNotEmpty()) append("\nℹ ${b.name} no longer does: ${others.joinToString { shortName(it.label) }}")
        }
        android.util.Log.i("NovaTest", "REMOTE ${b.name} → ${a.id}${if (note.isNotEmpty()) " (note)" else ""}")
        apply(b, a, note)
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
                    val b = list.firstOrNull { it.code == code && (it.fixed == null || it.learn != null) } ?: return@onPreviewKeyEvent false
                    picking = b
                    true
                },
        ) {
            // A phone (small screen): a simple list that always fits, instead of the drawn remote.
            if (minOf(maxWidth.value / 1000f, maxHeight.value / 700f) < 0.7f) {
                CompactRemote(list, tab, { tab = it }, { labelFor(it) }, { b -> if (b.learn != null && b.code < 0) learning = b else if (b.fixed == null) picking = b }, onClose)
            } else {
            val s = minOf(maxWidth.value / 1000f, maxHeight.value / 700f)
            fun d(v: Float) = (v * s).dp
            val top = 60f
            // ---- the remote and the arrows
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / (maxWidth.value).coerceAtLeast(1f) * s // px per design unit
                fun p(x: Float, y: Float) = Offset(x * k, (y + top) * k)
                drawRemote(style, ::p, k)
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
                        FocusBox(onClick = { if (b.learn != null && b.code < 0) learning = b else if (b.fixed == null) picking = b }, shape = RoundedCornerShape(d(9f))) { focused ->
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
    }

    // ---- teach Nova a button (□ △ ○ ★, app keys): one press
    learning?.let { b ->
        Dialog(onDismissRequest = { learning = null }) {
            Box(
                Modifier.widthIn(max = 460.dp).background(Color(0xFF16264A), RoundedCornerShape(16.dp))
                    .border(2.dp, ACCENT, RoundedCornerShape(16.dp)).padding(20.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        val code = e.nativeKeyEvent.keyCode
                        if (code == KeyEvent.KEYCODE_BACK) { learning = null; return@onPreviewKeyEvent true }
                        val nav = code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
                        if (nav || RemoteKeys.isReserved(code)) {
                            Toast.makeText(context, "That is ${RemoteKeys.keyName(code)} — press ${b.name} please", Toast.LENGTH_SHORT).show()
                            return@onPreviewKeyEvent true
                        }
                        RemoteKeys.setLearnt(context, b.learn!!, code)
                        android.util.Log.i("NovaTest", "REMOTE learnt ${b.learn} = $code")
                        Toast.makeText(context, "✓ ${b.name} learnt (${RemoteKeys.keyName(code)}) — now choose what it does", Toast.LENGTH_LONG).show()
                        learning = null
                        version++
                        picking = b.copy(code = code, fixed = null)
                        true
                    },
            ) {
                val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                Column(Modifier.focusRequester(fr).focusable(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Teach Nova the ${b.name} button", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ACCENT)
                    Text("Press ${b.name} once on your remote.\nIf nothing happens, this box keeps that button for itself (Android).\nBack cancels.", color = Color.White)
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
                            val elsewhere = RemoteKeys.keysFor(storage, a.id).filter { it != b.code }
                                .mapNotNull { k -> list.firstOrNull { it.code == k }?.name }
                            Text(
                                shortName(a.label) + (if (a.id == current?.id) "  (now)" else "") +
                                    if (elsewhere.isNotEmpty()) "   ⚠ also on ${elsewhere.joinToString(" · ")}" else "",
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


/** "Your remote" on a phone: every button in a list (name · what it does), tap to change. */
@Composable
private fun CompactRemote(
    list: List<RemoteButton>, tab: RemoteKeys.Area, onTab: (RemoteKeys.Area) -> Unit,
    labelFor: (RemoteButton) -> Pair<String, String?>, onPick: (RemoteButton) -> Unit, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("🎮 Your remote", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ACCENT)
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RemoteKeys.Area.LIVE to "Live TV", RemoteKeys.Area.VOD to "Films & series", RemoteKeys.Area.LISTS to "Lists & menus")
                .forEach { (a, l) -> Chip(l, tab == a, { onTab(a) }) }
            Chip("✕ Close", false, onClose)
        }
        Text("Tap a button to choose what it does. Not every remote has every button.", fontSize = 12.sp, color = Color(0xFF9FB3E0))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(list.sortedBy { !it.left }) { b ->
                val (text, extra) = labelFor(b)
                FocusBox(onClick = { onPick(b) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f ->
                    Row(
                        Modifier.fillMaxWidth().background(if (f) Color(0xFF2F4B7C) else Color(0xFF16264A), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(b.name, fontWeight = FontWeight.Bold, color = ACCENT, modifier = Modifier.width(96.dp), maxLines = 2)
                        Column(Modifier.weight(1f)) {
                            Text(text, color = if (b.fixed != null) Color(0xFF9FB3E0) else Color.White)
                            extra?.let { Text(it, fontSize = 12.sp, color = Color(0xFF9FB3E0)) }
                        }
                    }
                }
            }
        }
    }
}
