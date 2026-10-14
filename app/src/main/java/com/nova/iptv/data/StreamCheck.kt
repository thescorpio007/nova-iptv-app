package com.nova.iptv.data

import android.util.Log
import com.nova.iptv.NovaApp
import com.nova.iptv.download.DownloadCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Status dots: checks quietly, one at a time, whether the channels / films / episodes on screen answer.
 *  - green  = the stream answers (or played fine here);
 *  - red    = no signal / not found / server error;
 *  - orange = the stream works but THIS device couldn't play its format (learned from real playback);
 *  - grey   = not checked yet (or waiting for a free connection).
 * Only what is open on screen is checked; results are kept 6 hours (format problems longer).
 * The server's connection limit is respected: nothing is checked on a server whose connections are all in use
 * (playing or downloading), and a running check is stopped at once when playback starts on that server.
 */
object StreamCheck {

    enum class State { OK, BAD, DEVICE, CHECKING, WAITING, UNKNOWN }

    data class Status(val state: State, val note: String? = null)

    data class Target(val key: String, val url: String, val accountId: String, val live: Boolean)

    private data class Net(val ok: Boolean, val at: Long, val note: String?)

    private const val FRESH_MS = 6 * 3600_000L
    private const val DEVICE_MS = 30L * 24 * 3600_000L

    private val net = ConcurrentHashMap<String, Net>()
    private val device = ConcurrentHashMap<String, Pair<Long, String>>()
    private val waiting = ConcurrentHashMap<String, String>()

    private val _version = MutableStateFlow(0)
    /** Changes whenever a dot changes (screens redraw). */
    val version: StateFlow<Int> = _version

    @Volatile private var checkingKey: String? = null
    @Volatile private var checkingAccount: String? = null
    @Volatile private var call: Call? = null
    /** What the main screen shows, and what the player's channel list shows (that one wins while open). */
    @Volatile private var mainQueue: List<Target> = emptyList()
    @Volatile private var playerQueue: List<Target>? = null
    private val queue: List<Target> get() = playerQueue ?: mainQueue
    private val forced = ConcurrentHashMap.newKeySet<String>()
    /** Account → time until which nothing is checked there (the server said "busy"). */
    private val backOff = ConcurrentHashMap<String, Long>()

    /** The account the player is using right now (null = nothing playing). */
    @Volatile var playingAccount: String? = null
        private set

    private lateinit var app: NovaApp
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    @Volatile private var dirty = false

