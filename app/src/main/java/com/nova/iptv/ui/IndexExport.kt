package com.nova.iptv.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.SearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Settings › Accounts › Search index: export the channels / films / series index used by Search and the
 * filters (no logins) to Download/NovaIPTV/Index, and import it on another or a new install.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchIndexSection(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val tick by vm.indexExportTick.collectAsStateWithLifecycle()
    val idxStatus by SearchIndex.status.collectAsStateWithLifecycle()
    val st = vm.appStorage
    var auto by remember { mutableStateOf(st.autoExportIndex) }
    // Servers ticked for "Export selected" (all at first).
    var picked by remember(accounts) { mutableStateOf(accounts.map { it.id }.toSet()) }
    var chooser by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Triple<String, ByteArray, Boolean>?>(null) } // (name, bytes, from Index folder)
    var working by remember { mutableStateOf(false) }

    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    fun export(list: List<com.nova.iptv.data.Account>) {
        if (working) return
        working = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { vm.exportIndex(list) } }
            working = false
            r.onSuccess {
                if (it == null) toast("The index isn't built yet. Open Search once and wait for it to finish.")
                else toast("Index saved (${"%,d".format(it.second)} items): Download/NovaIPTV/Index/${it.first.name}")
            }.onFailure { toast("Could not save the index: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    // Other location: export to a file the user picks / import any .novaindex file.
    var saveList by remember { mutableStateOf<List<com.nova.iptv.data.Account>>(emptyList()) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { vm.exportIndexTo(it, saveList) } }
            }
            n.onSuccess { toast("Index saved (${"%,d".format(it)} items)") }.onFailure { toast("Could not save the index here") }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null) toast("Could not read this file")
            else ask = Triple(uri.lastPathSegment?.substringAfterLast('/') ?: "file", bytes, false)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("🗂 Search index", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Every channel, film and series used by Search and the filters. No usernames or passwords. " +
                "A new install of Nova finds it here and uses it straight away for the servers you add.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Text("📁 Download/NovaIPTV/Index", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)

        Text("Save automatically after each update", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("On", auto, { auto = true; st.autoExportIndex = true })
            Chip("Off", !auto, { auto = false; st.autoExportIndex = false })
        }
        val last = remember(tick) { st.indexExportedAt }
        Text(
            if (last > 0) "Last saved: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
            else "Not saved yet",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (accounts.size > 1) {
            Text("Servers for “Export selected”", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { acc ->
                    val n = remember(acc.id, tick, idxStatus[acc.id]) { SearchIndex.itemCount(acc) }
                    val on = acc.id in picked
                    Chip(
                        (if (on) "✓ " else "") + acc.name + if (n > 0) " · ${"%,d".format(n)}" else " · not indexed",
                        on, { picked = if (on) picked - acc.id else picked + acc.id },
                    )
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { export(accounts) }) { Text(if (working) "Saving…" else "⬇ Export all") }
            if (accounts.size > 1) OutlinedButton(onClick = {
                val sel = accounts.filter { it.id in picked }
                if (sel.isEmpty()) toast("Tick at least one server") else export(sel)
            }) { Text("Export selected (${picked.count { id -> accounts.any { it.id == id } }})") }
            OutlinedButton(onClick = { chooser = true }) { Text("⬆ Import index") }
            TextButton(onClick = {
                saveList = if (accounts.size > 1) accounts.filter { it.id in picked } else accounts
                saveAs.launch("index_" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date()) + "." + SearchIndex.EXT)
            }) { Text("Export to other location…") }
        }
    }

    if (chooser) {
        var infos by remember { mutableStateOf<List<SearchIndex.FileInfo>?>(null) }
        LaunchedEffect(Unit) {
            infos = withContext(Dispatchers.IO) {
                SearchIndex.exportFiles(context).mapNotNull { f -> runCatching { SearchIndex.describe(f) }.getOrNull() }
            }
        }
        AlertDialog(
            onDismissRequest = { chooser = false },
            title = { Text("Import search index") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Found in Download/NovaIPTV/Index", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val list = infos
                    when {
                        list == null -> Text("Looking…", fontSize = 13.sp)
                        list.isEmpty() -> Text("No .novaindex file here yet.", fontSize = 13.sp)
                        else -> LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(list) { i ->
                                FocusBox(onClick = {
                                    chooser = false
                                    scope.launch {
                                        val b = withContext(Dispatchers.IO) { runCatching { i.file.readBytes() }.getOrNull() }
                                        if (b == null) toast("Could not read ${i.file.name}") else ask = Triple(i.file.name, b, true)
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                        Text(
                                            (if (i.file.name == SearchIndex.LATEST) "✅ " else "🗂 ") + i.file.name.removeSuffix("." + SearchIndex.EXT),
                                            fontSize = 14.sp,
                                        )
                                        Text(
                                            DateFormat.getDateInstance().format(Date(i.exportedAt)) + " · " +
                                                i.servers.joinToString(", ") + " · " + "%,d".format(i.items) + " items",
                                            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "Items play once an account on the same server is added.",
                        fontSize = 12.sp, color = Color(0xFFFFD27A),
                    )
                    AllFilesNotice()
                }
            },
            confirmButton = { TextButton(onClick = { chooser = false; pick.launch(arrayOf("*/*")) }) { Text("Other location…") } },
            dismissButton = { TextButton(onClick = { chooser = false }) { Text("Cancel") } },
        )
    }

    ask?.let { (name, bytes, fromFolder) ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Import this index?") },
            text = { Text("$name\nThe index of each server in the file replaces the one for the same server here. Your accounts, lists and settings stay.") },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { vm.importIndex(bytes, fromFolder) } }
                        r.onSuccess {
                            toast(buildString {
                                if (it.linked.isNotEmpty()) append("Imported ${"%,d".format(it.items)} items for ${it.linked.joinToString(", ")}.")
                                if (it.waiting.isNotEmpty()) {
                                    if (isNotEmpty()) append(" ")
                                    append("Waiting for an account on: ${it.waiting.joinToString(", ")}.")
                                }
                                if (isEmpty()) append("This file holds no servers.")
                            })
                        }.onFailure { toast(it.message ?: "Could not import") }
                    }
                }) { Text("Yes, import") }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
        )
    }
}
