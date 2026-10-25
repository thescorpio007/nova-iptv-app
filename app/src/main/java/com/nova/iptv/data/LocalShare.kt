package com.nova.iptv.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Bookmarks between Nova devices on the same home Wi-Fi — nothing goes through the internet:
 *  - every Nova announces itself on the Wi-Fi (Android's network discovery) and listens on a small local port;
 *  - "📲 Send bookmarks" lists the Nova devices found and sends them to the chosen one;
 *  - "📷 Receive bookmarks (QR)" shows a QR code; the phone's camera opens a page served by this device
 *    (send the phone's Nova bookmarks, or paste web addresses). The page only works while that screen is open.
 * The receiving device always asks before adding anything (added to its own bookmarks, nothing removed).
 */
object LocalShare {
    const val PORT = 8642
    private const val TYPE = "_novaiptv._tcp."
    private const val TAG = "NovaShare"

    data class Device(val name: String, val host: String, val port: Int)
    data class Offer(val from: String, val bookmarks: List<WebStore.Bookmark>)

    /** Bookmarks waiting for "Add them?" on this device. */
    val incoming = MutableStateFlow<Offer?>(null)

    /** The QR page answers until this time (0 = closed). */
    @Volatile var pageOpenUntil = 0L

    private lateinit var app: Context
    private lateinit var http: OkHttpClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var registered: NsdManager.RegistrationListener? = null

    fun deviceName(context: Context): String =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL)

    fun start(context: Context, client: OkHttpClient) {
        if (server != null) return
        app = context.applicationContext
        http = client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        scope.launch {
            val s = runCatching { ServerSocket(PORT) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() } ?: return@launch
            server = s
            register(s.localPort)
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                scope.launch { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
    }

    private fun register(port: Int) {
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Nova · " + deviceName(app)
            serviceType = TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "registered ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "register failed $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        registered = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    /** This device's address on the Wi-Fi (for the QR code). */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    fun port(): Int = server?.localPort ?: PORT

    // ------------------------------------------------------------------ finding the other Nova devices

    /** Looks for other Nova devices on the Wi-Fi for a few seconds. */
    suspend fun discover(context: Context, millis: Long = 4000): List<Device> = withContext(Dispatchers.Main) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@withContext emptyList()
        val found = java.util.concurrent.ConcurrentHashMap<String, NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) { found[i.serviceName] = i }
            override fun onServiceLost(i: NsdServiceInfo) { found.remove(i.serviceName) }
        }
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        kotlinx.coroutines.delay(millis)
        runCatching { nsd.stopServiceDiscovery(listener) }
        val me = "Nova · " + deviceName(context)
        val out = mutableListOf<Device>()
        // Resolve one by one (older Android resolves only one at a time).
        for (i in found.values.filter { it.serviceName != me }) {
            val d = kotlinx.coroutines.suspendCancellableCoroutine<Device?> { cont ->
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, e: Int) { if (cont.isActive) cont.resume(null) {} }
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            val host = s.host?.hostAddress
                            if (cont.isActive) cont.resume(host?.let { Device(s.serviceName.removePrefix("Nova · "), it, s.port) }) {}
                        }
                    })
                }.onFailure { if (cont.isActive) cont.resume(null) {} }
            }
            if (d != null && out.none { it.host == d.host }) out += d
        }
        out
    }

    // ------------------------------------------------------------------ sending

    /** Sends this device's bookmarks to another Nova; returns null when done, or what went wrong. */
    suspend fun send(host: String, port: Int): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("from", deviceName(app))
            .put("bookmarks", JSONArray(WebStore.bookmarks.value.map { JSONObject().put("t", it.title).put("u", it.url).put("a", it.at) }))
        runCatching {
            http.newCall(Request.Builder().url("http://$host:$port/bookmarks").post(body.toString().toRequestBody("application/json".toMediaType())).build())
                .execute().use { r -> if (r.isSuccessful) null else "The other device answered ${r.code}" }
        }.getOrElse { "Could not reach it (${it.message ?: "no answer"}). Is Nova open on it, on the same Wi-Fi?" }
    }

    // ------------------------------------------------------------------ receiving

    private fun local(a: InetAddress) = a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress

    private fun handle(c: Socket) {
        c.soTimeout = 8000
        if (!local(c.inetAddress)) return respond(c, 403, "text/plain", "Home Wi-Fi only")
        // Read the request as bytes (Content-Length counts bytes, not letters).
        val ins = java.io.BufferedInputStream(c.getInputStream())
        fun line(): String? {
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val ch = ins.read()
                if (ch < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (ch == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                buf.write(ch)
                if (buf.size() > 8192) return buf.toString("UTF-8")
            }
        }
        val first = line() ?: return
        val (method, path) = first.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        var length = 0
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val bytes = ByteArray(length.coerceIn(0, 2_000_000))
        var read = 0
        while (read < bytes.size) { val n = ins.read(bytes, read, bytes.size - read); if (n < 0) break; read += n }
        val body = String(bytes, 0, read, Charsets.UTF_8)
        val pageOpen = System.currentTimeMillis() < pageOpenUntil
        when {
            method == "GET" && path == "/nova" -> respond(c, 200, "application/json", JSONObject().put("name", deviceName(app)).toString())
            method == "POST" && path == "/bookmarks" -> {
                val o = runCatching { JSONObject(body) }.getOrNull() ?: return respond(c, 400, "text/plain", "bad request")
                val a = o.optJSONArray("bookmarks") ?: JSONArray()
                val list = (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { b -> WebStore.Bookmark(b.optString("t"), b.optString("u"), b.optLong("a", 0L)) }
                }.filter { it.url.startsWith("http") }
                incoming.value = Offer(o.optString("from").ifBlank { "another device" }, list)
                respond(c, 200, "text/plain", "offered")
            }
            method == "GET" && path.substringBefore('?') == "/" && pageOpen -> respond(c, 200, "text/html; charset=utf-8", page())
            method == "POST" && path == "/links" && pageOpen -> {
                val text = URLDecoder.decode(body.substringAfter("links=", ""), "UTF-8")
                val urls = Regex("""https?://\S+""").findAll(text).map { it.value.trimEnd(',', ';') }.distinct().toList()
                val now = System.currentTimeMillis()
                incoming.value = Offer("a phone (web page)", urls.map { u ->
                    WebStore.Bookmark(android.net.Uri.parse(u).host ?: u, u, now)
                })
                respond(c, 200, "text/html; charset=utf-8", page("✓ Sent — accept them on the TV (${urls.size})."))
            }
            else -> respond(c, 404, "text/plain", if (pageOpen) "not found" else "Open Web ⚙ › Receive bookmarks (QR) on the TV first")
        }
    }

    private fun page(note: String = ""): String {
        val me = deviceName(app)
        val addr = "${localAddress()}:${port()}"
        val intent = "intent://send?to=$addr#Intent;scheme=novaiptv;package=${app.packageName};end"
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Send to Nova</title><style>body{font-family:sans-serif;background:#0f1d3a;color:#fff;margin:0;padding:18px}
a.b,button{display:inline-block;background:#39d98a;color:#062;border:0;border-radius:18px;padding:12px 18px;font-weight:700;font-size:16px;text-decoration:none;margin:8px 0}
textarea{width:100%;height:120px;border-radius:10px;border:1px solid #2f4b7c;background:#0b1220;color:#fff;padding:8px;box-sizing:border-box}
p{color:#9fb3e0}.n{color:#5ff2d6;font-weight:700}</style></head><body>
<h2>📺 Send to Nova on “$me”</h2>${if (note.isNotEmpty()) "<p class=n>$note</p>" else ""}
<p>From Nova on this phone:</p><a class=b href="$intent">📲 Send my Nova bookmarks</a>
<p>Or paste one or more web addresses:</p><form method="post" action="/links"><textarea name="links" placeholder="https://…"></textarea><br><button>Add to the TV</button></form>
</body></html>"""
    }

    private fun respond(c: Socket, code: Int, type: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val out = c.getOutputStream()
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }
}
