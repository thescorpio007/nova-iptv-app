package com.nova.iptv.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.LocalShare
import com.nova.iptv.data.NovaCrypto
import com.nova.iptv.data.WebStore
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Moving Nova to another device on the home Wi-Fi (e.g. phone → TV box): the box shows a QR code (welcome screen,
 * Settings › Backup); the phone's camera opens Nova there; you choose Everything / IPTV / Web / My lists; it travels
 * encrypted with a 4-digit code shown on the phone; the box asks for the code and confirms before restoring.
 */
object TransferPayload {
    val kinds = listOf(
        "all" to ("📦 Everything" to "IPTV + web"),
        "iptv" to ("📺 IPTV only" to "servers, lists, settings"),
        "web" to ("🌐 Web browser only" to "bookmarks, settings"),
        "lists" to ("📋 My lists only" to "order, favorites, hidden"),
    )

    fun label(kind: String) = kinds.firstOrNull { it.first == kind }?.second?.first ?: kind

    /** The same content as a backup file of that kind; plus a short summary (no secrets) for the other screen. */
    fun build(app: com.nova.iptv.NovaApp, kind: String): Pair<String, String> {
        val st = app.storage
        val web = runCatching { JSONObject(WebStore.toText()) }.getOrNull()
        val text = when (kind) {
            "web" -> JSONObject().put("app", "NovaIPTV").put("kind", "web").put("web", web).toString()
            "iptv" -> JSONObject(st.exportBackup()).put("kind", "iptv").toString()
            "lists" -> st.exportLists()
            else -> JSONObject(st.exportBackup()).put("kind", "all").put("web", web).toString()
        }
        val servers = st.accounts.size
        val bms = WebStore.bookmarks.value.size
        val summary = when (kind) {
            "web" -> "$bms bookmarks · browser settings"
            "iptv" -> "$servers servers · favorites, hidden items, settings"
            "lists" -> st.currentListsSummary().ifBlank { "your lists" }
            else -> "$servers servers · $bms bookmarks · favorites, hidden items, settings"
        }
        return text to summary
    }
}

/** Choose what to send, then send it (encrypted with a 4-digit code shown here). */
@Composable
fun TransferChooser(targetName: String, host: String, port: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as com.nova.iptv.NovaApp
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("all") }
    var lock by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var sentCode by remember { mutableStateOf<String?>(null) }
    val code = sentCode
    Panel {
        if (code != null) {
            Text("✓ Sent to “$targetName”", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
            if (code.isNotEmpty()) {
                Text("Type this code on it:", fontSize = 15.sp)
                Text(code.toList().joinToString("  "), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Text("It asks before restoring anything.", fontSize = 13.sp, color = Color(0xFF9FB3E0))
            WideButton("Close", primary = true, onClick = onDone)
            return@Panel
        }
        Text("📲 Send to “$targetName”", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
        TransferPayload.kinds.forEach { (k, l) ->
            FocusBox(onClick = { kind = k }, modifier = Modifier.fillMaxWidth()) { f ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (kind == k || f) Color.White else Color(0xFF1F3159), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(l.first, modifier = Modifier.weight(1f), fontWeight = if (kind == k) FontWeight.Bold else FontWeight.Normal,
                        color = if (kind == k || f) Color(0xFF0D2236) else Color.White, maxLines = 1)
                    Text(l.second, fontSize = 12.sp, color = if (kind == k || f) Color(0xFF0D2236) else Color(0xFF9FB3E0), maxLines = 1)
                }
            }
        }
        val summary = remember(kind) { runCatching { TransferPayload.build(app, kind).second }.getOrDefault("") }
        Text(summary + if (kind == "all" || kind == "iptv") " · includes your server passwords" else "", fontSize = 12.sp, color = Color(0xFF9FB3E0))
        WideButton((if (lock) "☑" else "☐") + " 🔒 Protect with a 4-digit code") { lock = !lock }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        WideButton("Send", primary = true) {
            if (busy) return@WideButton
            busy = true
            scope.launch {
                val (text, sum) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TransferPayload.build(app, kind) }
                val pin = if (lock) (1000 + java.security.SecureRandom().nextInt(9000)).toString() else ""
                val data = if (lock) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { NovaCrypto.encrypt(text, pin) } else text
                val err = LocalShare.sendTransfer(host, port, kind, data, sum, lock)
                busy = false
                if (err == null) sentCode = pin else Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            }
        }
        WideButton("Cancel", onClick = onDone)
    }
}

/** Settings › Backup › 📲 Send to another Nova: the Nova devices on this Wi-Fi, then what to send. */
@Composable
fun SendNovaDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<LocalShare.Device>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<LocalShare.Device?>(null) }
    LaunchedEffect(round) { devices = null; devices = LocalShare.discover(context) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val t = target
        if (t != null) { TransferChooser(t.name, t.host, t.port, onClose); return@Dialog }
        Panel {
            Text("📲 Send Nova to another device", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Text("Nova devices on your Wi-Fi (Nova must be open on them):", fontSize = 13.sp, color = Color(0xFF9FB3E0))
            val list = devices
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                list.isEmpty() -> Text("No other Nova found. Open Nova on the other device (same Wi-Fi), then search again.", fontSize = 14.sp)
                else -> LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list) { d -> WideButton("📺  ${d.name}") { target = d } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { WideButton("⟳ Search again") { round++ } }
                Column(Modifier.weight(1f)) { WideButton("Close", onClick = onClose) }
            }
        }
    }
}

