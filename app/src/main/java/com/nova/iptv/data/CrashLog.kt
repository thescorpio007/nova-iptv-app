package com.nova.iptv.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why Nova closed by itself (an error, "not responding", too little memory), so it can be
 * shown in Settings › 7 System › Last problem and shared. Saved in Download/NovaIPTV/Logs too.
 */
object CrashLog {
    private const val PREFS = "nova_crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val mem = Runtime.getRuntime().let { "memory used ${(it.totalMemory() - it.freeMemory()) / 1_048_576} MB of ${it.maxMemory() / 1_048_576} MB" }
                record(app, "Error in “${thread.name}”: ${e.javaClass.simpleName}: ${e.message}\n$mem\n\n" + sw.toString().take(6000))
            }
            previous?.uncaughtException(thread, e)
        }
        // Android 11+: also the closes Nova can't catch itself ("not responding", low memory…).
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = am.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull() ?: return@runCatching
            if (last.timestamp <= p.getLong("seen_exit", 0)) return@runCatching
            p.edit().putLong("seen_exit", last.timestamp).apply()
            val why = when (last.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> "Nova stopped responding (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed Nova: not enough memory"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Nova crashed in the video/system part (native)"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android closed Nova: it used too much power/CPU"
                android.app.ApplicationExitInfo.REASON_CRASH -> null // already recorded by the handler above
                else -> null
            } ?: return@runCatching
            val trace = runCatching { last.traceInputStream?.bufferedReader()?.use { it.readText().take(6000) } }.getOrNull().orEmpty()
            record(app, "$why\n${last.description.orEmpty()}\nmemory ${last.pss / 1024} MB", Date(last.timestamp), trace)
        }
    }

    private fun record(context: Context, text: String, at: Date = Date(), extra: String = "") {
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(at)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val full = "Nova IPTV $version · $stamp\n$text" + if (extra.isNotBlank()) "\n\n$extra" else ""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last", full.take(12000)).putBoolean("new", true).commit()
        runCatching {
            val dir = File(Folders.keepDir(context, "Logs").path).also { it.mkdirs() }
            File(dir, "problem-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(at) + ".txt").writeText(full)
        }
    }

    fun last(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)
    fun isNew(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("new", false)
    fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("new", false).apply()
    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last").putBoolean("new", false).apply()
}
