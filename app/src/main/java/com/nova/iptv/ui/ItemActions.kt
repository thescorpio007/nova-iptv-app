package com.nova.iptv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.SavedItem
import kotlinx.coroutines.delay

/** "|FR| Little Brother (2026)" → "Little Brother 2026": no language tags, quality words or symbols — ready to search. */
fun searchName(raw: String): String {
    var s = raw
    s = s.replace(Regex("""^\s*(\|[^|]{1,12}\||\[[^\]]{1,12}]|[A-Z]{2,4}\s*[-:|]\s+)"""), " ")
    s = s.replace(Regex("""\|[^|]{1,12}\|"""), " ")
    s = s.replace(Regex("""\b(4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|MULTI|VOSTFR|VOST|VF|VFF|VO)\b""", RegexOption.IGNORE_CASE), " ")
    s = s.replace(Regex("""[()\[\]{}|_•★#*~]"""), " ")
    return s.replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '.').ifBlank { raw.trim() }
}

fun copyText(context: Context, text: String, toast: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Nova", text))
    Toast.makeText(context, toast, Toast.LENGTH_LONG).show()
}

/** Long-press on a film / series: copy its name (to search it on the web), search it in the Web tab, Favorites. Closes after 10 s. */
@Composable
fun ItemActionsHost(vm: MainViewModel) {
    val item by vm.itemActions.collectAsStateWithLifecycle()
    val s = item ?: return
    val context = LocalContext.current
    val clean = remember(s) { searchName(s.name) }
    val isFav = vm.isFavorite(s)
    var left by remember(s) { mutableIntStateOf(10) }
    fun close() { vm.itemActions.value = null }
    LaunchedEffect(s, left) { if (left > 0) { delay(1000); left-- } else close() }
    Dialog(onDismissRequest = { close() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Panel {
            Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GREEN, maxLines = 2)
            Text((if (s.type == ItemType.SERIES) "Series" else "Film") + " · closes in $left s", fontSize = 12.sp, color = Color(0xFF9FB3E0))
            WideButton("📋 Copy the name: $clean", primary = true) { copyText(context, clean, "✓ Copied: $clean"); close() }
            WideButton("🔍 Search it in the Web tab") { close(); vm.openWeb("https://duckduckgo.com/?q=" + android.net.Uri.encode(clean)) }
            WideButton(if (isFav) "♥ Remove from Favorites" else "♡ Add to Favorites") { vm.toggleFavorite(s); close() }
            WideButton("📋 Copy the exact name") { copyText(context, s.name, "✓ Copied: ${s.name}"); close() }
            WideButton("Cancel") { close() }
        }
    }
}

/** ▦ QR of a link: the phone camera takes it. */
@Composable
fun LinkQrDialog(url: String, onClose: () -> Unit) {
    val bmp = remember(url) { qrBitmap(url, 600) }
    Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Panel {
            Text("▦ Link", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(200.dp).background(Color.White, RoundedCornerShape(6.dp)).padding(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Scan with the phone camera to get the full link on the phone.", fontSize = 13.sp)
                    Text("⚠ This link contains your account user name and password — share it only with your own devices.",
                        fontSize = 12.sp, color = Color(0xFFFFD27A), modifier = Modifier.padding(top = 8.dp))
                }
            }
            WideButton("Close", primary = true, onClick = onClose)
        }
    }
}
