package com.nova.iptv.download

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * ⏰ Scheduled recordings: a channel from a start to an end time (once, every day, Mon–Fri or every week),
 * started by an alarm even when Nova is closed or the box sleeps, then optionally: close Nova, sleep, switch off.
 */
object RecSchedule {
    data class Sched(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val url: String,
        val accountId: String,
        val icon: String?,
        val programme: String? = null,
        val start: Long,
        val end: Long,
        /** "once", "daily", "weekdays", "weekly". */
        val repeat: String = "once",
        /** After the recording: "none", "close", "sleep", "off". */
        val then: String = "none",
    )

    val all = MutableStateFlow<List<Sched>>(emptyList())

    private fun prefs(c: Context) = c.getSharedPreferences("rec_schedule", Context.MODE_PRIVATE)

    fun load(c: Context): List<Sched> {
        val out = mutableListOf<Sched>()
        runCatching {
            val a = JSONArray(prefs(c).getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += Sched(
                    o.getString("id"), o.getString("title"), o.getString("url"), o.optString("account"), o.optString("icon").ifBlank { null },
                    o.optString("programme").ifBlank { null }, o.getLong("start"), o.getLong("end"), o.optString("repeat", "once"), o.optString("then", "none"),
                )
            }
        }
        all.value = out.sortedBy { it.start }
        return all.value
    }

