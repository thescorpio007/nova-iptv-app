package com.nova.iptv.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 📡 DLNA: Nova on the box appears as a "media renderer" ("Nova (Ugoos AM8)") in DLNA cast apps
 * (BubbleUPnP, Web Video Caster, some gallery apps…): they send a link and control play / pause / seek /
 * stop / volume. Standard UPnP AV: SSDP discovery (UDP 1900) + a description and SOAP control on Nova's
 * local port (shared with 📺 Play on TV).
 * Mode: "ask" (a popup on the TV, plays after 10 s unless refused), "direct" or "off".
 */
object Dlna {
    private const val TAG = "NovaDlna"
    private const val GROUP = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    private const val DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("nova_cast", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: MulticastSocket? = null
    private var lock: android.net.wifi.WifiManager.MulticastLock? = null
    private var job: kotlinx.coroutines.Job? = null

    /** "ask" (default on TV boxes), "direct" or "off" (default on phones). */
    var mode: String
        get() = prefs.getString("dlna", null) ?: if (Cast.isTv(app)) "ask" else "off"
        set(v) { prefs.edit().putString("dlna", v).apply(); restart() }

    private val udn: String get() = "uuid:" + UUID.nameUUIDFromBytes(("nova-dlna-" + Cast.myId).toByteArray())
    fun friendlyName(): String = "Nova (" + LocalShare.deviceName(app) + ")"

    // ------------------------------------------------------------------ what plays

    @Volatile private var uri: String? = null
    @Volatile private var title: String = "Video"
    @Volatile private var meta: String = ""
    /** The link we started (null = nothing started by DLNA). */
    @Volatile private var started: String? = null

    /** Apps allowed in "ask" mode (their address), for 3 hours. */
    private val allowed = ConcurrentHashMap<String, Long>()
    data class Ask(val from: String, val host: String, val title: String, val url: String)
    val asking = MutableStateFlow<Ask?>(null)

    fun init(context: Context) { app = context.applicationContext }

    fun start(context: Context) {
        app = context.applicationContext
        if (mode == "off" || job != null) return
        job = scope.launch { runCatching { listen() }.onFailure { Log.w(TAG, "ssdp stopped: $it") } }
        scope.launch { eventLoop() }
    }

    private fun stop() {
        val s = socket
        if (s != null) runCatching { notifyAll(s, alive = false) }
        job?.cancel(); job = null
        runCatching { s?.close() }; socket = null
        runCatching { if (lock?.isHeld == true) lock?.release() }
    }

    private fun restart() { stop(); if (mode != "off") start(app) }

    // ------------------------------------------------------------------ SSDP (found by the apps)

    private fun location(): String? = LocalShare.localAddress()?.let { "http://$it:${LocalShare.port()}/dlna/desc.xml" }

    private val types get() = listOf("upnp:rootdevice", udn, DEVICE, AVT, RC, CM)

    private suspend fun listen() {
        @Suppress("DEPRECATION")
        lock = (app.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
            ?.createMulticastLock("nova:dlna")?.apply { setReferenceCounted(false); acquire() }
        val s = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(SSDP_PORT))
            timeToLive = 4
        }
        socket = s
        @Suppress("DEPRECATION")
        s.joinGroup(InetAddress.getByName(GROUP))
        Log.i(TAG, "listening as ${friendlyName()}")
        // Announce now and every minute.
        scope.launch { while (isActive && socket === s) { runCatching { notifyAll(s, alive = true) }; delay(60_000) } }
        val buf = ByteArray(4096)
        while (scope.isActive && !s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { s.receive(p) }.isFailure) { if (s.isClosed) return; delay(200); continue }
            val text = String(p.data, 0, p.length, Charsets.UTF_8)
            if (!text.startsWith("M-SEARCH", true)) continue
            val st = Regex("""(?im)^ST:\s*(.+?)\s*$""").find(text)?.groupValues?.get(1) ?: continue
            val answers = when {
                st == "ssdp:all" -> types
                st in types -> listOf(st)
                else -> emptyList()
            }
            val loc = location() ?: continue
            for (t in answers) {
                val usn = if (t == udn) udn else "$udn::$t"
                val msg = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: $loc\r\n" +
                    "SERVER: Android/${android.os.Build.VERSION.RELEASE} UPnP/1.0 NovaIPTV/4.0\r\nST: $t\r\nUSN: $usn\r\n\r\n"
                val b = msg.toByteArray()
                runCatching { s.send(DatagramPacket(b, b.size, p.address, p.port)) }
            }
        }
    }

    private fun notifyAll(s: MulticastSocket, alive: Boolean) {
        val loc = location() ?: return
        val group = InetAddress.getByName(GROUP)
        for (t in types) {
            val usn = if (t == udn) udn else "$udn::$t"
            val msg = if (alive) "NOTIFY * HTTP/1.1\r\nHOST: $GROUP:$SSDP_PORT\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $loc\r\n" +
                "NT: $t\r\nNTS: ssdp:alive\r\nSERVER: Android UPnP/1.0 NovaIPTV/4.0\r\nUSN: $usn\r\n\r\n"
            else "NOTIFY * HTTP/1.1\r\nHOST: $GROUP:$SSDP_PORT\r\nNT: $t\r\nNTS: ssdp:byebye\r\nUSN: $usn\r\n\r\n"
            val b = msg.toByteArray()
            s.send(DatagramPacket(b, b.size, group, SSDP_PORT))
        }
    }

    // ------------------------------------------------------------------ HTTP: description, control, events

    /** Answers /dlna/… requests on Nova's local port. True = answered. */
    fun handle(method: String, path: String, headers: Map<String, String>, body: String, from: String, out: OutputStream): Boolean {
        if (!path.startsWith("/dlna/")) return false
        if (mode == "off") { write(out, 404, "text/plain", "off"); return true }
        when {
            method == "GET" && path == "/dlna/desc.xml" -> write(out, 200, "text/xml; charset=\"utf-8\"", description())
            method == "GET" && path == "/dlna/AVTransport.xml" -> write(out, 200, "text/xml; charset=\"utf-8\"", scpd(AVT_ACTIONS, AVT_VARS))
            method == "GET" && path == "/dlna/RenderingControl.xml" -> write(out, 200, "text/xml; charset=\"utf-8\"", scpd(RC_ACTIONS, RC_VARS))
            method == "GET" && path == "/dlna/ConnectionManager.xml" -> write(out, 200, "text/xml; charset=\"utf-8\"", scpd(CM_ACTIONS, CM_VARS))
            method == "SUBSCRIBE" -> subscribe(path, headers, out)
            method == "UNSUBSCRIBE" -> { headers["sid"]?.let { subs.remove(it) }; write(out, 200, "text/plain", "") }
            method == "POST" && path.startsWith("/dlna/ctl/") -> {
                val action = headers["soapaction"]?.trim('"')?.substringAfterLast('#')
                    ?: Regex("""<(?:\w+:)?(\w+)[^>]*xmlns(?::\w+)?="urn:schemas-upnp-org:service""").find(body)?.groupValues?.get(1).orEmpty()
                val service = path.removePrefix("/dlna/ctl/")
                val agent = headers["user-agent"].orEmpty()
                val result = runCatching { control(service, action, body, from, agent) }.getOrElse { Log.w(TAG, "$action: $it"); null }
                if (result == null) fault(out) else soap(out, service, action, result)
            }
            else -> write(out, 404, "text/plain", "not found")
        }
        return true
    }

    private fun arg(body: String, name: String): String =
        unescape(Regex("""<(?:\w+:)?$name(?:\s[^>]*)?>(.*?)</(?:\w+:)?$name>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty())

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)
    }

    private fun parseClock(s: String): Long? {
        val parts = s.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sec = parts[2].toDoubleOrNull() ?: return null
        return ((h * 3600 + m * 60) * 1000 + sec * 1000).toLong()
    }

    /** PLAYING / PAUSED_PLAYBACK / STOPPED / NO_MEDIA_PRESENT. */
    private fun state(): String {
        val n = Cast.now
        val ours = started != null && Cast.nowUrl == started
        return when {
            ours && n != null -> if (n.playing) "PLAYING" else "PAUSED_PLAYBACK"
            uri != null -> "STOPPED"
            else -> "NO_MEDIA_PRESENT"
        }
    }

    private fun audio() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The action's answer (name → value), or null = not supported (SOAP fault). */
    private fun control(service: String, action: String, body: String, from: String, agent: String): List<Pair<String, String>>? {
        val n = Cast.now
        val ours = started != null && Cast.nowUrl == started && n != null
        return when (action) {
            // ---- AVTransport
            "SetAVTransportURI" -> {
                uri = arg(body, "CurrentURI").trim()
                meta = arg(body, "CurrentURIMetaData")
                title = Regex("""<dc:title>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL).find(meta)?.groupValues?.get(1)?.let { unescape(it) }
                    ?.takeIf { it.isNotBlank() } ?: android.net.Uri.parse(uri).lastPathSegment ?: "Video"
                Log.i(TAG, "uri set: $title")
                // Already playing something of ours: switch to the new link at once.
                if (ours) startPlayback(from, agent)
                emptyList()
            }
            "SetNextAVTransportURI" -> emptyList()
            "Play" -> {
                if (uri != null && (!ours || Cast.nowUrl != uri)) startPlayback(from, agent) else Cast.commands.tryEmit("play")
                emptyList()
            }
            "Pause" -> { Cast.commands.tryEmit("pause"); emptyList() }
            "Stop" -> { if (ours) Cast.commands.tryEmit("stop"); started = null; emptyList() }
            "Seek" -> {
                val unit = arg(body, "Unit")
                val target = arg(body, "Target")
                if (unit == "REL_TIME" || unit == "ABS_TIME") parseClock(target)?.let { Cast.commands.tryEmit("seekto:$it") }
                emptyList()
            }
            "Next", "Previous" -> emptyList()
            "GetTransportInfo" -> listOf("CurrentTransportState" to state(), "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            "GetPositionInfo" -> {
                val dur = if (ours) n!!.dur else 0L
                val pos = if (ours) n!!.pos else 0L
                listOf(
                    "Track" to "1", "TrackDuration" to clock(dur), "TrackMetaData" to esc(meta), "TrackURI" to esc(uri.orEmpty()),
                    "RelTime" to clock(pos), "AbsTime" to clock(pos), "RelCount" to "2147483647", "AbsCount" to "2147483647",
                )
            }
            "GetMediaInfo" -> listOf(
                "NrTracks" to (if (uri != null) "1" else "0"), "MediaDuration" to clock(if (ours) n!!.dur else 0L),
                "CurrentURI" to esc(uri.orEmpty()), "CurrentURIMetaData" to esc(meta), "NextURI" to "", "NextURIMetaData" to "",
                "PlayMedium" to "NETWORK", "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED",
            )
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetDeviceCapabilities" -> listOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> listOf("Actions" to "Play,Pause,Stop,Seek")
            // ---- RenderingControl
            "GetVolume" -> {
                val a = audio(); val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                listOf("CurrentVolume" to (a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).toString())
            }
            "SetVolume" -> {
                val v = arg(body, "DesiredVolume").toIntOrNull() ?: 0
                val a = audio(); val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                a.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0, 100) * max + 50) / 100, 0)
                emptyList()
            }
            "GetMute" -> listOf("CurrentMute" to (if (android.os.Build.VERSION.SDK_INT >= 23 && audio().isStreamMute(AudioManager.STREAM_MUSIC)) "1" else "0"))
            "SetMute" -> {
                val mute = arg(body, "DesiredMute").let { it == "1" || it.equals("true", true) }
                if (android.os.Build.VERSION.SDK_INT >= 23) audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                emptyList()
            }
            "ListPresets" -> listOf("CurrentPresetNameList" to "FactoryDefaults")
            "SelectPreset" -> emptyList()
            // ---- ConnectionManager
            "GetProtocolInfo" -> listOf("Source" to "", "Sink" to "http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,http-get:*:*:*")
            "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> listOf(
                "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
            )
            else -> null
        }
    }

    private fun appName(agent: String, host: String): String {
        val known = listOf("BubbleUPnP", "Web Video Caster", "WebVideoCaster", "VLC", "Kodi", "LocalCast", "AllCast", "Windows", "Plex")
        return known.firstOrNull { agent.contains(it, true) } ?: agent.substringBefore('/').substringBefore(' ').takeIf { it.length in 3..24 } ?: host
    }

    private fun startPlayback(from: String, agent: String) {
        val url = uri ?: return
        val name = appName(agent, from)
        val ok = mode == "direct" || (allowed[from] ?: 0L) > System.currentTimeMillis()
        if (ok) play(url, name) else {
            asking.value = Ask(name, from, title, url)
            Handler(Looper.getMainLooper()).post {
                runCatching { app.startActivity(Intent(app, com.nova.iptv.ui.DlnaAskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    /** The TV said yes (or the 10 s passed): play, and let this app play directly for 3 hours. */
    fun accept(a: Ask) {
        asking.value = null
        allowed[a.host] = System.currentTimeMillis() + 3 * 60 * 60_000L
        play(a.url, a.from)
    }

    fun refuse() { asking.value = null }

    private fun play(url: String, from: String) {
        started = url
        val live = url.substringBefore('?').let { it.endsWith(".m3u8", true) && meta.contains("live", true) }
        Cast.playHere(JSONObject().put("url", url).put("title", title).put("live", live).put("type", if (live) ItemType.LIVE else ItemType.MOVIE)
            .put("id", url).put("acc", "dlna"), from)
    }

    // ------------------------------------------------------------------ events (apps subscribe to changes)

    private class Sub(val callback: String, val service: String, var seq: Int = 0, var last: String = "")
    private val subs = ConcurrentHashMap<String, Sub>()

    private fun subscribe(path: String, headers: Map<String, String>, out: OutputStream) {
        val sid = headers["sid"]?.takeIf { subs.containsKey(it) } ?: run {
            val cb = Regex("<([^>]+)>").find(headers["callback"].orEmpty())?.groupValues?.get(1) ?: return write(out, 412, "text/plain", "")
            "uuid:" + UUID.randomUUID().toString().also { subs["uuid:$it"] = Sub(cb, path.removePrefix("/dlna/evt/")) }
        }
        val o = StringBuilder("HTTP/1.1 200 OK\r\nSID: $sid\r\nTIMEOUT: Second-1800\r\nSERVER: Android UPnP/1.0 NovaIPTV/4.0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        out.write(o.toString().toByteArray()); out.flush()
        subs[sid]?.let { s -> scope.launch { delay(300); sendEvent(sid, s, force = true) } }
    }

    private fun lastChange(service: String): String {
        val inner = if (service == "AVTransport") {
            "<InstanceID val=\"0\"><TransportState val=\"${state()}\"/><CurrentTransportActions val=\"Play,Pause,Stop,Seek\"/>" +
                "<AVTransportURI val=\"${esc(uri.orEmpty())}\"/><CurrentTrackURI val=\"${esc(uri.orEmpty())}\"/></InstanceID>"
        } else {
            val a = audio(); val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            "<InstanceID val=\"0\"><Volume channel=\"Master\" val=\"${a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}\"/><Mute channel=\"Master\" val=\"0\"/></InstanceID>"
        }
        val ns = if (service == "AVTransport") "urn:schemas-upnp-org:metadata-1-0/AVT/" else "urn:schemas-upnp-org:metadata-1-0/RCS/"
        return "<Event xmlns=\"$ns\">$inner</Event>"
    }

    private fun sendEvent(sid: String, s: Sub, force: Boolean) {
        if (s.service == "ConnectionManager") {
            if (!force) return
        }
        val change = if (s.service == "ConnectionManager") "" else lastChange(s.service)
        if (!force && change == s.last) return
        s.last = change
        val prop = if (s.service == "ConnectionManager")
            "<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property><e:property><SinkProtocolInfo>http-get:*:*:*</SinkProtocolInfo></e:property><e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property>"
        else "<e:property><LastChange>${esc(change)}</LastChange></e:property>"
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">$prop</e:propertyset>"
        val bytes = xml.toByteArray()
        runCatching {
            val u = java.net.URL(s.callback)
            java.net.Socket().use { sock ->
                sock.connect(InetSocketAddress(u.host, if (u.port > 0) u.port else 80), 3000)
                sock.soTimeout = 3000
                val head = "NOTIFY ${u.file.ifBlank { "/" }} HTTP/1.1\r\nHOST: ${u.host}:${u.port}\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
                    "NT: upnp:event\r\nNTS: upnp:propchange\r\nSID: $sid\r\nSEQ: ${s.seq++}\r\nCONTENT-LENGTH: ${bytes.size}\r\nConnection: close\r\n\r\n"
                sock.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                runCatching { sock.getInputStream().read() }
            }
        }.onFailure { subs.remove(sid) }
    }

    private suspend fun eventLoop() {
        while (true) {
            delay(1000)
            if (subs.isEmpty()) continue
            subs.forEach { (sid, s) -> if (s.service != "ConnectionManager") sendEvent(sid, s, force = false) }
        }
    }

    // ------------------------------------------------------------------ XML

    private fun write(out: OutputStream, code: Int, type: String, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 412 -> "Precondition Failed"; 500 -> "Internal Server Error"; else -> "Not Found" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nEXT:\r\nSERVER: Android UPnP/1.0 NovaIPTV/4.0\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun soap(out: OutputStream, service: String, action: String, values: List<Pair<String, String>>) {
        val ns = "urn:schemas-upnp-org:service:$service:1"
        val inner = values.joinToString("") { (k, v) -> "<$k>$v</$k>" }
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:${action}Response xmlns:u=\"$ns\">$inner</u:${action}Response></s:Body></s:Envelope>"
        write(out, 200, "text/xml; charset=\"utf-8\"", xml)
    }

    private fun fault(out: OutputStream) {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
            "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
        write(out, 500, "text/xml; charset=\"utf-8\"", xml)
    }

    private fun description(): String {
        fun svc(type: String, name: String) = "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$name</serviceId>" +
            "<SCPDURL>/dlna/$name.xml</SCPDURL><controlURL>/dlna/ctl/$name</controlURL><eventSubURL>/dlna/evt/$name</eventSubURL></service>"
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><device><deviceType>$DEVICE</deviceType>" +
            "<friendlyName>${esc(friendlyName())}</friendlyName><manufacturer>Nova IPTV</manufacturer><modelName>Nova IPTV</modelName>" +
            "<modelNumber>4.0</modelNumber><modelDescription>Nova IPTV media renderer</modelDescription><UDN>$udn</UDN>" +
            "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>" +
            svc(AVT, "AVTransport") + svc(RC, "RenderingControl") + svc(CM, "ConnectionManager") +
            "</serviceList></device></root>"
    }

    /** Actions: name → list of (argument, direction, related variable). */
    private fun scpd(actions: Map<String, List<Triple<String, String, String>>>, vars: List<Triple<String, String, Boolean>>): String {
        val a = actions.entries.joinToString("") { (name, args) ->
            "<action><name>$name</name><argumentList>" + args.joinToString("") { (n, d, v) ->
                "<argument><name>$n</name><direction>$d</direction><relatedStateVariable>$v</relatedStateVariable></argument>"
            } + "</argumentList></action>"
        }
        val v = vars.joinToString("") { (n, t, ev) -> "<stateVariable sendEvents=\"${if (ev) "yes" else "no"}\"><name>$n</name><dataType>$t</dataType></stateVariable>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>$a</actionList><serviceStateTable>$v</serviceStateTable></scpd>"
    }

    private fun i(n: String, v: String) = Triple(n, "in", v)
    private fun o(n: String, v: String) = Triple(n, "out", v)
    private val ID = i("InstanceID", "A_ARG_TYPE_InstanceID")

    private val AVT_ACTIONS = linkedMapOf(
        "SetAVTransportURI" to listOf(ID, i("CurrentURI", "AVTransportURI"), i("CurrentURIMetaData", "AVTransportURIMetaData")),
        "SetNextAVTransportURI" to listOf(ID, i("NextURI", "NextAVTransportURI"), i("NextURIMetaData", "NextAVTransportURIMetaData")),
        "Play" to listOf(ID, i("Speed", "TransportPlaySpeed")),
        "Pause" to listOf(ID),
        "Stop" to listOf(ID),
        "Seek" to listOf(ID, i("Unit", "A_ARG_TYPE_SeekMode"), i("Target", "A_ARG_TYPE_SeekTarget")),
        "Next" to listOf(ID), "Previous" to listOf(ID),
        "GetTransportInfo" to listOf(ID, o("CurrentTransportState", "TransportState"), o("CurrentTransportStatus", "TransportStatus"), o("CurrentSpeed", "TransportPlaySpeed")),
        "GetPositionInfo" to listOf(ID, o("Track", "CurrentTrack"), o("TrackDuration", "CurrentTrackDuration"), o("TrackMetaData", "CurrentTrackMetaData"),
            o("TrackURI", "CurrentTrackURI"), o("RelTime", "RelativeTimePosition"), o("AbsTime", "AbsoluteTimePosition"),
            o("RelCount", "RelativeCounterPosition"), o("AbsCount", "AbsoluteCounterPosition")),
        "GetMediaInfo" to listOf(ID, o("NrTracks", "NumberOfTracks"), o("MediaDuration", "CurrentMediaDuration"), o("CurrentURI", "AVTransportURI"),
            o("CurrentURIMetaData", "AVTransportURIMetaData"), o("NextURI", "NextAVTransportURI"), o("NextURIMetaData", "NextAVTransportURIMetaData"),
            o("PlayMedium", "PlaybackStorageMedium"), o("RecordMedium", "RecordStorageMedium"), o("WriteStatus", "RecordMediumWriteStatus")),
        "GetTransportSettings" to listOf(ID, o("PlayMode", "CurrentPlayMode"), o("RecQualityMode", "CurrentRecordQualityMode")),
        "GetDeviceCapabilities" to listOf(ID, o("PlayMedia", "PossiblePlaybackStorageMedia"), o("RecMedia", "PossibleRecordStorageMedia"), o("RecQualityModes", "PossibleRecordQualityModes")),
        "GetCurrentTransportActions" to listOf(ID, o("Actions", "CurrentTransportActions")),
    )
    private val AVT_VARS = listOf(
        Triple("LastChange", "string", true), Triple("A_ARG_TYPE_InstanceID", "ui4", false), Triple("AVTransportURI", "string", false),
        Triple("AVTransportURIMetaData", "string", false), Triple("NextAVTransportURI", "string", false), Triple("NextAVTransportURIMetaData", "string", false),
        Triple("TransportPlaySpeed", "string", false), Triple("A_ARG_TYPE_SeekMode", "string", false), Triple("A_ARG_TYPE_SeekTarget", "string", false),
        Triple("TransportState", "string", false), Triple("TransportStatus", "string", false), Triple("CurrentTrack", "ui4", false),
        Triple("CurrentTrackDuration", "string", false), Triple("CurrentTrackMetaData", "string", false), Triple("CurrentTrackURI", "string", false),
        Triple("RelativeTimePosition", "string", false), Triple("AbsoluteTimePosition", "string", false), Triple("RelativeCounterPosition", "i4", false),
        Triple("AbsoluteCounterPosition", "i4", false), Triple("NumberOfTracks", "ui4", false), Triple("CurrentMediaDuration", "string", false),
        Triple("PlaybackStorageMedium", "string", false), Triple("RecordStorageMedium", "string", false), Triple("RecordMediumWriteStatus", "string", false),
        Triple("CurrentPlayMode", "string", false), Triple("CurrentRecordQualityMode", "string", false), Triple("PossiblePlaybackStorageMedia", "string", false),
        Triple("PossibleRecordStorageMedia", "string", false), Triple("PossibleRecordQualityModes", "string", false), Triple("CurrentTransportActions", "string", false),
    )
    private val RC_ACTIONS = linkedMapOf(
        "GetVolume" to listOf(ID, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentVolume", "Volume")),
        "SetVolume" to listOf(ID, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredVolume", "Volume")),
        "GetMute" to listOf(ID, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentMute", "Mute")),
        "SetMute" to listOf(ID, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredMute", "Mute")),
        "ListPresets" to listOf(ID, o("CurrentPresetNameList", "PresetNameList")),
        "SelectPreset" to listOf(ID, i("PresetName", "A_ARG_TYPE_PresetName")),
    )
    private val RC_VARS = listOf(
        Triple("LastChange", "string", true), Triple("A_ARG_TYPE_InstanceID", "ui4", false), Triple("A_ARG_TYPE_Channel", "string", false),
        Triple("Volume", "ui2", false), Triple("Mute", "boolean", false), Triple("PresetNameList", "string", false), Triple("A_ARG_TYPE_PresetName", "string", false),
    )
    private val CM_ACTIONS = linkedMapOf(
        "GetProtocolInfo" to listOf(o("Source", "SourceProtocolInfo"), o("Sink", "SinkProtocolInfo")),
        "GetCurrentConnectionIDs" to listOf(o("ConnectionIDs", "CurrentConnectionIDs")),
        "GetCurrentConnectionInfo" to listOf(i("ConnectionID", "A_ARG_TYPE_ConnectionID"), o("RcsID", "A_ARG_TYPE_RcsID"), o("AVTransportID", "A_ARG_TYPE_AVTransportID"),
            o("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), o("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"), o("PeerConnectionID", "A_ARG_TYPE_ConnectionID"),
            o("Direction", "A_ARG_TYPE_Direction"), o("Status", "A_ARG_TYPE_ConnectionStatus")),
    )
    private val CM_VARS = listOf(
        Triple("SourceProtocolInfo", "string", true), Triple("SinkProtocolInfo", "string", true), Triple("CurrentConnectionIDs", "string", true),
        Triple("A_ARG_TYPE_ConnectionStatus", "string", false), Triple("A_ARG_TYPE_ConnectionManager", "string", false), Triple("A_ARG_TYPE_Direction", "string", false),
        Triple("A_ARG_TYPE_ProtocolInfo", "string", false), Triple("A_ARG_TYPE_ConnectionID", "i4", false), Triple("A_ARG_TYPE_AVTransportID", "i4", false),
        Triple("A_ARG_TYPE_RcsID", "i4", false),
    )
}
