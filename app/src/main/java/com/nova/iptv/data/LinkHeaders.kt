package com.nova.iptv.data

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Websites that only answer "their own player": the extra details they need (Referer = the website's
 * address, User-Agent = a browser's name) are remembered per site and added to every request to it.
 *  - typed in "Open link → Advanced", or passed along by the app that shared the link (MX Player / VLC style);
 *  - or found by Nova: when a site refuses (401 / 403), the request is retried once as a normal web browser,
 *    and if that works it is remembered for that site.
 * Your IPTV servers are never touched.
 */
class LinkHeadersInterceptor(private val storage: () -> Storage?) : Interceptor {
    companion object {
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Ugoos AM8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
    }

    private var accountHosts: Set<String> = emptySet()
    private var hostsAt = 0L

    private fun isAccountHost(s: Storage, host: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - hostsAt > 30_000) {
            accountHosts = s.accounts.mapNotNull { a -> runCatching { java.net.URI(XtreamApi.normalizeServer(a.server)).host }.getOrNull() }.toSet()
            hostsAt = now
        }
        return host in accountHosts
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val s = storage() ?: return chain.proceed(req)
        val host = req.url.host
        if (isAccountHost(s, host)) return chain.proceed(req)
        val saved = s.linkHeaders(host)
        val first = if (saved.isEmpty()) req else req.newBuilder().apply { saved.forEach { (k, v) -> header(k, v) } }.build()
        val resp = chain.proceed(first)
        if (resp.code != 401 && resp.code != 403) return resp
        if (saved["User-Agent"] == BROWSER_UA && saved.containsKey("Referer")) return resp // already tried as a browser
        resp.close()
        val origin = "${req.url.scheme}://$host"
        val referer = saved["Referer"] ?: "$origin/"
        val retry = first.newBuilder()
            .header("User-Agent", saved["User-Agent"] ?: BROWSER_UA)
            .header("Referer", referer)
            .header("Origin", runCatching { java.net.URI(referer).let { "${it.scheme}://${it.host}" } }.getOrDefault(origin))
            .header("Accept", "*/*")
            .build()
        val second = chain.proceed(retry)
        if (second.isSuccessful) {
            Log.i("NovaTest", "LINK headers: $host answers as a web browser (remembered)")
            s.setLinkHeaders(host, saved + ("User-Agent" to (saved["User-Agent"] ?: BROWSER_UA)) + ("Referer" to referer))
        }
        return second
    }
}
