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
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
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
                OutlinedTextField(
                    value = referer, onValueChange = { referer = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Referer — the website's address, e.g. https://site.com/") },
                    trailingIcon = { PasteButton { referer = it } },
                )
                OutlinedTextField(
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
                Text("RECENT LINKS", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                recent.forEach { entry ->
                    val time = entry.substringBefore('|').toLongOrNull() ?: 0L
                    val url = entry.substringAfter('|')
                    FocusBox(
                        onClick = { link = url },
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text("🎬  " + LinkActions.title(url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (android.net.Uri.parse(url).host ?: "") + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time)),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
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
    }
}
