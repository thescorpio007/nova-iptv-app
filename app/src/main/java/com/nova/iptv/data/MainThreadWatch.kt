package com.nova.iptv.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches Nova's screen (main) thread: when it doesn't answer for 3 s, what it was doing is saved, so a later
 * "Nova stopped responding" (ANR) report says exactly where it was stuck — even on boxes where Android gives no record.
 */
object MainThreadWatch {
    private const val PREFS = "main_thread_watch"
    @Volatile private var lastBeat = SystemClock.uptimeMillis()
    @Volatile private var saved = false

    fun start(context: Context) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val beat = object : Runnable { override fun run() { lastBeat = SystemClock.uptimeMillis(); saved = false; main.postDelayed(this, 1000) } }
        main.post(beat)
        Thread({
            while (true) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { return@Thread }
                val stuck = SystemClock.uptimeMillis() - lastBeat
                if (stuck > 3000 && !saved) {
                    saved = true
                    runCatching {
                        val stack = Looper.getMainLooper().thread.stackTrace.take(40).joinToString("\n") { "  at $it" }
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putLong("at", System.currentTimeMillis()).putString("stack", "stuck ${stuck / 1000} s+\n$stack").commit()
                        android.util.Log.w("NovaWatch", "screen thread stuck ${stuck} ms\n$stack")
                    }
                }
            }
        }, "nova-mainwatch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** The stall saved closest before a freeze at [atMs] (within 2 minutes), or null. */
    fun lastStall(context: Context, atMs: Long): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        if (at == 0L || kotlin.math.abs(atMs - at) > 120_000) return null
        return p.getString("stack", null)
    }
}
