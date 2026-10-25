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

private val GREEN = Color(0xFF5FF2D6)

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth()
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
    Dialog(onDismissRequest = onClose) {
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
                SmallButton("⟳ Search again", { round++ })
                SmallButton("Close", onClose)
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap? = runCatching {
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
    Dialog(onDismissRequest = onClose) {
        Panel {
            Text("📷 Receive bookmarks", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
            if (addr == null) {
                Text("This device is not on a Wi-Fi / home network.", fontSize = 14.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    bmp?.let { Image(it.asImageBitmap(), "QR code", Modifier.size(200.dp).background(Color.White).padding(8.dp)) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Scan with your phone's camera (same Wi-Fi). It opens:", fontSize = 14.sp)
                        Text(url, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Then tap “Send my Nova bookmarks”, or paste web addresses. You'll be asked here before anything is added. The page closes after 5 min.",
                            fontSize = 12.sp, color = Color(0xFF9FB3E0))
                    }
                }
            }
            SmallButton("Close", onClose)
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
    Dialog(onDismissRequest = { LocalShare.incoming.value = null }) {
        Panel {
            Text("📲 Bookmarks from “${o.from}”", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Text("${o.bookmarks.size} bookmarks · ${fresh.size} new, ${o.bookmarks.size - fresh.size} already here (skipped)", fontSize = 15.sp)
            Text("They are added to yours (newest first) — nothing is removed.", fontSize = 13.sp, color = Color(0xFF9FB3E0))
            if (showList) {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(fresh) { b -> Text("• " + b.title.ifBlank { b.url }, fontSize = 13.sp, maxLines = 1) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("✓ Add them", {
                    val n = WebStore.mergeBookmarks(o.bookmarks)
                    LocalShare.incoming.value = null
                    Toast.makeText(context, "$n bookmarks added", Toast.LENGTH_LONG).show()
                })
                if (!showList) SmallButton("Show the list", { showList = true })
                SmallButton("Cancel", { LocalShare.incoming.value = null })
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