    private fun store(c: Context, list: List<Sched>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().apply {
                put("id", s.id); put("title", s.title); put("url", s.url); put("account", s.accountId); put("icon", s.icon ?: "")
                put("programme", s.programme ?: ""); put("start", s.start); put("end", s.end); put("repeat", s.repeat); put("then", s.then)
            })
        }
        prefs(c).edit().putString("list", a.toString()).apply()
        all.value = list.sortedBy { it.start }
    }

    fun save(c: Context, s: Sched) {
        store(c, load(c).filter { it.id != s.id } + s)
        arm(c, s)
        Log.i("NovaTest", "SCHEDULE saved ${s.title} ${LiveRec.hhmm(s.start)}→${LiveRec.hhmm(s.end)} ${s.repeat} then ${s.then}")
    }

    fun cancel(c: Context, id: String) {
        store(c, load(c).filter { it.id != id })
        runCatching { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, id)) }
        Log.i("NovaTest", "SCHEDULE cancelled $id")
    }

    /** Other schedules that overlap [s]. */
    fun overlaps(c: Context, s: Sched): List<Sched> = load(c).filter { it.id != s.id && it.start < s.end && s.start < it.end }

    fun canExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    private fun pending(c: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        c, id.hashCode(), Intent(c, RecAlarmReceiver::class.java).setAction("com.nova.iptv.REC_ALARM").putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun arm(c: Context, s: Sched) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, s.id)
        runCatching {
            if (Build.VERSION.SDK_INT < 23) am.setExact(AlarmManager.RTC_WAKEUP, s.start, pi)
            else if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.start, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.start, pi)
        }
    }

    /** At Nova's start: alarms set again; a schedule whose time is running now starts at once; past ones move on. */
    fun rearm(c: Context) {
        val now = System.currentTimeMillis()
        val keep = mutableListOf<Sched>()
        var startNow: Sched? = null
        load(c).forEach { s0 ->
            var s = s0
            if (s.end <= now) s = next(s) ?: return@forEach
            if (s.start <= now && s.end > now + 60_000 && startNow == null) startNow = s
            keep += s
        }
        store(c, keep)
        keep.forEach { if (it.start > now) arm(c, it) }
        startNow?.let { s -> Handler(Looper.getMainLooper()).postDelayed({ fire(c, s.id) }, 3000) }
    }

    /** The next time of a repeating schedule, or null for "once". */
    fun next(s: Sched): Sched? {
        if (s.repeat == "once") return null
        val now = System.currentTimeMillis()
        val len = s.end - s.start
        val cal = Calendar.getInstance().apply { timeInMillis = s.start }
        var guard = 0
        do {
            cal.add(Calendar.DAY_OF_YEAR, if (s.repeat == "weekly") 7 else 1)
            if (s.repeat == "weekdays") while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) cal.add(Calendar.DAY_OF_YEAR, 1)
        } while (cal.timeInMillis + len <= now && guard++ < 400)
        return s.copy(start = cal.timeInMillis, end = cal.timeInMillis + len)
    }

    /** The alarm: start the recording (the player asks first when it would take the connection you watch on). */
    fun fire(c: Context, id: String) {
        val s = load(c).firstOrNull { it.id == id } ?: return
        if (s.start > System.currentTimeMillis() + 60_000) return // not due (already handled, or the next time of a repeat)
        Log.i("NovaTest", "SCHEDULE start ${s.title}")
        // The next time (repeats) or done (once).
        val nx = next(s)
        if (nx != null) { store(c, load(c).filter { it.id != id } + nx); arm(c, nx) } else store(c, load(c).filter { it.id != id })
        if (System.currentTimeMillis() >= s.end - 30_000) return
        val running = LiveRec.state.value
        if (running != null) {
            Log.i("NovaTest", "SCHEDULE ${s.title}: not started, ${running.title} is recording")
            LiveRec.notes.tryEmit("⏰ ${s.title} not recorded: ${running.title} is recording")
            return
        }
        val top = com.nova.iptv.TopActivity.get()
        if (top is com.nova.iptv.player.PlayerActivity) top.scheduledStart(s) else startNow(c, s)
    }

    fun startNow(c: Context, s: Sched) {
        LiveRec.start(c, s.title, s.url, s.accountId, s.icon, endsAt = s.end, fixedEnd = true, then = s.then, programme = s.programme)
    }

    // ---------------------------------------------------------------- after the recording

    fun thenLabel(t: String) = when (t) {
        "close" -> "close Nova"
        "sleep" -> "box / phone to sleep"
        "off" -> "switch off the box"
        else -> "nothing"
    }

    /** The recording ended at its time: the chosen action follows, 10 s to say "Stay on". */
    fun afterEnd(c: Context, then: String, title: String, seconds: Long) {
        if (then == "none") return
        Handler(Looper.getMainLooper()).post {
            val a = com.nova.iptv.TopActivity.get()
            if (a == null) { act(c, then); return@post }
            val icon = when (then) { "close" -> "✕"; "sleep" -> "💤"; else -> "⏻" }
            com.nova.iptv.player.RecPopups.choose(
                a, "✓ Recording saved", "$title · ${LiveRec.words(seconds)}. Then: ${thenLabel(then)}.",
                listOf(
                    com.nova.iptv.player.RecPopups.Choice("$icon ${thenLabel(then).replaceFirstChar { it.uppercase() }} now", "Chosen in 10 s") { act(c, then) },
                    com.nova.iptv.player.RecPopups.Choice("✕ Stay on") { Log.i("NovaTest", "SCHEDULE then: stay on") },
                ),
                default = 0,
            ) { act(c, then) }
        }
    }

    private fun act(c: Context, then: String) {
        Log.i("NovaTest", "SCHEDULE then: $then")
        when (then) {
            "close" -> com.nova.iptv.TopActivity.get()?.finishAffinity()
            "sleep" -> sleep(c)
            "off" -> Thread { if (!powerOff()) Handler(Looper.getMainLooper()).post { sleep(c) } }.start()
        }
    }

    // ---------------------------------------------------------------- sleep / switch off

    fun adminComponent(c: Context) = ComponentName(c, NovaAdmin::class.java)

    fun canSleep(c: Context): Boolean = runCatching {
        (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminComponent(c))
    }.getOrDefault(false)

    /** Android asks once: "Allow Nova to turn the screen off?" (needed to put the box / phone to sleep). */
    fun askSleepPermission(a: android.app.Activity) {
        runCatching {
            a.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(a))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets Nova put the box / phone to sleep after a scheduled recording (screen off). Nothing else."))
        }.onFailure { android.widget.Toast.makeText(a, "This device doesn't allow it: Nova will close itself instead.", android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun sleep(c: Context) {
        val ok = runCatching {
            val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isAdminActive(adminComponent(c))) false else { dpm.lockNow(); true }
        }.getOrDefault(false)
        if (!ok) { Log.i("NovaTest", "SCHEDULE sleep not allowed → closing Nova"); com.nova.iptv.TopActivity.get()?.finishAffinity() }
    }

    /** Can Nova switch this box off (only boxes that give apps full system rights)? Blocking, ~3 s. */
    fun canPowerOff(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        waitFor(p, 3000) == 0 && p.inputStream.bufferedReader().readText().contains("uid=0")
    }.getOrDefault(false)

    private fun powerOff(): Boolean = runCatching {
        waitFor(Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p")), 5000)
        false // still running: switching off didn't happen
    }.getOrDefault(false)

    /** The exit code, or -1 when it didn't end in [ms]. */
    private fun waitFor(p: Process, ms: Long): Int {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            try { return p.exitValue() } catch (_: IllegalThreadStateException) { Thread.sleep(100) }
        }
        p.destroy()
        return -1
    }
}

/** The ⏰ alarm of a scheduled recording. */
class RecAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return
        RecSchedule.fire(c.applicationContext, id)
    }
}

/** Only used to turn the screen off (sleep) after a scheduled recording. */
class NovaAdmin : DeviceAdminReceiver()
