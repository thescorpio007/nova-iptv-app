package com.nova.iptv.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.nova.iptv.NovaApp
import com.nova.iptv.player.PlayItem
import com.nova.iptv.player.PlaybackQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 📺 Play on TV: Nova on a phone sends what it plays to Nova on the TV box (same Wi-Fi, found automatically).
 *  - First time: the TV shows a 4-digit code, typed on the phone; they then share a secret key, and every link
 *    (IPTV links contain the server user name and password) is sent encrypted with it.
 *  - A video stored on the phone (a Nova download, a gallery video) is streamed by the phone itself:
 *    the TV plays http://phone/media/… and can pause / jump anywhere (range requests).
 *  - The phone then works as a small remote: ⏸ / ±30 s / ■ Stop and the position, while the TV plays it.
 */
object Cast {
    private const val TAG = "NovaCast"
    const val EXTRA_FROM = "nova_cast_from"

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("nova_cast", Context.MODE_PRIVATE)
    private val rnd = SecureRandom()
    private val http: OkHttpClient by lazy {
        (app as NovaApp).http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    fun init(context: Context) { app = context.applicationContext }

    /** This device's id (stays the same, so a TV remembers a phone and a phone remembers a TV). */
    val myId: String
        get() = prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }

    fun isTv(context: Context): Boolean {
        val tv = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        return tv || !context.packageManager.hasSystemFeature("android.hardware.touchscreen")
    }

    // ------------------------------------------------------------------ settings (TV side)

    /** 📡 Receive from Nova phones (on by default). */
    var receiving: Boolean
        get() = prefs.getBoolean("receive", true)
        set(v) = prefs.edit().putBoolean("receive", v).apply()

    private fun phones(): JSONObject = runCatching { JSONObject(prefs.getString("phones", "{}") ?: "{}") }.getOrDefault(JSONObject())
    fun pairedPhones(): List<String> = phones().let { p -> p.keys().asSequence().map { p.optJSONObject(it)?.optString("name").orEmpty().ifBlank { "a phone" } }.toList() }
    fun forgetPhones() = prefs.edit().putString("phones", "{}").apply()
    private fun phoneSecret(id: String): String? = phones().optJSONObject(id)?.optString("secret")?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ encryption (AES-GCM with the shared key)

    private fun newSecret(): String = Base64.encodeToString(ByteArray(32).also { rnd.nextBytes(it) }, Base64.NO_WRAP)

