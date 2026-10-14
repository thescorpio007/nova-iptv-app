package com.nova.iptv.data

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Playback report: one record for every channel, film and episode played (start time, quality, decoder,
 * line speed vs what it needs, cushion, interruptions, watchdog actions, result). Kept 7 days on the device.
 * Never contains usernames, passwords or stream addresses: servers appear by their account name.
 * Settings › System › Last problem › Playback report (Share report → .txt).
 */
object PlaybackLog {

    private const val KEEP_MS = 7L * 24 * 3600_000
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "playback-log.jsonl")
    }

    /** One playback in progress (filled by the player, written when it ends). */
    class Session(
        val title: String,
        val kind: String, // live, movie, series, catchup, recordings
        val server: String,
        val group: String?,
    ) {
        val startedAt = System.currentTimeMillis()
        var mode = ""
        var container = ""
        var video = ""
        var decoder = ""
        var startMs = -1L
        var needMbit = 0.0
        private var arrivalSum = 0.0
        private var arrivalN = 0
        var cushionStart = -1
        var cushionMax = 0
        var cushionMin = Int.MAX_VALUE
        var rebuffers = 0
        var rebufferMs = 0L
        val actions = mutableListOf<String>()
        var result = "OK"
        var note = ""

        fun arrival(mbit: Double) { if (mbit > 0) { arrivalSum += mbit; arrivalN++ } }
        val avgArrival: Double get() = if (arrivalN == 0) 0.0 else arrivalSum / arrivalN
        fun cushion(sec: Int) {
            if (sec < 0) return
            if (cushionStart < 0) cushionStart = sec
            if (sec > cushionMax) cushionMax = sec
            if (sec < cushionMin) cushionMin = sec
        }
        fun action(a: String) { if (actions.size < 12) actions += a }
        fun fail(r: String, why: String) { if (result == "OK" || result == "SLOW") { result = r; note = why } }
    }

    fun end(s: Session) {
        if (!::file.isInitialized) return
        val dur = System.currentTimeMillis() - s.startedAt
        // A channel skipped through in under 3 s without trouble is not worth a line.
        if (s.result == "OK" && s.startMs < 0 && dur < 3000) return
        if (s.result == "OK" && s.startMs < 0) s.fail("FAIL", "no picture (left after ${dur / 1000} s)")
        val o = JSONObject()
            .put("at", s.startedAt).put("dur", dur).put("t", s.title).put("k", s.kind).put("srv", s.server)
            .put("grp", s.group ?: "").put("mode", s.mode).put("cont", s.container).put("vid", s.video).put("dec", s.decoder)
            .put("start", s.startMs).put("need", s.needMbit).put("got", s.avgArrival)
            .put("c0", s.cushionStart).put("cmax", s.cushionMax).put("cmin", if (s.cushionMin == Int.MAX_VALUE) -1 else s.cushionMin)
            .put("rb", s.rebuffers).put("rbms", s.rebufferMs).put("act", s.actions.joinToString(" · "))
            .put("res", s.result).put("note", s.note)
        runCatching {
            synchronized(this) {
                file.appendText(o.toString() + "\n")
                if (file.length() > 1_500_000) prune()
            }
        }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val keep = file.readLines().filter { l -> runCatching { now - JSONObject(l).getLong("at") < KEEP_MS }.getOrDefault(false) }
        file.writeText(keep.takeLast(4000).joinToString("\n", postfix = "\n"))
    }

    private fun records(days: Int): List<JSONObject> {
        if (!::file.isInitialized || !file.exists()) return emptyList()
        val from = if (days <= 1) startOfToday() else System.currentTimeMillis() - days * 24 * 3600_000L
        return file.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.filter { it.optLong("at") >= from }
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun clear() { if (::file.isInitialized) runCatching { file.delete() } }

    /** "Played 64 · OK 55 · failed 6 · can't decode here 3 · average start 2.3 s" + one line per server. */
    fun glance(days: Int = 7): String {
        val r = records(days)
        if (r.isEmpty()) return "Nothing played yet in this period."
        val ok = r.count { it.optString("res") == "OK" || it.optString("res") == "SLOW" }
        val failed = r.count { it.optString("res") == "FAIL" }
        val device = r.count { it.optString("res") == "DEVICE" }
        val starts = r.mapNotNull { it.optLong("start").takeIf { s -> s >= 0 } }
        val avgStart = if (starts.isEmpty()) "–" else "%.1f s".format(starts.average() / 1000)
        return "Played ${r.size} · OK $ok · failed $failed · can't decode here $device · average start $avgStart\n" + serverLines(r).joinToString("\n")
    }

    private fun serverLines(r: List<JSONObject>): List<String> = r.groupBy { it.optString("srv") }.map { (srv, list) ->
        val speeds = list.map { it.optDouble("got") }.filter { it > 0 }
        val hours = list.sumOf { it.optLong("dur") } / 3_600_000.0
        val rb = list.sumOf { it.optInt("rb") }
        "$srv: " + (if (speeds.isEmpty()) "speed –" else "%.1f Mbit/s avg".format(speeds.average())) +
            (if (hours > 0.05) ", %.1f rebuffers / hour".format(rb / hours) else "") + ", ${list.size} played"
    }

    /** The full text report (period: 1 = today, 3, 7 days). */
    fun report(context: Context, days: Int, settings: String, health: List<String>): String {
        val r = records(days)
        val df = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val dm = context.resources.displayMetrics
        val sb = StringBuilder()
        val period = when (days) { 1 -> "today"; else -> "last $days days" }
        sb.appendLine("NOVA IPTV PLAYBACK REPORT · $period · made ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}")
        sb.appendLine("Nova $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${dm.widthPixels}×${dm.heightPixels}")
        sb.appendLine("Settings: $settings")
        sb.appendLine("──────────────── SUMMARY ────────────────")
        if (r.isEmpty()) sb.appendLine("Nothing played in this period.")
        else {
            sb.appendLine(glance(days).lineSequence().first())
            val fails = r.filter { it.optString("res") == "FAIL" }.groupBy { it.optString("t") }
                .entries.sortedByDescending { it.value.size }.take(8)
                .joinToString(" · ") { (t, l) -> "$t (${l.size}× ${l.first().optString("note").take(30)})" }
            if (fails.isNotBlank()) sb.appendLine("Failed most : $fails")
            val dev = r.filter { it.optString("res") == "DEVICE" }.map { it.optString("t") + " (" + it.optString("note").take(30) + ")" }.distinct().take(8)
            if (dev.isNotEmpty()) sb.appendLine("Can't decode: " + dev.joinToString(" · "))
            val slow = r.filter { it.optString("res") == "SLOW" || it.optString("act").contains("slow") }.map { it.optString("t") }.distinct().take(8)
            if (slow.isNotEmpty()) sb.appendLine("Line too slow: " + slow.joinToString(" · "))
            serverLines(r).forEach { sb.appendLine("Server      : $it") }
        }
        sb.appendLine("──────────────── PLAYBACK (newest first) ─")
        r.sortedByDescending { it.optLong("at") }.forEach { o ->
            val res = o.optString("res").padEnd(6)
            val grp = o.optString("grp").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            sb.appendLine("${df.format(Date(o.optLong("at")))} $res ${o.optString("t")} · ${o.optString("srv")} · ${o.optString("k")}$grp")
            val tech = listOf(o.optString("mode"), o.optString("cont"), o.optString("vid"), o.optString("dec")).filter { it.isNotBlank() }
            if (tech.isNotEmpty()) sb.appendLine("   " + tech.joinToString(" · "))
            val parts = mutableListOf<String>()
            val start = o.optLong("start")
            parts += if (start >= 0) "start %.1f s".format(start / 1000.0) else "never started"
            val need = o.optDouble("need"); val got = o.optDouble("got")
            if (need > 0 || got > 0) parts += "needs %.1f / got %.1f Mbit/s".format(need, got)
            parts += "watched " + dur(o.optLong("dur"))
            sb.appendLine("   " + parts.joinToString(" · "))
            val c0 = o.optInt("c0", -1); val cmax = o.optInt("cmax"); val cmin = o.optInt("cmin", -1)
            val rb = o.optInt("rb"); val rbms = o.optLong("rbms")
            val line3 = mutableListOf<String>()
            if (c0 >= 0) line3 += "cushion ${c0} s→${cmax} s (low ${cmin.coerceAtLeast(0)} s)"
            line3 += "rebuffers $rb" + if (rb > 0) " (${rbms / 1000} s)" else ""
            o.optString("act").takeIf { it.isNotBlank() }?.let { line3 += it }
            sb.appendLine("   " + line3.joinToString(" · "))
            o.optString("note").takeIf { it.isNotBlank() }?.let { sb.appendLine("   → $it") }
        }
        sb.appendLine("──────────────── WATCHDOG / HEALTH ──────")
        val from = if (days <= 1) startOfToday() else System.currentTimeMillis() - days * 24 * 3600_000L
        if (health.isEmpty()) sb.appendLine("none")
        health.take(80).forEach { sb.appendLine(it.replace('|', ' ')) }
        sb.appendLine("──────────────── APP ────────────────────")
        val logs = runCatching { java.io.File(Folders.keepDir(context, "Logs").path).listFiles() }.getOrNull().orEmpty()
            .filter { it.name.startsWith("problem-") && it.lastModified() >= from }.sortedByDescending { it.lastModified() }
        if (logs.isEmpty()) sb.appendLine("No crash, freeze or forced close in this period.")
        logs.take(10).forEach { f ->
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            sb.appendLine(lines.take(4).joinToString(" | "))
            lines.drop(4).filter { it.contains("at com.nova") || it.contains("Exception") || it.contains("Error") }.take(8).forEach { sb.appendLine("   $it") }
        }
        return sb.toString()
    }

    private fun dur(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else if (m > 0) "$m min" else "${ms / 1000} s"
    }

    /** Writes the report to Download/NovaIPTV/Logs/nova-report-<date>.txt. */
    fun save(context: Context, text: String): File {
        val dir = Folders.keepDir(context, "Logs").also { it.mkdirs() }
        val f = File(dir, "nova-report-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".txt")
        runCatching { f.writeText(text) }.onFailure {
            // An older install's file can't be overwritten: a fresh name.
            val alt = File(dir, "nova-report-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date()) + ".txt")
            alt.writeText(text)
            return alt
        }
        return f
    }
}
