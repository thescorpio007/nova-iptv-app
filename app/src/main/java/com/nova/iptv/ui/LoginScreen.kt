package com.nova.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.XtreamApi

/**
 * Sign-in page. "Restore backup" comes first (green box at the top on a phone, the left column on a
 * TV / wide screen), the Xtream sign-in form after it. On a TV everything fits on one screen.
 */
@Composable
fun LoginScreen(vm: MainViewModel, isTv: Boolean = false) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val hasAccounts = accounts.isNotEmpty()
    BackHandler(enabled = hasAccounts) { vm.openLogin(false) }
    // First start: Back returns to the welcome screen.
    BackHandler(enabled = !hasAccounts) { vm.welcomeForm.value = false }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        val wide = maxWidth > maxHeight && maxWidth >= 640.dp
        if (wide) {
            Row(
                Modifier.fillMaxSize().widthIn(max = 1100.dp).padding(horizontal = 40.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Title(big = true)
                    Text(
                        "Live TV, films and series from your Xtream provider.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    RestoreCard(vm, hasAccounts, focusFirst = isTv)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SignInForm(vm, hasAccounts, accountsText = accounts.joinToString { it.name }, heading = "Or sign in with your Xtream details")
                }
            }
        } else {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Title(big = false)
                RestoreCard(vm, hasAccounts, focusFirst = isTv)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Text(
                        "  or sign in with your Xtream details  ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
                SignInForm(vm, hasAccounts, accountsText = accounts.joinToString { it.name }, heading = null)
            }
        }
    }
}

@Composable
private fun Title(big: Boolean) {
    Text("Nova IPTV", fontSize = if (big) 34.sp else 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RestoreCard(vm: MainViewModel, hasAccounts: Boolean, focusFirst: Boolean) {
    val focus = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (hasAccounts) "Replace everything with a backup?" else "Already have a Nova backup?", fontWeight = FontWeight.Bold)
        Text(
            "Restore all servers and settings in one step — from the box, a USB stick / SD card, or any web link.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        RestoreBackupButton(vm, "⟲ Restore backup", Modifier.focusRequester(focus), filled = true)
    }
    // On a TV the remote starts on "Restore backup".
    if (focusFirst) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun SignInForm(vm: MainViewModel, hasAccounts: Boolean, accountsText: String, heading: String?) {
    val busy by vm.loginBusy.collectAsStateWithLifecycle()
    val error by vm.loginError.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }

    heading?.let { Text(it, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
    Text(
        "You can also paste a full M3U link into the server field.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
    )
    if (hasAccounts) {
        Text(
            "Already added: $accountsText. The new account will be shown together with them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Text("Add another account", fontWeight = FontWeight.Bold)
    }
    TvTextField(
        value = name, onValueChange = { name = it },
        label = { Text("Name (optional)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    val setServer = { text: String ->
        val parsed = XtreamApi.parseLink(text)
        if (parsed != null) {
            server = parsed.first
            user = parsed.second
            pass = parsed.third
        } else {
            server = text
        }
    }
    TvTextField(
        value = server,
        onValueChange = { setServer(it) },
        label = { Text("Server URL or M3U link") }, singleLine = true,
        placeholder = { Text("http://example.com:8080") },
        trailingIcon = { PasteButton { setServer(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    TvTextField(
        value = user, onValueChange = { user = it },
        label = { Text("Username") }, singleLine = true,
        trailingIcon = { PasteButton { user = it } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    TvTextField(
        value = pass, onValueChange = { pass = it },
        label = { Text("Password") }, singleLine = true,
        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PasteButton { pass = it }
                FocusBox(onClick = { showPass = !showPass }, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(end = 6.dp)) {
                    Text(if (showPass) "🙈" else "👁", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.login(name, server, user, pass) }, enabled = !busy) {
            Text(if (busy) "Connecting…" else if (hasAccounts) "Add account" else "Connect")
        }
        if (hasAccounts) OutlinedButton(onClick = { vm.openLogin(false) }) { Text("Cancel") }
        else OutlinedButton(onClick = { vm.welcomeForm.value = false }) { Text("← Back") }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp))
    }
    Spacer(Modifier.height(8.dp))
}
