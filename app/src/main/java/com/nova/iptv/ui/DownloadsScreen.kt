package com.nova.iptv.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus

@Composable
fun DownloadsScreen(vm: MainViewModel, onPlay: PlayFn) {
    val allItems by vm.downloads.collectAsStateWithLifecycle()
    // 📼 The Live engine's hidden buffer is never listed.
    val items = allItems.filter { it.kind != "livebuffer" }
    val scheduled by com.nova.iptv.download.RecSchedule.all.collectAsStateWithLifecycle()
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { com.nova.iptv.download.RecSchedule.load(context) }

    val dlState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().scrollBar(dlState), state = dlState, contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Saved in: ${vm.downloadsFolder()}\n" +
                    "Each IPTV server downloads one film at a time (one connection of your account); other links download up to 4 at once. " +
                    "Recordings are made from the ● Rec button (or a long press on OK) while you watch a channel; they continue in the background.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (!com.nova.iptv.data.Cast.isTv(context)) item {
            androidx.compose.material3.OutlinedButton(onClick = { CastSendActivity.pickVideo(context) }) {
                Text("📺 Play a video from this phone on the TV…")
            }
        }
        if (items.isEmpty()) {
            item { EmptyText("No downloads or recordings yet.\nOpen a movie or a series episode and choose Download.") }
        }
        // ⏰ Scheduled recordings on top.
        if (scheduled.isNotEmpty()) item(key = "sched-title") {
            Text("⏰ SCHEDULED", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
        itemsIndexed(scheduled, key = { _, s -> "sched-" + s.id }) { _, sc ->
            ScheduledRow(sc,
                onEdit = { (context as? android.app.Activity)?.let { a -> com.nova.iptv.player.RecScheduleDialog.show(a, sc, emptyList()) } },
                onCancel = { com.nova.iptv.download.RecSchedule.cancel(context, sc.id) })
        }
        // ● Recordings first: resume one after coming back home, even while it still records.
        val recordings = items.filter { it.kind == "recording" }
        if (recordings.isNotEmpty()) item(key = "rec-title") {
            Text("● RECORDINGS", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                color = androidx.compose.ui.graphics.Color(0xFFFF8A8A), modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
        itemsIndexed(recordings, key = { _, d -> d.id }) { _, d ->
            val resumeAt = vm.resumePosition(com.nova.iptv.data.SavedItem("file", d.id, d.title, d.icon))
            RecordingRow(
                d, resumeAt,
                onPlay = { startAt ->
                    val item = com.nova.iptv.download.LiveRec.playItem(d, startAt)
                    if (item != null) onPlay(listOf(item), 0)
                    else Toast.makeText(context, "The file is no longer on the device.", Toast.LENGTH_LONG).show()
                },
                onStop = { com.nova.iptv.download.LiveRec.stop(context, "stopped by you (Downloads)") },
                onTv = if (com.nova.iptv.data.Cast.isTv(context) || d.status == DownloadStatus.RECORDING) null else ({
                    val item = vm.localPlayItem(d)
                    if (item != null) com.nova.iptv.data.Cast.open(context, item, resumeAt)
                }),
                onDelete = { vm.deleteDownload(d) },
            )
        }
        val split = items.filter { it.kind != "recording" }.partition { com.nova.iptv.download.DownloadCenter.isServer(it.url) }
        listOf(
            "🖥  YOUR IPTV SERVERS · 1 at a time per server (never blocks your watching)" to split.first,
            "🔗  OTHER LINKS · up to ${com.nova.iptv.download.DownloadCenter.LINK_SLOTS} at the same time" to split.second,
        ).forEach { (title, list) ->
        if (list.isNotEmpty()) item(key = title) {
            Text(title, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                color = androidx.compose.ui.graphics.Color(0xFF9FC3FF), modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
        itemsIndexed(list, key = { _, d -> d.id }) { _, d ->
            DownloadRow(
                d,
                onPlay = {
                    val item = vm.localPlayItem(d)
                    if (item != null) onPlay(listOf(item), 0)
                    else Toast.makeText(context, "The file is no longer on the device.", Toast.LENGTH_LONG).show()
                },
                onWatch = {
                    val item = vm.watchDownload(d)
                    if (item != null) onPlay(listOf(item), 0)
                },
                onTv = if (com.nova.iptv.data.Cast.isTv(context)) null else ({
                    val item = vm.localPlayItem(d)
                    if (item != null) com.nova.iptv.data.Cast.open(context, item, vm.resumePosition(item.saved))
                    else Toast.makeText(context, "The file is no longer on the device.", Toast.LENGTH_LONG).show()
                }),
                onPause = { vm.pauseDownload(d) },
                onResume = { vm.resumeDownload(d) },
                onDelete = { vm.deleteDownload(d) },
            )
        }
        }
    }
}

/** ⏰ A scheduled recording: when, repeat, then…; Edit / Cancel. */
@Composable
private fun ScheduledRow(s: com.nova.iptv.download.RecSchedule.Sched, onEdit: () -> Unit, onCancel: () -> Unit) {
    val R = com.nova.iptv.download.RecSchedule
    val day = androidx.compose.runtime.remember(s.start) {
        val c = java.util.Calendar.getInstance(); val today = c.get(java.util.Calendar.DAY_OF_YEAR); c.timeInMillis = s.start
        when (c.get(java.util.Calendar.DAY_OF_YEAR) - today) { 0 -> "Today"; 1 -> "Tomorrow"; else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(s.start)) }
    }
    val rep = when (s.repeat) { "daily" -> "Every day "; "weekdays" -> "Mon–Fri "; "weekly" -> "Every week from $day "; else -> "$day " }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(s.icon, s.title, Modifier.size(width = 56.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(s.title + (s.programme?.let { " · $it" } ?: ""), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("⏰ $rep${com.nova.iptv.download.LiveRec.hhmm(s.start)} → ${com.nova.iptv.download.LiveRec.hhmm(s.end)} · then: ${R.thenLabel(s.then)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit) { Text("✎ Edit") }
            TextButton(onClick = onCancel) { Text("✕ Cancel", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** ● A recording: Resume / From start, and ■ Stop while it records (Delete once saved). */
@Composable
private fun RecordingRow(
    d: DownloadItem,
    resumeAt: Long,
    onPlay: (Long) -> Unit,
    onStop: () -> Unit,
    onTv: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val L = com.nova.iptv.download.LiveRec
    val recording = d.status == DownloadStatus.RECORDING
    val dur = L.words(d.durationSec)
    val gaps = if (d.gaps > 0) " · ${d.gaps} gap${if (d.gaps == 1) "" else "s"} (${d.gapSec} s)" else ""
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(d.icon, d.title, Modifier.size(width = 56.dp, height = 80.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(d.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (recording) {
                    Row {
                        Text("● Recording", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFFFF8A8A))
                        Text(" · $dur · ${L.size(d.doneBytes)}" + (if (d.endsAt > 0) " · ends ${L.hhmm(d.endsAt)}" else "") + gaps,
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("Recording · Ready · " + (if (d.durationSec > 0) "$dur · " else "") + L.size(d.doneBytes) + gaps,
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (d.durationSec > 0 && resumeAt > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { (resumeAt / 1000f / d.durationSec).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            if (resumeAt > 0) TextButton(onClick = { onPlay(com.nova.iptv.player.PlayItem.START_RESUME) }) { Text("▶ Resume ${L.clock(resumeAt / 1000)}") }
            else TextButton(onClick = { onPlay(if (recording) com.nova.iptv.player.PlayItem.START_NEWEST else 0L) }) { Text(if (recording) "▶ Watch" else "▶ Play") }
            TextButton(onClick = { onPlay(0L) }) { Text("⏮ From start") }
            if (onTv != null) TextButton(onClick = onTv) { Text("📺 TV") }
            if (recording) TextButton(onClick = onStop) { Text("■ Stop", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun DownloadRow(
    d: DownloadItem,
    onPlay: () -> Unit,
    onWatch: () -> Unit,
    onTv: (() -> Unit)?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val mb = d.doneBytes / (1024 * 1024)
    // HLS links: the size is known only at the end, so the parts give the progress.
    val totalMb = when {
        d.totalBytes > 0 -> d.totalBytes / (1024 * 1024)
        d.parts > 0 && d.partsDone > 0 -> d.doneBytes * d.parts / d.partsDone / (1024 * 1024)
        else -> -1
    }
    val progress = when {
        d.totalBytes > 0 -> (d.doneBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f)
        d.parts > 0 -> (d.partsDone.toFloat() / d.parts).coerceIn(0f, 1f)
        else -> null
    }
    val kindLabel = when (d.kind) {
        "recording" -> "Recording"
        "episode" -> "Episode"
        else -> "Movie"
    }
    val statusLabel = when (d.status) {
        DownloadStatus.DONE -> "Ready · $mb MB"
        DownloadStatus.DOWNLOADING -> {
            val conn = when {
                d.connections > 1 -> " · ${d.connections} connections"
                d.connections == 1 -> " · 1 connection"
                else -> ""
            }
            val speed = if (d.speed > 0) " · %.1f MB/s".format(d.speed / 1048576.0) else ""
            val left = if (d.speed > 0 && totalMb > 0) {
                val sec = ((totalMb * 1048576L - d.doneBytes) / d.speed).coerceAtLeast(0)
                " · " + (if (sec >= 3600) "${sec / 3600} h ${sec % 3600 / 60} min" else if (sec >= 60) "${sec / 60} min" else "$sec s") + " left"
            } else ""
            val parts = if (d.parts > 0) " · ${d.partsDone} / ${d.parts} parts" else ""
            (if (totalMb > 0) "Downloading · $mb / $totalMb MB (${((progress ?: 0f) * 100).toInt()}%)" else "Downloading · $mb MB") + parts + conn + speed + left
        }
        DownloadStatus.QUEUED -> "Waiting · " + com.nova.iptv.download.DownloadCenter.waitingNote(d).removePrefix("waiting — ").removePrefix("waiting ")
        DownloadStatus.PAUSED -> "Paused · $mb MB"
        DownloadStatus.FAILED -> "Failed: ${d.error ?: "unknown error"}"
        DownloadStatus.RECORDING -> "● Recording · $mb MB"
        else -> d.status
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(d.icon, d.title, Modifier.size(width = 56.dp, height = 80.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(d.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$kindLabel · $statusLabel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.note?.let { Text(it, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFFFD27A), maxLines = 2) }
                val err = d.error
                if (err != null && d.status == DownloadStatus.DOWNLOADING) {
                    Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 1)
                }
                if (d.status == DownloadStatus.DOWNLOADING || d.status == DownloadStatus.PAUSED) {
                    Spacer(Modifier.height(6.dp))
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else if (d.status == DownloadStatus.DOWNLOADING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        // 🔗 The link of this film / episode: copy it, or ▦ QR for the phone.
        if (d.url.startsWith("http")) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val qrState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔗 " + d.url, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = androidx.compose.ui.graphics.Color(0xFFBFE9FF), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { copyText(ctx, d.url, "✓ Link copied — paste it in another app (1DM, VLC, a browser…)") }) { Text("📋 Copy link") }
                TextButton(onClick = { qrState.value = true }) { Text("▦ QR") }
            }
            if (qrState.value) LinkQrDialog(d.url) { qrState.value = false }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            if (d.kind != "recording" && d.status in listOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.PAUSED) && d.doneBytes > 0) {
                TextButton(onClick = onWatch) { Text("▶ Watch now") }
            }
            when (d.status) {
                DownloadStatus.DONE -> {
                    if (onTv != null) TextButton(onClick = onTv) { Text("📺 TV") }
                    TextButton(onClick = onPlay) { Text("▶ Play") }
                }
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> TextButton(onClick = onPause) { Text("Pause") }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> TextButton(onClick = onResume) { Text("Resume") }
                else -> Unit
            }
            if (d.status != DownloadStatus.RECORDING) {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
