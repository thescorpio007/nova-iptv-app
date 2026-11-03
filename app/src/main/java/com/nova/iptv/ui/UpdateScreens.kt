package com.nova.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main screen: the daily check, the small "build N is available" note (top right, 10 s) and the install progress. */
@Composable
fun UpdateHost() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rel by Updater.available.collectAsStateWithLifecycle()
    val shown by Updater.noteShown.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { delay(8000); runCatching { Updater.checkDaily(ctx) } }
    val r = rel
    if (r != null && !shown) {
        var left by remember(r.build) { mutableIntStateOf(10) }
        LaunchedEffect(r.build, left) { if (left > 0) { delay(1000); left-- } else Updater.noteShown.value = true }
        Box(Modifier.fillMaxSize().padding(top = 64.dp, end = 16.dp), contentAlignment = Alignment.TopEnd) {
            Column(
                Modifier.widthIn(max = 360.dp).background(Color(0xFF16264A), RoundedCornerShape(14.dp))
                    .border(2.dp, GREEN, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("⬆ Nova 4.0 · build %02d is available".format(r.build), fontWeight = FontWeight.Bold)
                Text("You have build %02d · closes in $left s".format(Updater.currentBuild(ctx)), fontSize = 12.sp, color = Color(0xFF9FB3E0))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { WideButton("⬇ Update now", primary = true) { Updater.noteShown.value = true; scope.launch { Updater.install(ctx, r) } } }
                    Column(Modifier.weight(1f)) { WideButton("Later") { Updater.noteShown.value = true } }
                }
            }
        }
    }
    UpdateProgressDialog()
}

/** "⬇ Updating to build N": download progress, or what went wrong. */
@Composable
fun UpdateProgressDialog() {
    val p by Updater.progress.collectAsStateWithLifecycle()
    val problem by Updater.problem.collectAsStateWithLifecycle()
    val rel by Updater.latest.collectAsStateWithLifecycle()
    if (p == null && problem == null) return
    Dialog(onDismissRequest = { if (p == null) Updater.problem.value = null }) {
        Panel {
            Text("⬇ Updating to build %02d".format(rel?.build ?: 0), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GREEN)
            val pr = p
            if (pr != null) {
                Text("Downloading ${rel?.apkName.orEmpty()} · ${(pr * 100).toInt()} %", fontSize = 13.sp, color = Color(0xFF9FB3E0))
                LinearProgressIndicator(progress = { pr }, modifier = Modifier.fillMaxWidth())
                Text("Then: SHA-256 checked · same signature as your Nova · Android asks “Update this app?” → Update.", fontSize = 12.sp, color = Color(0xFF9FB3E0))
            } else {
                Text(problem.orEmpty(), color = Color(0xFFFFD27A), fontSize = 14.sp)
                WideButton("Close") { Updater.problem.value = null }
            }
        }
    }
}

/** Settings › System › ⬆ Updates. */
@Composable
fun UpdatesCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by Updater.latest.collectAsStateWithLifecycle()
    val available by Updater.available.collectAsStateWithLifecycle()
    val last by Updater.lastCheck.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var daily by remember { mutableStateOf(Updater.daily(ctx)) }
    var notesOpen by remember { mutableStateOf(false) }
    val mine = Updater.currentBuild(ctx)
    val df = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.ENGLISH) }
    SettingsCard("⬆ Updates", summary = if (available != null) "build %02d available".format(available!!.build) else "build %02d".format(mine)) {
        Text("This device: Nova IPTV 4.0 · build %02d".format(mine), fontWeight = FontWeight.Medium)
        Text(
            (latest?.let { "Latest on GitHub: 4.0 · build %02d · published ${it.publishedAt.take(10)}".format(it.build) } ?: "Latest on GitHub: not checked yet") +
                (if (last > 0) " · checked " + df.format(java.util.Date(last)) else ""),
            fontSize = 13.sp, color = if (available != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (latest != null && available == null) Text("✓ Nova is up to date", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            available?.let { a -> Button(onClick = { scope.launch { Updater.install(ctx, a) } }) { Text("⬇ Install build %02d".format(a.build)) } }
            OutlinedButton(onClick = {
                if (checking) return@OutlinedButton
                checking = true; note = null
                scope.launch { note = Updater.check(ctx); checking = false }
            }) { Text(if (checking) "Checking…" else "⟳ Check for updates") }
            if (latest?.notes?.isNotBlank() == true) OutlinedButton(onClick = { notesOpen = !notesOpen }) { Text("What's new") }
        }
        note?.let { Text(it, fontSize = 12.sp, color = Color(0xFFFFD27A)) }
        if (notesOpen) latest?.notes?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Check every day by itself", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("On", daily, { daily = true; Updater.setDaily(ctx, true) })
            Chip("Off", !daily, { daily = false; Updater.setDaily(ctx, false) })
        }
        Text(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, text = "Every download is checked against the release's SHA-256 checksum and must be signed like your Nova — a damaged or changed file is refused. Your settings, lists and logins stay as they are. The first time, Android asks to allow Nova to “install unknown apps”.")
    }
}
