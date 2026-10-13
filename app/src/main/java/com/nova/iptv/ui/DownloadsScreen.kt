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
    val items by vm.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val dlState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().scrollBar(dlState), state = dlState, contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Saved in: ${vm.downloadsFolder()}\n" +
                    "Downloads from your IPTV servers use one connection of your account and run one at a time; downloads from other links run at the same time. " +
                    "Recordings are made from the Rec button in the player while you watch a channel.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (items.isEmpty()) {
            item { EmptyText("No downloads or recordings yet.\nOpen a movie or a series episode and choose Download.") }
        }
        itemsIndexed(items) { _, d ->
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
                onPause = { vm.pauseDownload(d) },
                onResume = { vm.resumeDownload(d) },
                onDelete = { vm.deleteDownload(d) },
            )
        }
    }
}

@Composable
private fun DownloadRow(
    d: DownloadItem,
    onPlay: () -> Unit,
    onWatch: () -> Unit,
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
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            if (d.kind != "recording" && d.status in listOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.PAUSED) && d.doneBytes > 0) {
                TextButton(onClick = onWatch) { Text("▶ Watch now") }
            }
            when (d.status) {
                DownloadStatus.DONE -> TextButton(onClick = onPlay) { Text("▶ Play") }
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