    private val client: OkHttpClient by lazy {
        app.http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun key(type: String, accountId: String, id: String) = "$type|$accountId|$id"

    fun init(app: NovaApp) {
        if (started) return
        started = true
        this.app = app
        file = File(app.filesDir, "stream-status.json")
        runCatching { load() }
        scope.launch { worker() }
        scope.launch {
            while (true) {
                delay(5000)
                if (dirty) { dirty = false; runCatching { save() } }
            }
        }
    }

    private fun bump() { _version.value = _version.value + 1; dirty = true }

    val enabled: Boolean get() = ::app.isInitialized && app.storage.statusDots

    /** The dot of one item. */
    fun status(key: String): Status {
        if (key == checkingKey) return Status(State.CHECKING)
        val now = System.currentTimeMillis()
        val n = net[key]?.takeIf { now - it.at < FRESH_MS }
        if (n != null && !n.ok) return Status(State.BAD, n.note)
        device[key]?.takeIf { now - it.first < DEVICE_MS }?.let { return Status(State.DEVICE, it.second) }
        if (n != null) return Status(State.OK)
        waiting[key]?.let { return Status(State.WAITING, it) }
        return Status(State.UNKNOWN)
    }

    /** "Checked 9 / 18" for a list on screen: (checked, total, still checking). */
    fun progress(keys: List<String>): Triple<Int, Int, Boolean> {
        val now = System.currentTimeMillis()
        val done = keys.count { k -> net[k]?.let { now - it.at < FRESH_MS } == true || device.containsKey(k) }
        val busy = keys.any { it == checkingKey } || queue.any { t -> t.key in keys && !waiting.containsKey(t.key) && needs(t, now) }
        return Triple(done, keys.size, busy)
    }

    /** What is on screen now: only these are checked (the previous list is dropped). */
    fun want(targets: List<Target>, fromPlayer: Boolean = false) {
        if (!enabled) return
        val list = targets.distinctBy { it.key }.take(300)
        if (fromPlayer) playerQueue = list else mainQueue = list
        waiting.keys.retainAll(queue.map { it.key }.toSet())
    }

    /** The player closed: back to checking the main screen's list. */
    fun playerClosed() { playerQueue = null }

    /** Keys of the main screen's list (for "Checked 9 / 18"). */
    fun mainKeys(): List<String> = mainQueue.map { it.key }

    /** "⟳ Test again": check these again even when recently checked. */
    fun retest(targets: List<Target>, fromPlayer: Boolean = false, olderThanMs: Long = 0L) {
        val now = System.currentTimeMillis()
        // The old dots stay until each item has been checked again.
        targets.forEach { t -> if (net[t.key]?.let { now - it.at >= olderThanMs } != false) forced += t.key }
        bump()
        want(targets, fromPlayer)
    }

    fun retestMain() = retest(mainQueue)

    private fun needs(t: Target, now: Long): Boolean =
        t.key in forced || net[t.key]?.let { now - it.at >= FRESH_MS } ?: !device.containsKey(t.key)

    // ------------------------------------------------------------ results from real playback

    /** Playback starts on this account (null = stopped): a check running there is stopped at once. */
    fun playing(accountId: String?) {
        playingAccount = accountId?.ifBlank { null }
        // Nothing is checked while something plays (a check could disturb the picture): stop a running one.
        if (accountId != null) runCatching { call?.cancel() }
    }

    /** The item played (picture or sound came): green. Clears a format problem when the video decoded. */
    fun played(key: String, videoDecoded: Boolean) {
        if (!started || key.isBlank()) return
        net[key] = Net(true, System.currentTimeMillis(), null)
        if (videoDecoded) device.remove(key)
        bump()
    }

    /** The item didn't play because of the connection / server: red. */
    fun failed(key: String, note: String) {
        if (!started || key.isBlank()) return
        net[key] = Net(false, System.currentTimeMillis(), note)
        bump()
    }

    /** The stream came but this device couldn't decode it: orange. */
    fun deviceFailed(key: String, note: String) {
        if (!started || key.isBlank()) return
        device[key] = System.currentTimeMillis() to note
        net[key] = Net(true, System.currentTimeMillis(), null)
        bump()
    }

    // ------------------------------------------------------------ the background checker

    /** Connections Nova itself uses on this account now (playing + downloading). */
    private fun usedOn(accountId: String): Int =
        (if (playingAccount == accountId) 1 else 0) + DownloadCenter.items.value.count {
            it.status == DownloadStatus.DOWNLOADING && DownloadCenter.accountOf(it.url)?.id == accountId
        }

    private fun connectionsFree(accountId: String): Pair<Boolean, Int> {
        val allowed = app.storage.maxConnections(accountId).takeIf { it > 0 } ?: 1
        return (usedOn(accountId) + 1 <= allowed) to allowed
    }

    private suspend fun worker() {
        while (true) {
            val now = System.currentTimeMillis()
            // Never while the player is open: live channels and films play undisturbed.
            val q = if (enabled && playingAccount == null) queue.filter { !it.live } else emptyList()
            var picked: Target? = null
            var waitChanged = false
            for (t in q) {
                if (!needs(t, now)) continue
                if ((backOff[t.accountId] ?: 0L) > now) {
                    if (waiting.put(t.key, "server busy — later") == null) waitChanged = true
                    continue
                }
                val (free, allowed) = connectionsFree(t.accountId)
                if (!free) {
                    val note = if (allowed == 1) "waits: 1 connection" else "waits: connections in use"
                    if (waiting.put(t.key, note) == null) waitChanged = true
                    continue
                }
                if (waiting.remove(t.key) != null) waitChanged = true
                picked = t
                break
            }
            if (waitChanged) _version.value = _version.value + 1
            if (picked == null) { delay(1500); continue }
            checkingKey = picked.key
            checkingAccount = picked.accountId
            _version.value = _version.value + 1
            val result = runCatching { probe(picked) }.getOrElse { e -> Result.Bad(errorNote(e)) }
            checkingKey = null
            checkingAccount = null
            forced -= picked.key
            when (result) {
                is Result.Ok -> net[picked.key] = Net(true, System.currentTimeMillis(), null)
                is Result.Bad -> net[picked.key] = Net(false, System.currentTimeMillis(), result.note)
                is Result.Busy -> {
                    backOff[picked.accountId] = System.currentTimeMillis() + 60_000
                    waiting[picked.key] = "server busy — later"
                }
                Result.Stopped -> Unit // playback took the connection: checked again later
            }
            Log.i("NovaTest", "CHECK ${picked.key.substringBefore('|')} ${picked.key.substringAfterLast('|')}: $result")
            bump()
            // A short pause: never hammer the server.
            delay(if (picked.live) 400 else 250)
        }
    }

    private sealed class Result {
        object Ok : Result() { override fun toString() = "ok" }
        data class Bad(val note: String) : Result()
        object Busy : Result() { override fun toString() = "busy" }
        object Stopped : Result() { override fun toString() = "stopped" }
    }

    private fun errorNote(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "server not found"
        is java.net.ConnectException -> "server not answering"
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "no signal"
        else -> "no answer"
    }

    private fun request(url: String, range: Boolean): Request = Request.Builder().url(url).apply {
        app.storage.userAgent.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
        if (range) header("Range", "bytes=0-65535")
    }.build()

    private fun probe(t: Target): Result {
        val isHls = t.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val c = client.newCall(request(t.url, range = !t.live))
        call = c
        try {
            c.execute().use { r ->
                when (r.code) {
                    429, 456, 458, 509 -> return Result.Busy
                    // Refused while Nova uses this server too: probably its connection limit, not the channel.
                    401, 403 -> return if (usedOn(t.accountId) > 0) Result.Busy else Result.Bad("access refused")
                    404, 410 -> return Result.Bad("not found")
                }
                if (r.code >= 500) return Result.Bad("server error")
                if (!r.isSuccessful) return Result.Bad("server answered ${r.code}")
                val type = r.header("Content-Type").orEmpty().lowercase()
                val body = r.body ?: return Result.Bad("no signal")
                if (isHls) {
                    val src = body.source()
                    src.request(65536L)
                    val text = src.buffer.readUtf8()
                    if (!text.contains("#EXTM3U")) return Result.Bad("server error")
                    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?: return Result.Bad("no signal")
                    val next = r.request.url.resolve(line)?.toString() ?: return Result.Bad("no signal")
                    return probe(t.copy(url = next, live = false))
                }
                if (type.startsWith("text/html")) return Result.Bad("server error")
                // Read the first pieces: a live channel must keep sending, a film must have data.
                val need = if (t.live) 48 * 1024L else 1024L
                val source = body.source()
                val buf = okio.Buffer()
                var got = 0L
                val until = System.currentTimeMillis() + 6000
                while (got < need && System.currentTimeMillis() < until) {
                    val n = source.read(buf, 16 * 1024L)
                    if (n < 0) break
                    got += n
                    buf.clear()
                }
                return if (got >= need) Result.Ok else Result.Bad("no signal")
            }
        } catch (e: java.io.IOException) {
            if (c.isCanceled()) return Result.Stopped
            throw e
        } finally {
            call = null
        }
    }

    // ------------------------------------------------------------ saved on the device

    private fun load() {
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        val now = System.currentTimeMillis()
        o.optJSONObject("net")?.let { n ->
            n.keys().forEach { k ->
                val a = n.getJSONArray(k)
                val at = a.getLong(1)
                if (now - at < FRESH_MS) net[k] = Net(a.getBoolean(0), at, a.optString(2).ifBlank { null })
            }
        }
        o.optJSONObject("device")?.let { d ->
            d.keys().forEach { k ->
                val a = d.getJSONArray(k)
                val at = a.getLong(0)
                if (now - at < DEVICE_MS) device[k] = at to a.optString(1)
            }
        }
    }

    private fun save() {
        val now = System.currentTimeMillis()
        val n = JSONObject()
        net.forEach { (k, v) -> if (now - v.at < FRESH_MS) n.put(k, org.json.JSONArray().put(v.ok).put(v.at).put(v.note ?: "")) }
        val d = JSONObject()
        device.forEach { (k, v) -> d.put(k, org.json.JSONArray().put(v.first).put(v.second)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("net", n).put("device", d).toString())
        tmp.renameTo(file)
    }
}