/** The QR code that lets a phone send Nova here. [inline] = inside the welcome screen (stays active while shown). */
@Composable
fun ReceiveNovaQr(inline: Boolean, onClose: (() -> Unit)? = null) {
    val addr = remember { LocalShare.localAddress() }
    val url = "http://$addr:${LocalShare.port()}/"
    DisposableEffect(Unit) {
        LocalShare.pageMode = "transfer"
        LocalShare.pageOpenUntil = if (inline) Long.MAX_VALUE else System.currentTimeMillis() + 5 * 60_000
        onDispose { LocalShare.pageOpenUntil = 0L; LocalShare.pageMode = "bookmarks" }
    }
    val bmp = remember(url) { qrBitmap(url) }
    val qrSize = if (inline) 150.dp else 200.dp
    val qr = @Composable { bmp?.let { Image(it.asImageBitmap(), "QR code", Modifier.size(qrSize).background(Color.White, RoundedCornerShape(6.dp)).padding(6.dp)) } }
    val info = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (inline) "📲 Already have Nova on your phone?" else "📷 Receive Nova from another device",
                fontSize = if (inline) 18.sp else 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
            if (addr == null) {
                Text("Connect this device to Wi-Fi to receive Nova from your phone.", fontSize = 13.sp, color = Color.White)
            } else {
                Text("Scan with the phone's camera (same Wi-Fi), then choose Everything, IPTV only, Web only or My lists.",
                    fontSize = 13.sp, color = Color(0xFFCFD8EA))
                Text(url.removeSuffix("/"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                Text("You'll confirm here before anything is restored." + if (inline) "" else " Closes after 5 min.",
                    fontSize = 12.sp, color = Color(0xFF9FB3E0))
            }
        }
    }
    if (inline) {
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFF1D4F7A), Color(0xFF0B1D2A))), RoundedCornerShape(16.dp))
                .border(2.dp, GREEN, RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            if (wide() && addr != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { qr(); info() }
            else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { if (addr != null) qr(); info() }
        }
    } else {
        Dialog(onDismissRequest = { onClose?.invoke() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(5 * 60_000L); onClose?.invoke() }
            Panel {
                if (wide() && addr != null) Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    qr(); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { info(); Column(Modifier.widthIn(max = 180.dp)) { WideButton("Close") { onClose?.invoke() } } }
                } else {
                    if (addr != null) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { qr() }
                    info()
                    WideButton("Close") { onClose?.invoke() }
                }
            }
        }
    }
}

/** On this device: Nova sent from another one — code, bookmarks Add / Replace, then restore (cancels by itself after 30 s). */
@Composable
fun IncomingTransferHost(vm: MainViewModel) {
    val context = LocalContext.current
    val offer by LocalShare.incomingTransfer.collectAsStateWithLifecycle()
    val o = offer ?: return
    var code by remember(o) { mutableStateOf("") }
    var error by remember(o) { mutableStateOf<String?>(null) }
    var replaceBm by remember(o) { mutableStateOf(false) }
    var left by remember(o) { mutableIntStateOf(30) }
    LaunchedEffect(o, code) {
        left = 30
        while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
        LocalShare.incomingTransfer.value = null
    }
    Dialog(onDismissRequest = { LocalShare.incomingTransfer.value = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel {
            Text("📦 Nova from “${o.from}”", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Text(TransferPayload.label(o.kind) + " · " + o.summary, fontSize = 15.sp)
            if (o.locked) {
                Text("Code shown on the other device:", fontSize = 13.sp, color = Color(0xFF9FB3E0))
                TvTextField(
                    code, { code = it.filter(Char::isDigit).take(4); error = null }, singleLine = true,
                    modifier = Modifier.width(180.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    placeholder = { Text("4 digits") },
                )
            }
            if (o.kind == "all" || o.kind == "web") {
                Text("Bookmarks:", fontSize = 13.sp, color = Color(0xFF9FB3E0))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { WideButton((if (!replaceBm) "● " else "") + "＋ Add to mine", primary = !replaceBm) { replaceBm = false } }
                    Column(Modifier.weight(1f)) { WideButton((if (replaceBm) "● " else "") + "⇄ Replace mine", primary = replaceBm) { replaceBm = true } }
                }
            }
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
            WideButton("✓ Restore it here", primary = true) {
                val text = if (o.locked) runCatching { NovaCrypto.decrypt(o.text, code) }.getOrNull() else o.text
                if (text == null) { error = "Wrong code — look at the other device"; return@WideButton }
                val r = runCatching { if (o.kind == "lists") vm.restoreLists(text) else vm.restoreBackup(text, "all", replaceBm) }
                LocalShare.incomingTransfer.value = null
                Toast.makeText(context, r.fold({ "Restored: " + TransferPayload.label(o.kind) }, { it.message ?: "Could not restore" }), Toast.LENGTH_LONG).show()
            }
            WideButton("Cancel") { LocalShare.incomingTransfer.value = null }
            Text("Cancels by itself in $left s", fontSize = 12.sp, color = Color(0xFF9FB3E0))
        }
    }
}

/** Opened from the TV's QR page ("Send from Nova on this phone"): choose what to send to that TV. */
class TransferActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val to = intent?.data?.getQueryParameter("to").orEmpty()
        val host = to.substringBefore(':')
        val port = to.substringAfter(':', "").toIntOrNull() ?: LocalShare.PORT
        if (host.isBlank()) { finish(); return }
        setContent {
            NovaTheme {
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                    TransferChooser("the TV ($host)", host, port) { finish() }
                }
            }
        }
    }
}
