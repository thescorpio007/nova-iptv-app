package com.nova.iptv.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.ListedAccount
import com.nova.iptv.data.NovaCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val NovaGreen = Color(0xFF2EE66B)
private val NovaTeal = Color(0xFF13A88A)
private val NightBg = Brush.linearGradient(listOf(Color(0xFF071224), Color(0xFF0B1D3C), Color(0xFF0A1630)))

@Composable
private fun NovaMark(size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(Brush.linearGradient(listOf(NovaGreen, NovaTeal))),
        contentAlignment = Alignment.Center,
    ) {
        Text("▶", color = Color(0xFF06210F), fontSize = (size * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NovaName(size: Int) {
    Row {
        Text("Nova ", color = Color.White, fontSize = size.sp, fontWeight = FontWeight.ExtraBold)
        Text("IPTV", color = NovaGreen, fontSize = size.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Small "📋 Paste" button for text fields: puts the copied text (link, username, password…) into the field. */
@Composable
fun PasteButton(onPaste: (String) -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    FocusBox(
        onClick = {
            val t = clipboard.getText()?.text?.trim().orEmpty()
            if (t.isEmpty()) Toast.makeText(context, "Nothing copied yet", Toast.LENGTH_SHORT).show() else onPaste(t)
        },
        modifier = Modifier.padding(end = 6.dp).background(NovaGreen.copy(alpha = 0.13f), RoundedCornerShape(16.dp))
            .border(1.dp, NovaGreen.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text("📋 Paste", color = NovaGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

// =============================================================================== START-UP COVER

/** Shown for ~1.5 s at every start while Nova checks the accounts (and the accounts link). */
@Composable
fun StartCover() {
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF123A6E), Color(0xFF0A1630), Color(0xFF050B18)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NovaMark(110)
            Spacer(Modifier.height(22.dp))
            NovaName(44)
            Spacer(Modifier.height(22.dp))
            LinearProgressIndicator(Modifier.width(200.dp), color = NovaGreen, trackColor = Color(0x33FFFFFF))
            Spacer(Modifier.height(10.dp))
            Text("Loading your channels…", color = Color(0xFF9FB2D8), fontSize = 14.sp)
        }
        Text(
            "Designed by A.Hamrouni", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )
    }
}

// =============================================================================== WELCOME

/** First start (no account yet): Restore my backup · Connect with my accounts link · Add my IPTV account. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(vm: MainViewModel, isTv: Boolean) {
    var restore by remember { mutableStateOf(false) }
    var connect by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { android.util.Log.i("NovaTest", "WELCOME shown"); if (isTv) runCatching { first.requestFocus() } }
    BoxWithConstraints(Modifier.fillMaxSize().background(NightBg)) {
        val wide = maxWidth > maxHeight && maxWidth >= 640.dp
        val choices: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 460.dp)) {
                WelcomeChoice("⟲  Restore my backup", "From the box, a USB stick / SD card, or any web link", true, Modifier.focusRequester(first)) { restore = true }
                WelcomeChoice("🔗  Connect with my accounts link", "Your accounts list on OneDrive, Google Drive, Dropbox… — kept up to date every day", false) { connect = true }
                WelcomeChoice("＋  Add my IPTV account", "Xtream Codes details or an M3U link from your provider", false) { vm.welcomeForm.value = true }
            }
        }
        val intro: @Composable () -> Unit = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { NovaMark(if (wide) 80 else 64); Spacer(Modifier.width(16.dp)); NovaName(if (wide) 50 else 36) }
                Text(
                    "Your live TV, films and series — smooth on any box, built for the remote.",
                    color = Color(0xFFB8C7E6), fontSize = if (wide) 19.sp else 15.sp, modifier = Modifier.padding(top = 18.dp, bottom = 18.dp),
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("📺 Live TV", "🎬 Films & series", "💾 Disk buffer", "🛡 Watchdog").forEach {
                        Text(it, color = Color(0xFFDBE6FF), fontSize = 12.sp, maxLines = 1, modifier = Modifier.background(Color(0x1FFFFFFF), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
        }
        val footer: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth()) {
                Text("Nova IPTV · version " + appVersion(), color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("Designed by A.Hamrouni", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { intro() }
                Spacer(Modifier.width(40.dp))
                choices()
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(14.dp)) { footer() }
        } else {
            // Phone held upright: everything in one column, the footer after the buttons (never on top of them).
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
                Spacer(Modifier.height(12.dp)); intro(); Spacer(Modifier.height(18.dp)); choices()
                Spacer(Modifier.height(28.dp))
                footer()
            }
        }
    }
    if (restore) RestoreDialog(vm) { restore = false }
    if (connect) ConnectLinkDialog(vm) { connect = false }
}

@Composable
private fun WelcomeChoice(label: String, hint: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(16.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp)).padding(12.dp),
    ) {
        FocusBox(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().background(if (primary) NovaGreen else Color(0x22FFFFFF), RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
        ) { focused ->
            Text(
                label, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                color = if (primary) Color(0xFF06210F) else Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            )
        }
        Text(hint, color = Color(0xFF9FB2D8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
    }
}

// =============================================================================== RESTORE (file or any link)

/** Restore my backup: from the box / USB / SD, or from any web link; protected backups ask for their password. */
@Composable
fun RestoreDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("file") }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf<String?>(null) } // downloaded / read, maybe protected
    var fromLink by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var plain by remember { mutableStateOf<String?>(null) }
    val files = remember { vm.backupFiles() }

    fun accept(text: String, label: String, link: String?) {
        name = label; fromLink = link
        if (NovaCrypto.isProtected(text)) raw = text
        else if (vm.isBackup(text)) plain = text
        else error = "This file is not a Nova IPTV backup."
    }

    val openFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text == null) error = "Could not read that file" else accept(text, uri.lastPathSegment?.substringAfterLast('/') ?: "backup", null)
        }
    }

    if (plain == null && raw == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("⟲ Restore my backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Everything in Nova is replaced by the backup (after a Yes / No question).", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("📁 From the box / USB / SD", tab == "file", { tab = "file"; error = null })
                        Chip("🔗 From a link", tab == "link", { tab = "link"; error = null })
                    }
                    if (tab == "file") {
                        if (files.isEmpty()) Text("No backups found in ${vm.downloadsFolder()}/Backups. Use “Choose a file…”.", fontSize = 13.sp)
                        files.take(8).forEach { f ->
                            FocusBox(onClick = { runCatching { f.readText() }.getOrNull()?.let { accept(it, f.name, null) } ?: run { error = "Could not read ${f.name}" } }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(f.name, fontWeight = FontWeight.Medium)
                                    Text(DateFormat.getDateTimeInstance().format(Date(f.lastModified())), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        OutlinedButton(onClick = { openFrom.launch(arrayOf("*/*")) }) { Text("Choose a file…") }
                    } else {
                        TvTextField(
                            link, { link = it; error = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Link to your .bak file") },
                            trailingIcon = { PasteButton { link = it; error = null } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Text("OneDrive · Google Drive · Dropbox · your own site / NAS · any http(s) link", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        vm.lastBackupLink?.let { last ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Last link used: ${last.take(42)}${if (last.length > 42) "…" else ""} · ${DateFormat.getDateInstance().format(Date(vm.lastBackupLinkAt))}",
                                    fontSize = 12.sp, modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { link = last }) { Text("Use it again") }
                            }
                        }
                        Text("🔒 Your backup holds your account passwords — share its link only with yourself, or protect it with a password.", fontSize = 12.sp, color = Color(0xFFFFD27A))
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                if (tab == "link") TextButton(enabled = !busy && link.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        val r = runCatching { withContext(Dispatchers.IO) { vm.downloadBackup(link) } }
                        busy = false
                        r.onSuccess { accept(it, link.substringAfterLast('/').take(40), link) }
                            .onFailure { error = it.message ?: "Could not download the backup" }
                    }
                }) { Text("Download & check") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    raw?.let { text ->
        PasswordPrompt(
            title = "This backup is password-protected",
            onDismiss = { raw = null },
        ) { pw ->
            val r = runCatching { NovaCrypto.decrypt(text, pw) }
            r.fold(
                { dec -> if (vm.isBackup(dec)) { raw = null; plain = dec; null } else { raw = null; error = "This file is not a Nova IPTV backup."; null } },
                { it.message ?: "Wrong password" },
            )
        }
    }

    plain?.let { text ->
        val noFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { plain = null },
            title = { Text("Restore $name?") },
            text = {
                Text("All current accounts, favourites, hidden items and settings will be replaced by this backup.")
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { noFocus.requestFocus() } }
            },
            dismissButton = { TextButton(onClick = { plain = null }, modifier = Modifier.focusRequester(noFocus)) { Text("No") } },
            confirmButton = {
                TextButton(onClick = {
                    plain = null
                    val result = runCatching { vm.restoreBackup(text) }
                    if (result.isSuccess) fromLink?.let { vm.rememberBackupLink(it) }
                    Toast.makeText(context, result.fold({ "Backup restored" }, { it.message ?: "Could not restore" }), Toast.LENGTH_LONG).show()
                    if (result.isSuccess) onDismiss()
                }) { Text("Yes, restore") }
            },
        )
    }
}

/** Asks a password; [onPassword] returns null when accepted, or the error to show. */
@Composable
fun PasswordPrompt(title: String, onDismiss: () -> Unit, onPassword: (String) -> String?) {
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TvTextField(
                    pw, { pw = it; err = null }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { err = onPassword(pw) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =============================================================================== SAVE BACKUP (optional password)

@Composable
fun SaveBackupDialog(vm: MainViewModel, onSaveTo: (password: String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    fun check(): Boolean {
        err = if (pw != again) "The two passwords are different" else null
        return err == null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💾 Save a backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Accounts, favourites, order, hidden items and all settings.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TvTextField(pw, { pw = it; err = null }, singleLine = true, label = { Text("Password (optional)") }, visualTransformation = PasswordVisualTransformation())
                TvTextField(again, { again = it; err = null }, singleLine = true, label = { Text("Repeat password") }, visualTransformation = PasswordVisualTransformation())
                Text(
                    if (pw.isEmpty()) "Leave it empty for a normal backup." else "🔒 Keep this password safe — without it the backup cannot be restored.",
                    fontSize = 12.sp, color = if (pw.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFFFD27A),
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { if (check()) { onDismiss(); onSaveTo(pw.ifEmpty { null }) } }) { Text("Save to…") }
                TextButton(onClick = {
                    if (check()) {
                        val result = runCatching { vm.saveBackup(pw.ifEmpty { null }) }
                        Toast.makeText(context, result.fold({ "Backup saved: ${it.absolutePath}" }, { "Could not save: ${it.message}" }), Toast.LENGTH_LONG).show()
                        onDismiss()
                    }
                }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =============================================================================== ACCOUNTS LINK

/** Connect with my accounts link: read the list, show what was found (each tested), then connect. */
@Composable
fun ConnectLinkDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(vm.accountsLink.orEmpty()) }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<Pair<ListedAccount, String?>>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔗 Connect with my accounts link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Column {
                    Text("One account per line:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Name | server | username | password", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("(or a full Xtream / M3U link)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TvTextField(
                    link, { link = it; error = null; found = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Link to your accounts list (.txt)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = { PasteButton { link = it; error = null; found = null } },
                )
                TvTextField(
                    pw, { pw = it; error = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password of the list (only if protected)") }, visualTransformation = PasswordVisualTransformation(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                found?.let { list ->
                    Text("Found in your list", fontWeight = FontWeight.Bold)
                    list.forEach { (a, problem) ->
                        Row(Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontWeight = FontWeight.Bold)
                                Text(a.server, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (problem == null) "✓ works" else "⚠ $problem", color = if (problem == null) NovaGreen else Color(0xFFFFD27A), fontSize = 12.sp)
                        }
                    }
                    Text("⟳ Checked again at every start and once a day. Changes in the file update Nova by itself; a line removed from the file removes that account.", fontSize = 12.sp)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            val list = found
            if (list == null) {
                TextButton(enabled = !busy && link.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        val r = runCatching { vm.fetchAccountsList(link, pw.ifEmpty { null }) }
                        r.onSuccess { accs ->
                            found = accs.map { a -> a to runCatching { vm.testListed(a) }.getOrNull() }
                            android.util.Log.i("NovaTest", "ACCOUNTS LINK found ${accs.size}: ${accs.joinToString { it.name }}")
                        }.onFailure { error = it.message ?: "Could not read the list" }
                        busy = false
                    }
                }) { Text("Check") }
            } else {
                TextButton(onClick = {
                    vm.connectAccountsLink(link, pw.ifEmpty { null }, list.map { it.first })
                    onDismiss()
                }) { Text("Connect ${list.size} account${if (list.size == 1) "" else "s"}") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =============================================================================== ACCOUNTS CHECK

/** All accounts failed at start-up: say why, and let you adjust before entering. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AccountsCheckScreen(vm: MainViewModel, state: MainViewModel.Startup.AllFailed) {
    var editing by remember { mutableStateOf<com.nova.iptv.data.Account?>(null) }
    var restore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) { android.util.Log.i("NovaTest", "ACCOUNTS CHECK shown: ${state.problems.joinToString { it.first.name + " = " + it.second }}") }
    Column(
        Modifier.fillMaxSize().background(NightBg).verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { NovaMark(48); Spacer(Modifier.width(12.dp)); NovaName(30) }
        Text("Your accounts can't be used right now", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Nova stopped here so you can adjust them.", color = Color(0xFF9FB2D8))
        state.problems.forEach { (acc, problem) ->
            Row(
                Modifier.fillMaxWidth().widthIn(max = 900.dp).background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(acc.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(acc.server, color = Color(0xFF9FB2D8), fontSize = 12.sp)
                    Text(problem, color = Color(0xFFFFD27A), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
                OutlinedButton(onClick = { editing = acc }) { Text("Edit account") }
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.runStartup() }) { Text("⟳ Retry") }
            if (vm.accountsLink != null) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val note = vm.syncAccountsLink()
                        Toast.makeText(context, note, Toast.LENGTH_LONG).show()
                        vm.runStartup()
                    }
                }) { Text("Check my accounts link now") }
            }
            OutlinedButton(onClick = { restore = true }) { Text("Restore backup") }
            OutlinedButton(onClick = { vm.continueAnyway() }) { Text("Continue anyway") }
        }
        Text("Designed by A.Hamrouni", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
    }
    editing?.let { acc -> EditAccountDialog(vm, acc) { editing = null; vm.runStartup() } }
    if (restore) RestoreDialog(vm) { restore = false }
}

/** On the main screen: accounts that failed at start-up and accounts about to expire. */
@Composable
fun StartupBanner(vm: MainViewModel) {
    val problems by vm.accountProblems.collectAsStateWithLifecycle()
    val notes by vm.expiryNotes.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed || (problems.isEmpty() && notes.isEmpty())) return
    val lines = problems.mapNotNull { (id, p) -> accounts.firstOrNull { it.id == id }?.let { "${it.name}: $p" } } + notes.values
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF3A2A0A)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("⚠ " + lines.joinToString("   ·   "), color = Color(0xFFFFD27A), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
        if (problems.isNotEmpty()) SmallButton("Adjust", { SettingsFold.open = "Accounts"; vm.setTab(Tab.SETTINGS) })
        SmallButton("✕", { dismissed = true })
    }
}

// =============================================================================== SETTINGS → ACCOUNTS LINK

/** In Settings → Accounts: your accounts link (Check now · Change link · Disconnect) and "Save my accounts as a list file". */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AccountsLinkSection(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connect by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    var saveList by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) } // re-reads link / note after a check
    val link = remember(tick) { vm.accountsLink }
    val note = remember(tick) { vm.accountsLinkNote }
    val checkedAt = remember(tick) { vm.accountsLinkCheckedAt }
    LaunchedEffect(connect) { if (!connect) tick++ }

    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("🔗 Accounts link", fontWeight = FontWeight.Bold)
        if (link == null) {
            Text(
                "Keep your accounts in a text file on OneDrive, Google Drive, Dropbox or your own site: Nova reads it at every start and once a day, and updates itself.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { connect = true }, modifier = Modifier.fillMaxWidth()) { Text("🔗 Connect with my accounts link") }
            OutlinedButton(onClick = { saveList = true }, modifier = Modifier.fillMaxWidth()) { Text("💾 Save my accounts as a list file") }
        } else {
            Text(link, fontSize = 12.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                (if (checkedAt > 0) "Last checked: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(checkedAt))}" else "Not checked yet") +
                    (note?.let { "  ·  $it" } ?: ""),
                fontSize = 12.sp,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val n = vm.syncAccountsLink()
                        busy = false; tick++
                        Toast.makeText(context, n, Toast.LENGTH_LONG).show()
                    }
                }) { Text("⟳ Check now") }
                OutlinedButton(onClick = { connect = true }) { Text("Change link") }
                OutlinedButton(onClick = { disconnect = true }) { Text("Disconnect") }
            }
            OutlinedButton(onClick = { saveList = true }, modifier = Modifier.fillMaxWidth()) { Text("💾 Save my accounts as a list file") }
        }
    }
    if (connect) ConnectLinkDialog(vm) { connect = false }
    if (disconnect) {
        val no = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { disconnect = false },
            title = { Text("Disconnect the accounts link?") },
            text = {
                Text("Your accounts stay in Nova, but they will no longer be updated from the link.")
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { no.requestFocus() } }
            },
            dismissButton = { TextButton(onClick = { disconnect = false }, modifier = Modifier.focusRequester(no)) { Text("No") } },
            confirmButton = { TextButton(onClick = { vm.disconnectAccountsLink(); disconnect = false; tick++ }) { Text("Yes, disconnect") } },
        )
    }
    if (saveList) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveList = false },
            title = { Text("Save my accounts as a list file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A .txt file in the Backups folder, ready to upload to OneDrive / Drive / Dropbox and use as your accounts link.", fontSize = 13.sp)
                    TvTextField(pw, { pw = it }, singleLine = true, label = { Text("Password (optional)") }, visualTransformation = PasswordVisualTransformation())
                    Text("🔒 The file holds your account passwords — share its link only with yourself, or protect it with a password.", fontSize = 12.sp, color = Color(0xFFFFD27A))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = runCatching { vm.saveAccountsListFile(pw.ifEmpty { null }) }
                    Toast.makeText(context, r.fold({ "Saved: ${it.absolutePath}" }, { "Could not save: ${it.message}" }), Toast.LENGTH_LONG).show()
                    saveList = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveList = false }) { Text("Cancel") } },
        )
    }
}
