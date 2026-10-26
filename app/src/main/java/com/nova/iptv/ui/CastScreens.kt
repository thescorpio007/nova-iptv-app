package com.nova.iptv.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.Cast
import com.nova.iptv.data.SavedItem
import com.nova.iptv.player.PlayItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun clock(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/**
 * Phone: 📺 Play on TV. Finds the Nova TVs on the Wi-Fi, pairs the first time (code shown on the TV),
 * sends the video, then works as a small remote (⏸ / ±30 s / ■ Stop).
 * Opened with "pick" = true: first choose a video stored on the phone.
 */
class CastSendActivity : ComponentActivity() {
    private var picked by mutableStateOf<PlayItem?>(null)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) { finish(); return@registerForActivityResult }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "Video"
        picked = PlayItem(SavedItem("file", uri.toString(), name.substringBeforeLast('.').ifBlank { name }), uri.toString(), isLive = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = intent?.getBooleanExtra(EXTRA_PICK, false) == true
        if (pick && savedInstanceState == null) picker.launch(arrayOf("video/*"))
        else picked = Cast.pending
        setContent {
            NovaTheme {
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                    val item = picked
                    if (item == null) {
                        if (!pick) LaunchedEffect(Unit) { finish() }
                    } else {
                        CastPanel(item, if (pick) 0 else Cast.pendingPos, if (pick) null else Cast.onSent) { finish() }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PICK = "pick"
        fun pickVideo(context: Context) = context.startActivity(Intent(context, CastSendActivity::class.java).putExtra(EXTRA_PICK, true))
    }
}

@Composable
private fun CastPanel(item: PlayItem, positionMs: Long, onSent: ((Boolean) -> Unit)?, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tvs by remember { mutableStateOf<List<Cast.Tv>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var pairing by remember { mutableStateOf<Cast.Tv?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var playingOn by remember { mutableStateOf<Cast.Tv?>(null) }
    var hiddenNote by remember { mutableStateOf(false) }
    var continueAt by remember { mutableStateOf(true) }
    var stopPhone by remember { mutableStateOf(true) }
    var countdown by remember { mutableIntStateOf(-1) }

    fun send(tv: Cast.Tv) {
        if (busy) return
        busy = true; message = null; countdown = -1
        scope.launch {
            val (payload, problem) = Cast.payload(item, if (continueAt) positionMs else 0)
            if (payload == null) { busy = false; message = problem; return@launch }
            when (val r = Cast.play(tv, payload)) {
                "ok", "ok-hidden" -> {
                    if (payload.optBoolean("stream")) CastService.start(context)
                    hiddenNote = r == "ok-hidden"
                    playingOn = tv
                    onSent?.invoke(stopPhone)
                }
                "pair" -> {
                    val err = Cast.askPair(tv)
                    if (err == null) { pairing = tv; code = "" } else message = err
                }
                else -> message = r
            }
            busy = false
        }
    }

    LaunchedEffect(round) {
        tvs = null
        val list = Cast.findTvs(context)
        tvs = list
        // One paired TV (or the last one used): send by itself after 10 s.
        if (list.firstOrNull()?.let { it.paired && it.receiving && (list.size == 1 || it.id == Cast.lastTv) } == true) countdown = 10
    }
    LaunchedEffect(countdown) {
        if (countdown > 0) { delay(1000); if (countdown > 0) countdown-- }
        else if (countdown == 0) tvs?.firstOrNull()?.let { send(it) }
    }

    Panel {
        val on = playingOn
        when {
            on != null -> RemotePanel(item, on, hiddenNote, onClose)
            pairing != null -> {
                val tv = pairing!!
                Text("🔐 Pair with ${tv.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
                Text("Your TV now shows a 4-digit code. Type it here. You only do this once for this TV; the links are then sent encrypted.", fontSize = 13.sp, color = Color(0xFF9FB3E0))
                OutlinedTextField(
                    code, { v -> code = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Code shown on the TV") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = Color(0xFFFFB4A8), fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        WideButton("Pair & send", primary = true) {
                            if (code.length != 4 || busy) return@WideButton
                            busy = true; message = null
                            scope.launch {
                                val err = Cast.confirmPair(tv, code)
                                busy = false
                                if (err != null) message = err
                                else { pairing = null; send(tv.copy()) }
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) { WideButton("Cancel") { pairing = null } }
                }
            }
            else -> {
                Text("📺 Play on TV", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
                Text(item.title, fontSize = 14.sp, maxLines = 1)
                Text("Nova TVs on this Wi-Fi (found automatically):", fontSize = 13.sp, color = Color(0xFF9FB3E0))
                val list = tvs
                when {
                    list == null -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Looking…", fontSize = 13.sp) }
                    list.isEmpty() -> Text("No Nova found. Open Nova on the TV box (same Wi-Fi), then search again.", fontSize = 14.sp)
                    else -> LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list, key = { it.id }) { tv ->
                            FocusBox(onClick = { if (tv.receiving) send(tv) }, modifier = Modifier.fillMaxWidth()) { f ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (f) Color.White else Color(0xFF1F3159), RoundedCornerShape(10.dp))
                                        .then(if (countdown > 0 && tv == list.first()) Modifier.border(2.dp, GREEN, RoundedCornerShape(10.dp)) else Modifier)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text((if (tv.isTv) "📺  " else "📱  ") + tv.name, color = if (f) Color(0xFF0D2236) else Color.White, fontWeight = FontWeight.Bold)
                                        Text(
                                            when { !tv.receiving -> "Receiving is off on it"; tv.paired -> "Paired ✓"; else -> "Not paired yet — a code will show on it" },
                                            fontSize = 12.sp, color = if (f) Color(0xFF33445A) else Color(0xFF9FB3E0),
                                        )
                                    }
                                    if (countdown > 0 && tv == list.first()) Text("Sends in $countdown s", color = if (f) Color(0xFF0A6B3A) else GREEN, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
                if (!item.isLive && positionMs > 10_000) {
                    CheckLine("Continue at ${clock(positionMs)} on the TV", continueAt) { continueAt = it; countdown = -1 }
                }
                if (onSent != null) CheckLine("Stop playing on the phone", stopPhone) { stopPhone = it; countdown = -1 }
                if (item.isLocal) Text("This video is on the phone: the phone streams it to the TV (keep it on this Wi-Fi).", fontSize = 12.sp, color = Color(0xFF9FB3E0))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = Color(0xFFFFB4A8), fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        WideButton(if (countdown > 0) "Send now" else "⟳ Search again", primary = countdown > 0) {
                            if (countdown > 0) tvs?.firstOrNull()?.let { send(it) } else round++
                        }
                    }
                    Column(Modifier.weight(1f)) { WideButton("Cancel") { countdown = -1; onClose() } }
                }
            }
        }
    }
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FocusBox(onClick = { onChange(!checked) }, modifier = Modifier.fillMaxWidth()) { f ->
        Text(
            (if (checked) "☑  " else "☐  ") + label,
            modifier = Modifier.fillMaxWidth().background(if (f) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(8.dp)).padding(6.dp),
            fontSize = 14.sp,
        )
    }
}

/** The phone as a remote while the TV plays: position, ⏸, −30 s, +30 s, ■ Stop. */
@Composable
private fun RemotePanel(item: PlayItem, tv: Cast.Tv, hiddenNote: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    val streaming by Cast.streaming.collectAsStateWithLifecycle()
    LaunchedEffect(tv.id) {
        var idle = 0
        while (true) {
            val s = Cast.status(tv)
            if (s != null && !s.optBoolean("idle")) {
                idle = 0
                pos = s.optLong("pos"); dur = s.optLong("dur"); playing = s.optBoolean("playing", true)
            } else if (s != null && ++idle >= 3) ended = true
            delay(2000)
        }
    }
    fun cmd(c: String) { scope.launch { Cast.control(tv, c) } }
    Text("📡 Playing on ${tv.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
    Text(item.title, fontSize = 14.sp, maxLines = 2)
    if (hiddenNote) Text("Nova couldn't come to the front on the TV: open Nova there (or allow it “Display over other apps” in the TV's settings).", fontSize = 12.sp, color = Color(0xFFFFD27A))
    if (ended) Text("The TV has stopped playing it.", fontSize = 13.sp, color = Color(0xFF9FB3E0))
    if (!item.isLive && dur > 0) {
        LinearProgressIndicator(progress = { (pos.toFloat() / dur).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("${clock(pos)} / ${clock(dur)}", fontSize = 13.sp, color = Color(0xFF9FB3E0))
    }
    if (streaming) Text("This phone is streaming the video: keep it on this Wi-Fi. The screen can turn off; the notification keeps it running.", fontSize = 12.sp, color = Color(0xFF9FB3E0))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) { WideButton(if (playing) "⏸" else "▶") { playing = !playing; cmd("toggle") } }
        if (!item.isLive) {
            Column(Modifier.weight(1f)) { WideButton("−30 s") { cmd("seek:-30000") } }
            Column(Modifier.weight(1f)) { WideButton("+30 s") { cmd("seek:30000") } }
        }
        Column(Modifier.weight(1f)) {
            WideButton("■ Stop") { cmd("stop"); CastService.stop(context); onClose() }
        }
    }
    WideButton("Close (keeps playing on the TV)", onClick = onClose)
}

/** TV: a phone asks to pair — shows the 4-digit code until it is typed (or 2 minutes). */
class CastPairActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovaTheme {
                val req by Cast.pairing.collectAsStateWithLifecycle()
                LaunchedEffect(req) { if (req == null) finish() }
                LaunchedEffect(Unit) { delay(120_000); Cast.pairing.value = null }
                Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                    val r = req ?: return@Box
                    Panel {
                        Text("📲 “${r.from}” wants to send videos to this TV", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Text(r.code.toCharArray().joinToString(" "), fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = GREEN,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text("Type this code on the phone. Next time it plays directly.", fontSize = 14.sp, color = Color(0xFF9FB3E0), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        WideButton("Refuse") { Cast.pairing.value = null }
                    }
                }
            }
        }
    }
}

/** Phone: keeps the phone serving a video to the TV while its screen is off (notification with ■ Stop). */
class CastService : Service() {
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wake: android.os.PowerManager.WakeLock? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            // Nothing read by the TV for 20 minutes: stop.
            if (System.currentTimeMillis() - Cast.lastServed > 20 * 60_000L) { stopSelf(); return }
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Play on TV", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("📡 Streaming to the TV")
            .setContentText("Keep the phone on this Wi-Fi")
            .setOngoing(true)
            .addAction(0, "■ Stop", stop)
            .build()
        ServiceCompat.startForeground(this, 77, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nova:cast")?.apply { acquire() }
            wake = (getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "nova:cast")?.apply { acquire(4 * 60 * 60_000L) }
        }
        Cast.streaming.value = true
        Cast.lastServed = System.currentTimeMillis()
        handler.removeCallbacks(check)
        handler.postDelayed(check, 60_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        runCatching { wifiLock?.release() }
        runCatching { if (wake?.isHeld == true) wake?.release() }
        Cast.streaming.value = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cast"
        private const val ACTION_STOP = "stop"
        fun start(context: Context) = runCatching { ContextCompat.startForegroundService(context, Intent(context, CastService::class.java)) }
        fun stop(context: Context) = runCatching { context.stopService(Intent(context, CastService::class.java)) }
    }
}


/** TV: a DLNA app wants to play something ("Ask me first"): plays after 10 s unless refused. */
class DlnaAskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovaTheme {
                val ask by com.nova.iptv.data.Dlna.asking.collectAsStateWithLifecycle()
                var left by remember { mutableIntStateOf(10) }
                LaunchedEffect(ask) { if (ask == null) finish() else left = 10 }
                LaunchedEffect(ask, left) {
                    val a = ask ?: return@LaunchedEffect
                    if (left > 0) { delay(1000); left-- } else com.nova.iptv.data.Dlna.accept(a)
                }
                Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                    val a = ask ?: return@Box
                    Panel {
                        Text("📡 ${a.from} wants to play on this TV", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GREEN)
                        Text(a.title, fontSize = 16.sp, maxLines = 2)
                        Text("From ${a.host} · after you accept, this app plays directly for 3 hours.", fontSize = 13.sp, color = Color(0xFF9FB3E0))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) { WideButton("▶ Play ($left s)", primary = true) { com.nova.iptv.data.Dlna.accept(a) } }
                            Column(Modifier.weight(1f)) { WideButton("Refuse") { com.nova.iptv.data.Dlna.refuse() } }
                        }
                    }
                }
            }
        }
    }
}