    private fun seal(text: String, secret: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.decode(secret, Base64.NO_WRAP), "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun unseal(data: String, secret: String): String? = runCatching {
        val all = Base64.decode(data, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.decode(secret, Base64.NO_WRAP), "AES"), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrNull()

    // ------------------------------------------------------------------ TV side: state shown on screen

    data class PairRequest(val from: String, val phone: String, val code: String, val until: Long, var tries: Int = 0)
    /** A phone asking to pair: the TV shows [PairRequest.code]. */
    val pairing = MutableStateFlow<PairRequest?>(null)

    /** What the TV plays for a phone (the phone's remote shows it). */
    data class Now(val title: String, val pos: Long, val dur: Long, val playing: Boolean)
    @Volatile var now: Now? = null
    /** Remote buttons from the phone: "toggle", "seek:<ms>", "stop". */
    val commands = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var savedQueue: Array<Any?>? = null
    /** Each video received gets a number; an older received video still open closes itself (one at a time). */
    val generation = MutableStateFlow(0)
    /** The link playing for a phone / DLNA app (DLNA reports it). */
    @Volatile var nowUrl: String? = null

    /** Called by the player when a video sent by a phone closes: the list watched before comes back. */
    fun restoreQueue() {
        nowUrl = null
        val q = savedQueue ?: return
        savedQueue = null
        @Suppress("UNCHECKED_CAST")
        PlaybackQueue.items = q[0] as List<PlayItem>
        PlaybackQueue.index = q[1] as Int
        PlaybackQueue.groupKey = q[2] as String?
        @Suppress("UNCHECKED_CAST")
        PlaybackQueue.playlist = q[3] as LinkedHashMap<String, List<PlayItem>>?
        now = null
    }

    private fun inForeground(): Boolean = runCatching {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(true)

    private fun canStartFromBackground(): Boolean =
        android.os.Build.VERSION.SDK_INT < 29 || inForeground() || android.provider.Settings.canDrawOverlays(app)

    // ------------------------------------------------------------------ TV side: requests (from LocalShare)

    /** Answers a /cast/… request; null = not a cast address. */
    fun handle(method: String, path: String, body: String): Pair<Int, String>? {
        if (!path.startsWith("/cast/") || method != "POST") return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to err("bad request")
        val phone = o.optString("phone")
        return when (path) {
            "/cast/pair" -> {
                if (!receiving) return 403 to err("This TV doesn't receive from phones (Settings › 📡 Receive from phones).")
                val code = "%04d".format(rnd.nextInt(10000))
                val req = PairRequest(o.optString("from").ifBlank { "a phone" }, phone, code, System.currentTimeMillis() + 120_000)
                pairing.value = req
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        app.startActivity(Intent(app, com.nova.iptv.ui.CastPairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Log.i(TAG, "pairing asked by ${req.from}")
                200 to JSONObject().put("ok", true).put("shown", canStartFromBackground()).toString()
            }
            "/cast/pair2" -> {
                val req = pairing.value
                if (req == null || req.phone != phone || System.currentTimeMillis() > req.until) return 410 to err("The code has expired — try again.")
                val proof = runCatching { NovaCrypto.decrypt(o.optString("proof"), req.code) }.getOrNull()
                if (proof != phone) {
                    req.tries++
                    if (req.tries >= 3) pairing.value = null
                    return 401 to err(if (req.tries >= 3) "Wrong code 3 times — start again." else "Wrong code — check the TV.")
                }
                val secret = newSecret()
                val all = phones().put(phone, JSONObject().put("name", req.from).put("secret", secret))
                prefs.edit().putString("phones", all.toString()).apply()
                pairing.value = null
                Log.i(TAG, "paired with ${req.from}")
                200 to JSONObject().put("tv", myId).put("name", LocalShare.deviceName(app)).put("data", NovaCrypto.encrypt(secret, req.code)).toString()
            }
            "/cast/play", "/cast/control", "/cast/status" -> {
                if (!receiving) return 403 to err("This TV doesn't receive from phones (Settings › 📡 Receive from phones).")
                val secret = phoneSecret(phone) ?: return 401 to err("pair")
                val from = phones().optJSONObject(phone)?.optString("name").orEmpty().ifBlank { "a phone" }
                when (path) {
                    "/cast/status" -> 200 to (now?.let {
                        JSONObject().put("title", it.title).put("pos", it.pos).put("dur", it.dur).put("playing", it.playing)
                    } ?: JSONObject().put("idle", true)).toString()
                    else -> {
                        val text = unseal(o.optString("data"), secret) ?: return 401 to err("pair")
                        val p = runCatching { JSONObject(text) }.getOrNull() ?: return 400 to err("bad request")
                        if (path == "/cast/control") {
                            commands.tryEmit(p.optString("cmd"))
                            200 to "{}"
                        } else {
                            playHere(p, from)
                            200 to JSONObject().put("ok", true).put("shown", canStartFromBackground()).toString()
                        }
                    }
                }
            }
            else -> 404 to err("not found")
        }
    }

    private fun err(message: String) = JSONObject().put("error", message).toString()

    fun playHere(o: JSONObject, from: String) {
        val url = o.optString("url")
        if (url.isBlank()) return
        val live = o.optBoolean("live")
        val title = o.optString("title").ifBlank { "Video" }
        val type = o.optString("type").ifBlank { if (live) ItemType.LIVE else ItemType.MOVIE }
        val saved = SavedItem(type, o.optString("id").ifBlank { url }, title, o.optString("icon").ifBlank { null }, accountId = o.optString("acc").ifBlank { "cast" })
        val st = (app as NovaApp).storage
        o.optJSONObject("headers")?.let { h ->
            val host = runCatching { Uri.parse(url).host }.getOrNull()
            if (host != null && h.length() > 0) st.setLinkHeaders(host, st.linkHeaders(host) + h.keys().asSequence().associateWith { h.optString(it) })
        }
        val pos = o.optLong("pos")
        if (!live) st.setPosition(saved.resumeKey, if (pos > 10_000) pos else 0)
        val item = PlayItem(saved, url, isLive = live, temporary = true)
        Log.i(TAG, "play from $from: $title")
        Handler(Looper.getMainLooper()).post {
            if (savedQueue == null) savedQueue = arrayOf(PlaybackQueue.items, PlaybackQueue.index, PlaybackQueue.groupKey, PlaybackQueue.playlist)
            PlaybackQueue.items = listOf(item)
            PlaybackQueue.index = 0
            PlaybackQueue.groupKey = null
            PlaybackQueue.playlist = null
            now = Now(title, pos, 0, true)
            nowUrl = url
            generation.value = generation.value + 1
            runCatching {
                app.startActivity(
                    Intent(app, com.nova.iptv.player.PlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_FROM, from),
                )
            }
        }
    }

    // ------------------------------------------------------------------ phone side: files streamed to the TV

    private class Served(val uri: Uri?, val path: String?, val mime: String)
    private val served = ConcurrentHashMap<String, Served>()
    /** Last time the TV read a file from this phone (the streaming notification stops after a long pause). */
    @Volatile var lastServed = 0L
    val streaming = MutableStateFlow(false)

    /** Makes a video on this phone readable by the TV; returns its address on the Wi-Fi (null = no Wi-Fi address). */
    fun serve(url: String, name: String): String? {
        val addr = LocalShare.localAddress() ?: return null
        val token = UUID.randomUUID().toString().replace("-", "")
        val (uri, path) = when {
            url.startsWith("/") -> null to url
            url.startsWith("file:") -> null to (Uri.parse(url).path ?: return null)
            else -> Uri.parse(url) to null
        }
        val ext = (path ?: name).substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 }
            ?: runCatching { app.contentResolver.getType(uri!!) }.getOrNull()?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: "mp4"
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "ts" -> "video/mp2t"; "mkv" -> "video/x-matroska"; else -> "video/mp4"
        }
        served[token] = Served(uri, path, mime)
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60).trim().ifBlank { "video" }
        val file = if (safe.lowercase().endsWith(".$ext")) safe else "$safe.$ext"
        return "http://$addr:${LocalShare.port()}/media/$token/" + Uri.encode(file)
    }

    /** GET / HEAD /media/<token>/<name>: the file, with range requests (jump anywhere). True = answered. */
    fun serveMedia(method: String, path: String, headers: Map<String, String>, out: OutputStream): Boolean {
        if (!path.startsWith("/media/")) return false
        val s = served[path.removePrefix("/media/").substringBefore('/')]
        if (s == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
            return true
        }
        lastServed = System.currentTimeMillis()
        val afd = s.uri?.let { runCatching { app.contentResolver.openAssetFileDescriptor(it, "r") }.getOrNull() }
        val input = when {
            s.path != null -> java.io.FileInputStream(s.path)
            afd != null -> afd.createInputStream()
            else -> null
        }
        if (input == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
            return true
        }
        try { input.use { ins ->
            val channel = ins.channel
            val base = if (afd != null && afd.startOffset > 0) afd.startOffset else 0L
            val total = when {
                s.path != null -> java.io.File(s.path).length()
                afd != null && afd.length >= 0 -> afd.length
                else -> channel.size() - base
            }
            val range = headers["range"]?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
            var start = 0L
            var end = total - 1
            if (range != null) {
                val a = range.groupValues[1]; val b = range.groupValues[2]
                if (a.isEmpty() && b.isNotEmpty()) start = (total - b.toLong()).coerceAtLeast(0)
                else {
                    start = a.toLongOrNull() ?: 0L
                    if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                }
            }
            if (start >= total && total > 0) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                return true
            }
            val length = end - start + 1
            val head = StringBuilder()
                .append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ${s.mime}\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n")
                .apply { if (range != null) append("Content-Range: bytes $start-$end/$total\r\n") }
                .append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            if (method == "HEAD") { out.flush(); return true }
            channel.position(base + start)
            val buf = ByteArray(128 * 1024)
            var left = length
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
                lastServed = System.currentTimeMillis()
            }
            out.flush()
        } } finally { runCatching { afd?.close() } }
        return true
    }

    // ------------------------------------------------------------------ phone side: talking to the TV

    data class Tv(val id: String, val name: String, val host: String, val port: Int, val isTv: Boolean, val receiving: Boolean) {
        val paired: Boolean get() = secretFor(id) != null
    }

    fun secretFor(tvId: String): String? = runCatching { prefs.getString("tv_$tvId", null) }.getOrNull()
    var lastTv: String?
        get() = prefs.getString("last_tv", null)
        set(v) = prefs.edit().putString("last_tv", v).apply()

    /** The Nova devices on this Wi-Fi that can receive (TV boxes first). */
    suspend fun findTvs(context: Context): List<Tv> {
        val found = LocalShare.discover(context)
        return withContext(Dispatchers.IO) {
            found.mapNotNull { d ->
                val o = runCatching {
                    http.newCall(Request.Builder().url("http://${d.host}:${d.port}/nova").build()).execute().use { r -> JSONObject(r.body?.string().orEmpty()) }
                }.getOrNull() ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                Tv(id, o.optString("name").ifBlank { d.name }, d.host, d.port, o.optBoolean("tv"), o.optBoolean("cast", true))
            }.sortedWith(compareByDescending<Tv> { it.id == lastTv }.thenByDescending { it.isTv }.thenByDescending { it.paired })
        }
    }

    private fun post(tv: Tv, path: String, o: JSONObject, timeoutSec: Long = 15): Pair<Int, JSONObject> = runCatching {
        http.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url("http://${tv.host}:${tv.port}$path").post(o.toString().toRequestBody("application/json".toMediaType())).build())
            .execute().use { r -> r.code to (runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() ?: JSONObject()) }
    }.getOrElse { 0 to JSONObject().put("error", "Could not reach the TV (${it.message ?: "no answer"}). Is Nova open on it, on the same Wi-Fi?") }

    private fun base() = JSONObject().put("phone", myId).put("from", LocalShare.deviceName(app))

    /** Asks the TV to show a code. Null = shown, else what went wrong. */
    suspend fun askPair(tv: Tv): String? = withContext(Dispatchers.IO) {
        val (code, o) = post(tv, "/cast/pair", base())
        if (code == 200) null else o.optString("error").ifBlank { "The TV answered $code" }
    }

    /** Sends the code typed on the phone. Null = paired. */
    suspend fun confirmPair(tv: Tv, code: String): String? = withContext(Dispatchers.IO) {
        val (status, o) = post(tv, "/cast/pair2", base().put("proof", NovaCrypto.encrypt(myId, code)), 30)
        if (status != 200) return@withContext o.optString("error").ifBlank { "The TV answered $status" }
        val secret = runCatching { NovaCrypto.decrypt(o.optString("data"), code) }.getOrNull() ?: return@withContext "Pairing failed — try again."
        prefs.edit().putString("tv_${o.optString("tv").ifBlank { tv.id }}", secret).apply()
        null
    }

    /** "ok", "ok-hidden" (the TV couldn't come to the front: open Nova on it), "pair", or what went wrong. */
    suspend fun play(tv: Tv, payload: JSONObject): String = withContext(Dispatchers.IO) {
        val secret = secretFor(tv.id) ?: return@withContext "pair"
        val (code, o) = post(tv, "/cast/play", base().put("data", seal(payload.toString(), secret)))
        when {
            code == 200 -> { lastTv = tv.id; if (o.optBoolean("shown", true)) "ok" else "ok-hidden" }
            code == 401 -> { prefs.edit().remove("tv_${tv.id}").apply(); "pair" }
            else -> o.optString("error").ifBlank { "The TV answered $code" }
        }
    }

    suspend fun control(tv: Tv, cmd: String) {
        withContext(Dispatchers.IO) {
            val secret = secretFor(tv.id) ?: return@withContext
            post(tv, "/cast/control", base().put("data", seal(JSONObject().put("cmd", cmd).toString(), secret)), 5)
        }
    }

    suspend fun status(tv: Tv): JSONObject? = withContext(Dispatchers.IO) {
        post(tv, "/cast/status", base(), 4).let { (c, o) -> if (c == 200) o else null }
    }

    /**
     * What is sent for [item]: the link (with the site details it needs), or — for a video on this phone — the
     * address where the phone serves it. Second = what went wrong.
     */
    fun payload(item: PlayItem, positionMs: Long): Pair<JSONObject?, String?> {
        val st = (app as NovaApp).storage
        var url = item.url
        var stream = false
        if (url.startsWith("nova-dl://")) return null to "This download is still running — send it to the TV when it has finished."
        if (item.isLocal) {
            url = serve(url, item.title) ?: return null to "This phone has no Wi-Fi address — connect it to the same Wi-Fi as the TV."
            stream = true
        }
        val o = JSONObject()
            .put("url", url).put("title", item.title).put("live", item.isLive)
            .put("type", item.saved.type).put("id", item.saved.id).put("acc", item.saved.accountId)
            .put("pos", if (item.isLive) 0 else positionMs).put("stream", stream)
        item.saved.icon?.takeIf { it.startsWith("http") }?.let { o.put("icon", it) }
        if (!stream) {
            val host = runCatching { Uri.parse(url).host }.getOrNull()
            if (host != null) {
                val h = st.linkHeaders(host)
                if (h.isNotEmpty()) o.put("headers", JSONObject(h))
            }
        }
        return o to null
    }

    /** Phone side: the item waiting in the "📺 Play on TV" screen, and what to do on the phone once it is sent. */
    @Volatile var pending: PlayItem? = null
    @Volatile var pendingPos: Long = 0
    @Volatile var onSent: ((stopPhone: Boolean) -> Unit)? = null

    fun open(context: Context, item: PlayItem, positionMs: Long, onSent: ((Boolean) -> Unit)? = null) {
        pending = item
        pendingPos = positionMs
        this.onSent = onSent
        context.startActivity(Intent(context, com.nova.iptv.ui.CastSendActivity::class.java).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** /nova answer: who this device is. */
    fun canShowNow(): Boolean = canStartFromBackground()

    fun describe(context: Context): JSONObject =
        JSONObject().put("name", LocalShare.deviceName(context)).put("id", myId).put("tv", isTv(context)).put("cast", receiving)
}
