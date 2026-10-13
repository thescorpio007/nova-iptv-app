package com.nova.iptv.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.iptv.NovaApp
import java.text.DateFormat
import java.util.Date

/** "🔗 Open link": any video link — Play, Download & watch, or Download. Recent links below. */
@Composable
fun LinkScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val storage = (context.applicationContext as NovaApp).storage
    var link by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(storage.recentLinks) }
    /** The link waiting for "Delete?" (an empty text = all of them). */
    var ask by remember { mutableStateOf<String?>(null) }
    val clean = LinkActions.findLink(link) ?: link.trim().takeIf { it.startsWith("/") || it.startsWith("content:") || it.startsWith("file:") }
    val host = clean?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
    var advanced by remember { mutableStateOf(false) }
    var referer by remember(host) { mutableStateOf(host?.let { storage.linkHeaders(it)["Referer"] }.orEmpty()) }
    var agent by remember(host) { mutableStateOf(host?.let { storage.linkHeaders(it)["User-Agent"] }.orEmpty()) }
    fun act(what: (android.content.Context, String) -> Unit) {
        val url = clean
        if (url == null) {
            Toast.makeText(context, "Paste or type a video link (http:// or https://)", Toast.LENGTH_LONG).show()
            return
        }
        // Advanced: the website's details, remembered for this site.
        if (host != null && (referer.isNotBlank() || agent.isNotBlank())) {
            storage.setLinkHeaders(host, storage.linkHeaders(host) + mapOf("Referer" to referer.trim(), "User-Agent" to agent.trim()).filterValues { it.isNotBlank() })
        }
        what(context, url)
        recent = storage.recentLinks
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val linkScroll = rememberScrollState()
        Column(
            Modifier.fillMaxSize().scrollBar(linkScroll).verticalScroll(linkScroll).padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvTextField(
                value = link, onValueChange = { link = it }, singleLine = true,
                label = { Text(if (wide) "Video link (mp4, mkv, m3u8, ts, m3u…)" else "Video link") },
                trailingIcon = { PasteButton { link = it } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "▾ Advanced (only if the website refuses)" else "▸ Advanced (only if the website refuses)", fontSize = 13.sp)
            }
            if (advanced) {
                TvTextField(
                    value = referer, onValueChange = { referer = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Referer — the website's address, e.g. https://site.com/") },
                    trailingIcon = { PasteButton { referer = it } },
                )
                TvTextField(
                    value = agent, onValueChange = { agent = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("User-Agent (browser name) — empty = automatic") },
                    trailingIcon = {
                        androidx.compose.material3.TextButton(onClick = { agent = com.nova.iptv.data.LinkHeadersInterceptor.BROWSER_UA }) { Text("Browser") }
                    },
                )
                Text(
                    "Nova already retries as a web browser by itself when a website refuses. Fill these only if it still fails " +
                        "(copy them from 1DM → Advance option). They are remembered for " + (host ?: "this website") + ".",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val play: @Composable (Modifier) -> Unit = { m -> Button(onClick = { act(LinkActions::play) }, modifier = m) { Text("▶ Play", fontWeight = FontWeight.Bold) } }
            val watch: @Composable (Modifier) -> Unit = { m -> OutlinedButton(onClick = { act(LinkActions::downloadAndWatch) }, modifier = m) { Text("⬇▶ Download & watch") } }
            val dl: @Composable (Modifier) -> Unit = { m ->
                OutlinedButton(onClick = {
                    act { c, u -> LinkActions.download(c, u); Toast.makeText(c, "Download added — see the Downloads tab", Toast.LENGTH_SHORT).show() }
                }, modifier = m) { Text("⬇ Download") }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { play(Modifier); watch(Modifier); dl(Modifier) }
            } else {
                play(Modifier.fillMaxWidth()); watch(Modifier.fillMaxWidth()); dl(Modifier.fillMaxWidth())
            }
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("RECENT LINKS", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { ask = "" }) { Text("🗑 Delete all", color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 13.sp) }
                }
                recent.forEach { entry ->
                    val time = entry.substringBefore('|').toLongOrNull() ?: 0L
                    val url = entry.substringAfter('|')
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        FocusBox(onClick = { link = url }, modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                Text("🎬  " + LinkActions.title(url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "🕒 Added " + java.text.SimpleDateFormat("dd/MM/yyyy · HH:mm", java.util.Locale.getDefault()).format(Date(time)),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                                )
                                Text(android.net.Uri.parse(url).host ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        FocusBox(onClick = { ask = entry }, shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(end = 8.dp)) {
                            Text("🗑 Delete", color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            Text(
                "Links opened here are not saved as accounts. Some websites only allow their own player (the link expires " +
                    "or needs the website): then Nova says it can't open it. You can also share a link to Nova from 1DM, " +
                    "your browser or a file manager (Share → Nova IPTV).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ask?.let { e ->
            val all = e.isEmpty()
            val time = e.substringBefore('|').toLongOrNull() ?: 0L
            ConfirmOverlay(
                title = if (all) "Delete all recent links?" else "Delete this link?",
                text = (if (all) "${recent.size} links" else LinkActions.title(e.substringAfter('|')) + " · added " +
                    java.text.SimpleDateFormat("dd/MM/yyyy · HH:mm", java.util.Locale.getDefault()).format(Date(time))) +
                    "\nThey are only removed from this list (downloads are kept).",
                yesLabel = if (all) "Yes, delete all" else "Yes, delete",
                onYes = {
                    storage.recentLinks = if (all) emptyList() else storage.recentLinks.filter { it != e }
                    recent = storage.recentLinks
                    android.util.Log.i("NovaTest", "LINK deleted ${if (all) "all" else LinkActions.title(e.substringAfter('|'))}")
                },
                onDismiss = { ask = null },
            )
        }
    }
}
