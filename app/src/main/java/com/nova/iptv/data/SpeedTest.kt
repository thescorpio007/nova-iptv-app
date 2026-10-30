package com.nova.iptv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ⚡ Speed test: downloads a film of your own account for 15 s (nothing is shown or kept) and measures the real speed,
 * with or without a VPN — to compare VPN servers, or to see that a film's server is blocked without one.
 */
object SpeedTest {
    data class Result(val name: String, val mbps: Double, val startMs: Long, val at: Long, val error: String?, val vpn: Boolean)

    val results = MutableStateFlow<List<Result>>(emptyList())
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("nova_speedtest", Context.MODE_PRIVATE)
        val a = runCatching { JSONArray(prefs?.getString("results", "[]") ?: "[]") }.getOrDefault(JSONArray())
        results.value = (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let {
                Result(it.optString("name"), it.optDouble("mbps"), it.optLong("start"), it.optLong("at"),
                    it.optString("error").ifBlank { null }, it.optBoolean("vpn"))
            }
        }
    }

    private fun save(list: List<Result>) {
        results.value = list
        prefs?.edit()?.putString("results", JSONArray(list.map {
            JSONObject().put("name", it.name).put("mbps", it.mbps).put("start", it.startMs).put("at", it.at)
                .put("error", it.error ?: "").put("vpn", it.vpn)
        }).toString())?.apply()
    }

    fun remove(r: Result) = save(results.value - r)
    fun clear() = save(emptyList())

    /** Best first; failures last. */
    fun ranked(): List<Result> = results.value.sortedWith(compareBy<Result> { it.error != null }.thenByDescending { it.mbps })

    /** Runs the test; [progress] gets the seconds done. */
    suspend fun run(http: OkHttpClient, userAgent: String, url: String, name: String, vpn: Boolean, seconds: Int = 15, progress: (Int) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val client = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
            val t0 = System.currentTimeMillis()
            var bytes = 0L
            var start = 0L
            val error: String? = try {
                client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { r ->
                    start = System.currentTimeMillis() - t0
                    if (!r.isSuccessful) "the server answered HTTP ${r.code}"
                    else {
                        val ins = r.body?.byteStream() ?: return@use "empty answer"
                        val buf = ByteArray(64 * 1024)
                        val tStart = System.currentTimeMillis()
                        var lastSec = -1
                        while (isActive) {
                            val el = System.currentTimeMillis() - tStart
                            if (el >= seconds * 1000L) break
                            val n = ins.read(buf)
                            if (n < 0) break
                            bytes += n
                            val sec = (el / 1000).toInt()
                            if (sec != lastSec) { lastSec = sec; withContext(Dispatchers.Main) { progress(sec) } }
                        }
                        null
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                "blocked — the film's server can't be reached"
            } catch (e: java.net.ConnectException) {
                "blocked — the film's server can't be reached"
            } catch (e: java.net.SocketTimeoutException) {
                if (bytes == 0L) "blocked — no answer from the film's server" else null
            } catch (e: javax.net.ssl.SSLException) {
                "blocked — the secure connection was cut"
            } catch (e: java.io.IOException) {
                if (bytes == 0L) "connection cut (${e.message ?: "no answer"})" else null
            }
            val elapsed = (System.currentTimeMillis() - t0 - start).coerceAtLeast(1)
            val mbps = if (error == null) bytes * 8.0 / elapsed / 1000.0 else 0.0
            val res = Result(name.ifBlank { if (vpn) "VPN" else "No VPN" }, mbps, start, System.currentTimeMillis(), error, vpn)
            withContext(Dispatchers.Main) { save(results.value + res) }
            res
        }

    /** The connection itself failed (address not found, refused, cut, no answer): typical of a blocked server. */
    fun isNetworkBlock(t: Throwable?): Boolean = generateSequence(t) { it.cause }.any {
        it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.NoRouteToHostException ||
            it is javax.net.ssl.SSLException || (it is java.net.SocketTimeoutException) ||
            (it is java.net.SocketException && it.message?.contains("reset", true) == true)
    }
}
