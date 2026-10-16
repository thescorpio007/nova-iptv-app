package com.nova.iptv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Ad & tracker blocking for the Web tab: well-known ad / tracker / pop-up domains (a built-in list plus the large
 * community hosts list, ~100 000 domains, updated weekly). A request to one of them (or a sub-domain) is never sent.
 */
object AdBlock {
    private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val WEEK = 7L * 24 * 3600_000

    /** Always blocked: the big ad networks and the pop-up / pop-under networks free film sites use. */
    private val BUILT_IN = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "google-analytics.com",
        "googletagservices.com", "googletagmanager.com", "adnxs.com", "adsrvr.org", "advertising.com", "taboola.com",
        "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com",
        "amazon-adsystem.com", "moatads.com", "scorecardresearch.com", "quantserve.com", "zedo.com", "yieldmo.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adsterra.com", "adsterratech.com",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "trafficstars.com", "hilltopads.net",
        "clickadu.com", "adcash.com", "adskeeper.com", "mgid.com", "revcontent.com", "onclickads.net", "onclkds.com",
        "popmyads.com", "a-ads.com", "bidvertiser.com", "admaven.com", "ad-maven.com", "galaksion.com", "monetag.com",
        "richpush.co", "pushame.com", "evadav.com", "rollerads.com", "pushground.com", "realsrv.com", "tsyndicate.com",
        "dtscout.com", "dtscdn.com", "histats.com", "yandexadexchange.net", "adform.net", "smartadserver.com",
        "inmobi.com", "applovin.com", "unityads.unity3d.com", "chartboost.com", "vungle.com", "adcolony.com",
        "onesignal.com", "pushcrew.com", "notix.io", "aclib.net", "acint.net", "jads.co", "adspyglass.com",
        "displayvertising.com", "highperformanceformat.com", "profitableratecpm.com", "effectiveratecpm.com",
        "toprevenuegate.com", "upskittyan.com", "whos.amung.us", "cloudfront-ads.net", "betteradsystem.com",
    )

    private val WS = Regex("\\s+")
    @Volatile private var hosts: Set<String> = BUILT_IN
    @Volatile var listSize = BUILT_IN.size; private set
    @Volatile var updatedAt = 0L; private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun init(context: Context, http: OkHttpClient) {
        if (started) return
        started = true
        val file = File(context.filesDir, "adblock-hosts.txt")
        scope.launch {
            if (file.exists()) load(file)
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > WEEK) update(http, file)
        }
    }

    private fun load(file: File) {
        runCatching {
            val set = HashSet<String>(140_000)
            set.addAll(BUILT_IN)
            file.forEachLine { line ->
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) return@forEachLine
                val parts = l.split(WS)
                val h = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
                if (h.contains('.') && h != "localhost" && h != "0.0.0.0") set += h
            }
            hosts = set
            listSize = set.size
            updatedAt = file.lastModified()
            Log.i("NovaTest", "ADBLOCK list: ${set.size} domains")
        }
    }

    private fun update(http: OkHttpClient, file: File) {
        runCatching {
            http.newCall(Request.Builder().url(LIST_URL).build()).execute().use { r ->
                if (!r.isSuccessful) return
                val tmp = File(file.parentFile, file.name + ".tmp")
                r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() > 100_000) { tmp.renameTo(file); load(file) } else tmp.delete()
            }
        }.onFailure { Log.i("NovaTest", "ADBLOCK update failed: $it") }
    }

    /** True for an ad / tracker domain (or one of its sub-domains). */
    fun isAd(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        var h = host.lowercase()
        val set = hosts
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** "site.example.co.uk" → "example.co.uk" (good enough to tell "another site" from "the same site"). */
    fun siteOf(host: String?): String {
        val parts = host.orEmpty().lowercase().split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val twoLevel = parts[parts.size - 2].length <= 3 && parts.last().length == 2
        return parts.takeLast(if (twoLevel) 3 else 2).joinToString(".")
    }

    /** Hides the usual ad boxes left on a page (applied after the page loads). */
    const val HIDE_CSS = "ins.adsbygoogle,[id^='google_ads'],[id^='div-gpt-ad'],[class*='adsbox'],[class*=' ad-'],[class^='ad-']," +
        "[class*='banner-ad'],[id*='banner-ad'],[class*='sponsor'],[id*='popunder'],[class*='popunder']," +
        "iframe[src*='ads'],iframe[src*='doubleclick'],iframe[src*='exoclick'],a[href*='/ads/'],div[id^='ad_'],div[class^='ad_']" +
        "{display:none!important;visibility:hidden!important;}"
}
