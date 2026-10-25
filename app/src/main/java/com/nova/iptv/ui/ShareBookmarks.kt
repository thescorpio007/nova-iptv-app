package com.nova.iptv.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.LocalShare
import com.nova.iptv.data.WebStore
import kotlinx.coroutines.launch

internal val GREEN = Color(0xFF5FF2D6)

/** Landscape / TV: content side by side; portrait: stacked (nothing squeezed into a narrow column). */
@Composable
internal fun wide(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return c.screenWidthDp > c.screenHeightDp && c.screenWidthDp >= 600
}

/** A full-width button with its label on one line (never broken inside a word). */
@Composable
internal fun WideButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { f ->
        Text(
            label,
            modifier = Modifier.fillMaxWidth()
                .background(
                    when { f -> Color.White; primary -> Color(0xFF39D98A); else -> Color(0xFF2A3A5A) },
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            color = if (f || primary) Color(0xFF062A14) else Color.White,
            fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
internal fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 14.dp).widthIn(max = if (wide()) 760.dp else 560.dp).fillMaxWidth()
            .background(Color(0xFF16264A), RoundedCornerShape(16.dp))
            .border(2.dp, GREEN, RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Web ⚙ › 📲 Send bookmarks: the Nova devices on this Wi-Fi; the chosen one is asked to add them. */
@Composable
fun SendBookmarksDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<LocalShare.Device>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    val count = WebStore.bookmarks.collectAsStateWithLifecycle().value.size
    LaunchedEffect(round) { devices = null; devices = LocalShare.discover(context) }
    Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Panel {
            Text("📲 Send bookmarks", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Text("Nova devices on your Wi-Fi (Nova must be open on them):", fontSize = 13.sp, color = Color(0xFF9FB3E0))
            val list = devices
            when {
                list == null -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Looking…", fontSize = 13.sp) }
                list.isEmpty() -> Text("No other Nova found. Open Nova on the TV box (same Wi-Fi), then search again.", fontSize = 14.sp)
                else -> LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list) { d ->
                        FocusBox(onClick = {
                            if (busy) return@FocusBox
                            busy = true
                            scope.launch {
                                val err = LocalShare.send(d.host, d.port)
                                busy = false
                                Toast.makeText(context, err ?: "Sent to ${d.name} — accept them on it", Toast.LENGTH_LONG).show()
                                if (err == null) onClose()
                            }
                        }, modifier = Modifier.fillMaxWidth()) { f ->
                            Text(
                                "📺  ${d.name}",
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (f) Color.White else Color(0xFF1F3159), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                color = if (f) Color(0xFF0D2236) else Color.White, fontWeight = if (f) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            Text("$count bookmarks will be offered · the other device decides (nothing is removed there)", fontSize = 12.sp, color = Color(0xFF9FB3E0))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { WideButton("⟳ Search again") { round++ } }
                Column(Modifier.weight(1f)) { WideButton("Close", onClick = onClose) }
            }
        }
    }
}

internal fun qrBitmap(text: String, size: Int = 512): Bitmap? = runCatching {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()

/** Web ⚙ › 📷 Receive bookmarks (QR): the phone's camera opens a page on this device (open while this is shown, 5 min). */
@Composable
fun ReceiveBookmarksQrDialog(onClose: () -> Unit) {
    val addr = remember { LocalShare.localAddress() }
    val url = "http://$addr:${LocalShare.port()}/"
    DisposableEffect(Unit) {
        LocalShare.pageOpenUntil = System.currentTimeMillis() + 5 * 60_000
        onDispose { LocalShare.pageOpenUntil = 0L }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(5 * 60_000L); onClose() }
    val bmp = remember(url) { qrBitmap(url) }
    Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Panel {
            Text("📷 Receive bookmarks", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
            if (addr == null) {
                Text("This device is not on a Wi-Fi / home network.", fontSize = 14.sp)
            } else {
                val qr = @Composable { bmp?.let { Image(it.asImageBitmap(), "QR code", Modifier.size(200.dp).background(Color.White).padding(8.dp)) } }
                val info = @Composable { center: Boolean ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
                        Text(url.removeSuffix("/"), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("1 · Phone on the same Wi-Fi\n2 · Scan with the camera\n3 · Tap “Send my Nova bookmarks” or paste addresses",
                            fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                        Text("You'll be asked here first · closes after 5 min", fontSize = 12.sp, color = Color(0xFF9FB3E0), modifier = Modifier.fillMaxWidth())
                    }
                }
                if (wide()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        qr()
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            info(false)
                            Column(Modifier.widthIn(max = 180.dp)) { WideButton("Close", onClick = onClose) }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { qr() }
                    info(true)
                }
            }
            if (addr == null || !wide()) WideButton("Close", onClick = onClose)
        }
    }
}

/** Bookmarks arriving from another device: "Add them?" (cancels by itself after 10 s). Shown over any Nova screen. */
@Composable
fun IncomingBookmarksHost() {
    val context = LocalContext.current
    val offer by LocalShare.incoming.collectAsStateWithLifecycle()
    val o = offer ?: return
    var showList by remember(o) { mutableStateOf(false) }
    var left by remember(o) { mutableIntStateOf(10) }
    val fresh = remember(o) { o.bookmarks.filter { WebStore.isNew(it.url) } }
    LaunchedEffect(o, showList) {
        if (showList) return@LaunchedEffect
        while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
        LocalShare.incoming.value = null
    }
    Dialog(onDismissRequest = { LocalShare.incoming.value = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Panel {
            Text("📲 Bookmarks from “${o.from}”", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Text("${o.bookmarks.size} bookmarks · ${fresh.size} new, ${o.bookmarks.size - fresh.size} already here (skipped)", fontSize = 15.sp)
            Text("They are added to yours (newest first) — nothing is removed.", fontSize = 13.sp, color = Color(0xFF9FB3E0))
            if (showList) {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(fresh) { b -> Text("• " + b.title.ifBlank { b.url }, fontSize = 13.sp, maxLines = 1) }
                }
            }
            WideButton("✓ Add them", primary = true) {
                val n = WebStore.mergeBookmarks(o.bookmarks)
                LocalShare.incoming.value = null
                Toast.makeText(context, "$n bookmarks added", Toast.LENGTH_LONG).show()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!showList) Column(Modifier.weight(1f)) { WideButton("Show the list") { showList = true } }
                Column(Modifier.weight(1f)) { WideButton("Cancel") { LocalShare.incoming.value = null } }
            }
            if (!showList) Text("Cancels by itself in $left s", fontSize = 12.sp, color = Color(0xFF9FB3E0))
        }
    }
}

/** Opened from the TV's QR page ("Send my Nova bookmarks"): sends this phone's bookmarks to that TV, then closes. */
class SendBookmarksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val to = intent?.data?.getQueryParameter("to").orEmpty()
        val host = to.substringBefore(':')
        val port = to.substringAfter(':', "").toIntOrNull() ?: LocalShare.PORT
        if (host.isBlank()) { finish(); return }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val err = LocalShare.send(host, port)
            Toast.makeText(applicationContext, err ?: "Sent ${WebStore.bookmarks.value.size} bookmarks — accept them on the TV", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

/**
 * Web ⚙ › ↥ Import: every saved copy (date, time, how many bookmarks), a preview, then
 * ＋ Add to my bookmarks (keeps yours) or ⇄ Replace my bookmarks; browser settings only when ticked.
 */
@Composable
fun RestoreBookmarksDialog(onOtherFolder: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var copies by remember { mutableStateOf<List<WebStore.Copy>?>(null) }
    var chosen by remember { mutableStateOf<WebStore.Copy?>(null) }
    var settingsToo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { copies = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WebStore.copies() } }
    val fmt = remember { java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale.ENGLISH) }
    Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Panel {
            val c = chosen
            if (c == null) {
                Text("↥ Restore bookmarks", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
                Text("Saved copies (newest first) — choose one:", fontSize = 13.sp, color = Color(0xFF9FB3E0))
                val list = copies
                when {
                    list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    list.isEmpty() -> Text("No saved copy found here. Choose another folder (e.g. Download/NovaIPTV).", fontSize = 14.sp)
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list) { cp ->
                            FocusBox(onClick = { chosen = cp }, modifier = Modifier.fillMaxWidth()) { f ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (f) Color.White else Color(0xFF1F3159), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val tag = when {
                                        cp.name == "browser-latest.novaweb" -> " · latest"
                                        cp.name.contains("before-emptying") -> " · before emptying"
                                        else -> ""
                                    }
                                    Text(if (cp.at > 0) fmt.format(java.util.Date(cp.at)) else cp.name, modifier = Modifier.weight(1f),
                                        color = if (f) Color(0xFF0D2236) else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("${cp.count} bookmarks$tag", fontSize = 13.sp,
                                        color = if (f) Color(0xFF0D2236) else if (cp.count == 0) Color(0xFFFF8A80) else Color(0xFF9FB3E0))
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { WideButton("📁 Other folder…") { onClose(); onOtherFolder() } }
                    Column(Modifier.weight(1f)) { WideButton("Cancel", onClick = onClose) }
                }
            } else {
                val inCopy = remember(c) { WebStore.bookmarksIn(c.text) }
                val missing = remember(c) { inCopy.count { WebStore.isNew(it.url) } }
                fun done(replace: Boolean) {
                    val n = WebStore.restoreCopy(c.text, replace, settingsToo)
                    Toast.makeText(context, "Bookmarks restored: you now have $n", Toast.LENGTH_LONG).show()
                    onClose()
                }
                val list = @Composable { maxH: Int ->
                    Column {
                        Text(if (c.at > 0) fmt.format(java.util.Date(c.at)) else c.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
                        Text("${inCopy.size} bookmarks · $missing not in your list now", fontSize = 13.sp, color = Color(0xFF9FB3E0))
                        LazyColumn(Modifier.heightIn(max = maxH.dp).padding(top = 4.dp)) {
                            items(inCopy) { b ->
                                Text("★ " + b.title.ifBlank { b.url }, fontSize = 13.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                val note = "Add keeps yours (recommended) · Replace = exactly this copy"
                if (wide()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1f)) { list(260) }
                        Column(Modifier.widthIn(min = 220.dp, max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WideButton("＋ Add to my bookmarks", primary = true) { done(false) }
                            WideButton("⇄ Replace my bookmarks") { done(true) }
                            WideButton((if (settingsToo) "☑" else "☐") + " Also browser settings") { settingsToo = !settingsToo }
                            WideButton("‹ Back") { chosen = null }
                            Text(note, fontSize = 12.sp, color = Color(0xFF9FB3E0))
                        }
                    }
                } else {
                    list(200)
                    WideButton("＋ Add to my bookmarks", primary = true) { done(false) }
                    WideButton("⇄ Replace my bookmarks") { done(true) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { WideButton((if (settingsToo) "☑" else "☐") + " Browser settings") { settingsToo = !settingsToo } }
                        Column(Modifier.weight(1f)) { WideButton("‹ Back") { chosen = null } }
                    }
                    Text(note, fontSize = 12.sp, color = Color(0xFF9FB3E0))
                }
            }
        }
    }
}
