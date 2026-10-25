package com.nova.iptv.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nova.iptv.data.Folders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.player.PlayItem
import com.nova.iptv.player.PlaybackQueue
import com.nova.iptv.player.PlayerActivity

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private fun askPermissions(storage: Boolean) {
        val wanted = mutableListOf<String>()
        if (storage && Folders.needsStoragePermission(this)) {
            wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        // Download folder access (Android 9 and older) and download notifications (Android 13+).
        if (savedInstanceState == null) askPermissions(storage = true)
        lifecycleScope.launch {
            vm.needStoragePermission.collect { needed ->
                if (needed) {
                    vm.needStoragePermission.value = false
                    askPermissions(storage = true)
                }
            }
        }
        // Draw edge to edge (Android 15 does it anyway) and keep content inside the safe area below.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            NovaTheme {
                val accounts by vm.accounts.collectAsStateWithLifecycle()
                val showLogin by vm.showLogin.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                // Android leaves "touch mode" on the first arrow after the mouse (sometimes swallowing that arrow).
                val inputModes = androidx.compose.ui.platform.LocalInputModeManager.current
                androidx.compose.runtime.LaunchedEffect(inputModes.inputMode) {
                    if (inputModes.inputMode == androidx.compose.ui.input.InputMode.Keyboard) NovaInput.remoteUsed()
                }
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    val startup by vm.startup.collectAsStateWithLifecycle()
                    val welcomeForm by vm.welcomeForm.collectAsStateWithLifecycle()
                    val webOnly by vm.webOnly.collectAsStateWithLifecycle()
                    val downloadsOnly by vm.downloadsOnly.collectAsStateWithLifecycle()
                    val st = startup
                    val onLogin = accounts.isEmpty() || showLogin
                    if (st is MainViewModel.Startup.Loading && accounts.isNotEmpty()) {
                        StartCover()
                    } else if (accounts.isEmpty() && !welcomeForm) {
                        if (downloadsOnly) DownloadsOnlyScreen(vm, this@MainActivity::play)
                        else if (webOnly) { WebOnlyScreen(vm); PinGateHost(vm) } else WelcomeScreen(vm, isTv)
                    } else if (!onLogin && st is MainViewModel.Startup.AllFailed) {
                        AccountsCheckScreen(vm, st)
                    } else androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                        if (!onLogin) StartupBanner(vm)
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f).fillMaxWidth()) {
                            if (onLogin) {
                                LoginScreen(vm, isTv)
                            } else {
                                HomeScreen(vm, isTv, this@MainActivity::play)
                            }
                            // Clock in a top corner (bottom corners are part of the footer strip).
                            if (!onLogin) ClockOverlay(settings.clockPosition)
                        }
                        FooterStrip(settings.clockPosition, showClock = !onLogin)
                        PinGateHost(vm)
                    }
                    // Bookmarks sent from another Nova on the Wi-Fi: "Add them?" over any screen.
                    IncomingBookmarksHost()
                    // A whole Nova configuration sent from another device (phone → box…): code, then restore.
                    IncomingTransferHost(vm)
                }
            }
        }
    }

    /** "Open with" / Share of a web page: open it in the Web tab. */
    private fun handleWebIntent(intent: android.content.Intent?) {
        if (intent?.action != ACTION_OPEN_WEB) return
        val url = intent.dataString ?: return
        android.util.Log.i("NovaTest", "WEB open from another app")
        vm.openWeb(url)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWebIntent(intent)
    }

    companion object {
        const val ACTION_OPEN_WEB = "com.nova.iptv.OPEN_WEB"
        const val EXTRA_FROM_BOOT = "from_boot"
    }

    private var autoPlayed = false

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true) {
            android.widget.Toast.makeText(this, "Nova started with the box", android.widget.Toast.LENGTH_SHORT).show()
        }
        if (savedInstanceState == null && intent?.action == ACTION_OPEN_WEB) { handleWebIntent(intent); autoPlayed = true }
        // Start the last watched channel / video when the app opens (Settings → Start-up).
        if (savedInstanceState == null && !autoPlayed) {
            autoPlayed = true
            lifecycleScope.launch {
                kotlinx.coroutines.delay(600)
                // Only once the start-up check let us in (not on the Accounts check screen).
                val st = vm.startup.first { it !is MainViewModel.Startup.Loading }
                // Nova was closed by Android during a video: back to where you were, no auto-play.
                if (vm.restoreReturnPoint()) return@launch
                if (st is MainViewModel.Startup.Ready) vm.autoPlayItems()?.let { play(it, 0) }
            }
        }
    }

    /** A video was started from here: when it ends, come back to the same place (tab, search, film page). */
    private var backFromPlayer = false

    override fun onResume() {
        super.onResume()
        if (backFromPlayer) { backFromPlayer = false; vm.restoreReturnPoint() }
        // Favorites may have been changed from the player screen.
        vm.refreshLocal()
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_HOVER_MOVE) NovaInput.mouseUsed()
        return super.dispatchGenericMotionEvent(ev)
    }

    private var swallowKeyUp = -1

    /** Remote buttons in Nova's lists and menus (Settings → Remote control). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        // From the mouse to the arrows: the first arrow puts the remote on the item under the mouse.
        val arrow = code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
            code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        if (event.action == android.view.KeyEvent.ACTION_UP && code == swallowKeyUp) { swallowKeyUp = -1; return true }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (arrow && NovaInput.remoteUsed()) { swallowKeyUp = code; return true }
            if (!arrow) NovaInput.mouse = false
        }
        if (!vm.showLogin.value && vm.accounts.value.isNotEmpty() && vm.startup.value is MainViewModel.Startup.Ready && !com.nova.iptv.data.RemoteKeys.isReserved(code)) {
            val storage = (application as com.nova.iptv.NovaApp).storage
            val action = com.nova.iptv.data.RemoteKeys.actionFor(storage, com.nova.iptv.data.RemoteKeys.Area.LISTS, code)
            if (action != null) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    android.util.Log.i("NovaTest", "KEY $action")
                    when (action) {
                        "list_page_down", "list_page_up" -> {
                            // A page = several steps of the focus, so it works in every list.
                            val step = if (action == "list_page_down") android.view.KeyEvent.KEYCODE_DPAD_DOWN else android.view.KeyEvent.KEYCODE_DPAD_UP
                            repeat(8) {
                                super.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, step))
                                super.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, step))
                            }
                        }
                        "list_live" -> vm.setTab(Tab.LIVE)
                        "list_films" -> vm.setTab(Tab.FILMS)
                        "list_series" -> vm.setTab(Tab.SERIES)
                        "list_search" -> vm.setTab(Tab.SEARCH)
                        "list_recent" -> vm.setTab(Tab.RECENT)
                        "list_settings" -> vm.setTab(Tab.SETTINGS)
                        "list_last" -> vm.lastChannelItems()?.let { play(it, 0) }
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun play(items: List<PlayItem>, index: Int) {
        if (items.isEmpty()) return
        val i = index.coerceIn(0, items.lastIndex)
        PlaybackQueue.items = items
        PlaybackQueue.index = i
        PlaybackQueue.playlist = null
        PlaybackQueue.groupKey = if (items[i].isLive && vm.tab.value == Tab.LIVE) vm.live.selected.value else null
        val item = items[i]
        // "Download & watch" needs the built-in player (it reads the file while it downloads).
        val choice = if (item.downloadId != null) "builtin" else vm.playerChoice(item)
        if (choice != "builtin" && vm.settings.value.requireVpn && !com.nova.iptv.player.Vpn.isActive(this)) {
            android.widget.Toast.makeText(this, "No VPN is connected. Connect your VPN app first.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!item.isLocal && !item.catchup) vm.addRecent(item.saved)
        vm.saveReturnPoint()
        backFromPlayer = true
        when (choice) {
            "builtin" -> startActivity(Intent(this, PlayerActivity::class.java))
            "ask" -> com.nova.iptv.player.ExternalPlayer.open(this, item, null)
            else -> com.nova.iptv.player.ExternalPlayer.open(this, item, choice)
        }
    }
}
