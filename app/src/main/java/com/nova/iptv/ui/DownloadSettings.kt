package com.nova.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.iptv.NovaApp

/** Settings → Downloads: connections (1–16) and speed limit, for your IPTV servers and for other links. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadSettings(vm: MainViewModel) {
    val storage = (LocalContext.current.applicationContext as NovaApp).storage
    val accounts by vm.accounts.collectAsState()
    var connS by remember { mutableIntStateOf(storage.dlConnServers) }
    var connL by remember { mutableIntStateOf(storage.dlConnLinks) }
    var speedS by remember { mutableIntStateOf(storage.dlSpeedServers) }
    var speedL by remember { mutableIntStateOf(storage.dlSpeedLinks) }
    val conns = listOf(1, 2, 4, 8, 16)
    val speeds = listOf(0, 1, 2, 5, 10, 20)

    @Composable
    fun Block(title: String, conn: Int, onConn: (Int) -> Unit, speed: Int, onSpeed: (Int) -> Unit, note: String, warn: Boolean) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            Text("CONNECTIONS", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                conns.forEach { n -> Chip("$n", conn == n, { onConn(n) }) }
            }
            Text("SPEED LIMIT", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                speeds.forEach { mb -> Chip(if (mb == 0) "No limit" else "$mb MB/s", speed == mb, { onSpeed(mb) }) }
            }
            Text(note, fontSize = 12.sp, color = if (warn) Color(0xFFFFD27A) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    SettingsCard("Downloads", summary = "servers: $connS conn.  ·  links: $connL conn.") {
        val allowed = accounts.joinToString(", ") { a ->
            val n = storage.maxConnections(a.id)
            "${a.name} allows " + (if (n > 0) "$n" else "?")
        }
        Block(
            "📡 From your IPTV servers" + (if (accounts.isNotEmpty()) " (" + accounts.joinToString { it.name } + ")" else ""),
            connS, { connS = it; storage.dlConnServers = it }, speedS, { speedS = it; storage.dlSpeedServers = it },
            "⚠ $allowed. Nova never uses more than the server allows (one stays free for watching) — otherwise the provider may block the account.",
            warn = true,
        )
        Block(
            "🔗 From other links (Open link, Share)",
            connL, { connL = it; storage.dlConnLinks = it }, speedL, { speedL = it; storage.dlSpeedLinks = it },
            "More connections = faster on most websites. If a website refuses, Nova goes back to 1 connection by itself.",
            warn = false,
        )
    }
}
