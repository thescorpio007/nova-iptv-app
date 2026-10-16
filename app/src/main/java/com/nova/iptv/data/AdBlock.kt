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
    /** Extra lists: "max" (Maximum level) and "adult" (Kids protection) — hagezi DNS lists, domain per line. */
    private const val MAX_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt"
    private val ADULT_URLS = listOf(
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/nsfw.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/gambling.txt",
    )

    @Volatile private var hosts: Set<String> = BUILT_IN
    @Volatile private var maxHosts: Set<String> = emptySet()
    @Volatile private var adultHosts: Set<String> = emptySet()
    @Volatile var listSize = BUILT_IN.size; private set
    @Volatile var maxSize = 0; private set
    @Volatile var adultSize = 0; private set
    @Volatile var updatedAt = 0L; private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private lateinit var dir: File
    private lateinit var client: OkHttpClient
    private var maxLoading = false
    private var adultLoading = false

    /** Adult / gambling words in a web address (Kids protection). */
    private val ADULT_WORDS = Regex(
        """(?i)(porn|xxx|xvideo|xnxx|xhamster|redtube|youporn|hentai|nsfw|onlyfans|camgirl|livejasmin|chaturbate|stripchat|""" +
            """brazzers|erotic|nude|naked|sex|escort|milf|casino|1xbet|betting|poker|roulette)""",
    )

    fun init(context: Context, http: OkHttpClient) {
        if (started) return
        started = true
        dir = context.filesDir
        client = http
        val file = File(dir, "adblock-hosts.txt")
        scope.launch {
            if (file.exists()) hosts = load(listOf(file), BUILT_IN).also { listSize = it.size; updatedAt = file.lastModified() }
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > WEEK) {
                if (download(LIST_URL, file)) hosts = load(listOf(file), BUILT_IN).also { listSize = it.size; updatedAt = file.lastModified() }
            }
        }
    }

    /** Loads (and updates weekly) the extra lists the settings need: Maximum level, Kids protection. */
    fun ensure(level: String, kids: String) {
        if (!::dir.isInitialized) return
        if (level == "maximum" && !maxLoading) {
            maxLoading = true
            scope.launch {
                val f = File(dir, "adblock-max.txt")
                if (!f.exists() || System.currentTimeMillis() - f.lastModified() > WEEK) download(MAX_URL, f)
                if (f.exists()) maxHosts = load(listOf(f), emptySet()).also { maxSize = it.size }
            }
        }
        if (kids != "off" && !adultLoading) {
            adultLoading = true
            scope.launch {
                val files = ADULT_URLS.mapIndexed { i, u ->
                    File(dir, "adult-$i.txt").also { f -> if (!f.exists() || System.currentTimeMillis() - f.lastModified() > WEEK) download(u, f) }
                }.filter { it.exists() }
                adultHosts = load(files, emptySet()).also { adultSize = it.size }
            }
        }
    }

    private fun load(files: List<File>, base: Set<String>): Set<String> {
        val set = HashSet<String>(200_000)
        set.addAll(base)
        files.forEach { file ->
            runCatching {
                file.forEachLine { line ->
                    val l = line.trim()
                    if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) return@forEachLine
                    val parts = l.split(WS)
                    val h = (if (parts.size >= 2) parts[1] else parts[0]).lowercase().trimStart('*', '.')
                    if (h.contains('.') && h != "localhost" && h != "0.0.0.0") set += h
                }
            }
        }
        Log.i("NovaTest", "ADBLOCK loaded ${files.joinToString { it.name }}: ${set.size} domains")
        return set
    }

    private fun download(url: String, file: File): Boolean = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@use false
            val tmp = File(file.parentFile, file.name + ".tmp")
            r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() > 20_000) { tmp.renameTo(file); true } else { tmp.delete(); false }
        }
    }.getOrElse { Log.i("NovaTest", "ADBLOCK update failed: $it"); false }

    private fun inSet(host: String?, set: Set<String>): Boolean {
        if (host.isNullOrBlank() || set.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** An ad / tracker domain for this level ("normal"/"strong": the main list; "maximum": + the big list). */
    fun isAd(host: String?, level: String): Boolean =
        level != "off" && (inSet(host, hosts) || (level == "maximum" && inSet(host, maxHosts)))

    /** Kids protection: an adult or gambling site (list or words in the address). */
    fun isAdult(host: String?, url: String): Boolean = inSet(host, adultHosts) || ADULT_WORDS.containsMatchIn(url)

    /** "site.example.co.uk" → "example.co.uk" (good enough to tell "another site" from "the same site"). */
    fun siteOf(host: String?): String {
        val parts = host.orEmpty().lowercase().split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val twoLevel = parts[parts.size - 2].length <= 3 && parts.last().length == 2
        return parts.takeLast(if (twoLevel) 3 else 2).joinToString(".")
    }

    /**
     * Maximum level: stops the tricks film sites use — window.open replaced, transparent click-catchers and
     * full-screen ad layers removed (checked again every 2 s).
     */
    const val MAX_JS = "(function(){try{window.open=function(){return null};}catch(e){}" +
        "function clean(){var w=innerWidth,h=innerHeight;document.querySelectorAll('body *').forEach(function(e){" +
        "var s=getComputedStyle(e);if((s.position=='fixed'||s.position=='absolute')&&parseInt(s.zIndex||0)>999){" +
        "var r=e.getBoundingClientRect();if(r.width>w*0.6&&r.height>h*0.6&&!e.querySelector('video')&&e.tagName!='VIDEO'){" +
        "var t=(e.innerText||'').trim();if(t.length<40||e.tagName=='A'||e.tagName=='IFRAME'){e.remove();}}}});}" +
        "clean();setInterval(clean,2000);})();"

    /**
     * Runs at the start of the page AND of every embedded frame (video players included), through the NovaFilters
     * bridge: the filter lists' "hide" rules for that site, and at Maximum the clean-up (window.open stopped, full-screen
     * click-catchers and boxes laid over the video — "Click here", links to other sites, ad frames — removed every 2 s).
     */
    const val FRAME_JS = "(function(){try{if(window.__nova)return;window.__nova=1;var N=window.NovaFilters;if(!N)return;" +
        "var lv=N.level();if(lv=='off')return;var css=N.css(location.hostname);" +
        "function add(){var s=document.createElement('style');s.textContent=css+'\\n'+N.base();(document.head||document.documentElement).appendChild(s);}" +
        "if(document.documentElement)add();else document.addEventListener('DOMContentLoaded',add);" +
        "if(lv!='maximum')return;try{window.open=function(){return null};}catch(e){}" +
        "var AD=/click here|cliquez|clique ici|install now|download app|sponsor|publicit|advertis|ads by|\\bad\\b|annonce/i;" +
        "function clean(){if(!document.body)return;var n=0,w=innerWidth,h=innerHeight,vs=[].slice.call(document.querySelectorAll('video')).filter(function(v){var r=v.getBoundingClientRect();return r.width>120&&r.height>60;});" +
        "document.querySelectorAll('body *').forEach(function(e){if(!e.isConnected||e.tagName=='VIDEO'||e.querySelector('video'))return;var s=getComputedStyle(e);" +
        "if(s.position!='fixed'&&s.position!='absolute')return;var q=e.getBoundingClientRect();if(q.width<40||q.height<20)return;" +
        "if(parseInt(s.zIndex||0)>999&&q.width>w*0.6&&q.height>h*0.6&&((e.innerText||'').trim().length<40||e.tagName=='A'||e.tagName=='IFRAME')){e.remove();n++;return;}" +
        "for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect();if(q.right<r.left||q.left>r.right||q.bottom<r.top||q.top>r.bottom)continue;" +
        "var a=e.tagName=='A'?e:e.querySelector('a[href]');var ext=a&&a.hostname&&a.hostname!=location.hostname;" +
        "if(e.tagName=='IFRAME'||ext||AD.test((e.innerText||'').slice(0,300))){e.remove();n++;}break;}});" +
        "if(n)try{N.removed(n);}catch(x){}}" +
        "setTimeout(clean,600);setInterval(clean,2000);}catch(e){}})();"

    /** Hides the usual ad boxes left on a page (applied after the page loads). */
    const val HIDE_CSS = "ins.adsbygoogle,[id^='google_ads'],[id^='div-gpt-ad'],[class*='adsbox'],[class*=' ad-'],[class^='ad-']," +
        "[class*='banner-ad'],[id*='banner-ad'],[class*='sponsor'],[id*='popunder'],[class*='popunder']," +
        "iframe[src*='ads'],iframe[src*='doubleclick'],iframe[src*='exoclick'],a[href*='/ads/'],div[id^='ad_'],div[class^='ad_']" +
        "{display:none!important;visibility:hidden!important;}"
}
