package com.nova.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nova.iptv.data.EpgEntry
import com.nova.iptv.data.Locations
import com.nova.iptv.download.LiveRec
import com.nova.iptv.download.RecSchedule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ⏰ Schedule a recording: a programme from the TV guide (fills start / end with margins) or your own day, start and end;
 * repeat; and what happens when it ends (nothing, close Nova, sleep, switch off). Works with the remote and by touch.
 */
object RecScheduleDialog {
    private const val EARLY = 2 * 60_000L
    private const val LATE = 5 * 60_000L

    fun show(a: Activity, base: RecSchedule.Sched, guide: List<EpgEntry>, onSaved: (() -> Unit)? = null) {
        var s = base
        val dp = { v: Int -> (v * a.resources.displayMetrics.density).toInt() }
        val accent = 0xFF7FF5C8.toInt()
        val chipBg = 0xFF2B3A5C.toInt()
        val onBg = 0xFFE6FFF4.toInt()
        val onFg = 0xFF0B6B46.toInt()

        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        fun label(t: String) = TextView(a).apply {
            text = t; setTextColor(0xFFCFE0FF.toInt()); setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(12), 0, dp(6))
        }.also { root.addView(it) }
        fun bg(on: Boolean) = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (on) onBg else chipBg) }
        fun chip(t: String, on: Boolean, click: () -> Unit) = Button(a).apply {
            text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = bg(on); setTextColor(if (on) onFg else Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6)); minHeight = 0; minimumHeight = 0
            setOnClickListener { click() }
            setOnFocusChangeListener { v, f -> (v as Button).background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(if (on) onBg else chipBg); if (f) setStroke(dp(3), accent) } }
        }
        fun row(): LinearLayout {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(r) })
            return r
        }
        fun add(r: LinearLayout, v: View) = r.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })

        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        lateinit var refresh: () -> Unit

        val guideRow = if (guide.isNotEmpty()) { label("From the TV guide (optional)"); row() } else null
        label("Day"); val dayRow = row()
        label("Start                                    End"); val timeRow = row()
        val margin = TextView(a).apply { setTextColor(0xFFFFD27A.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f) }.also { root.addView(it) }
        label("Repeat"); val repRow = row()
        label("When the recording ends, then…"); val thenRow = row()
        val thenNote = TextView(a).apply { setTextColor(0xFFFFD27A.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f) }.also { root.addView(it) }
        val summary = TextView(a).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f); setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF10351F.toInt()); setStroke(dp(1), 0xFF3DDC84.toInt()) }
        }
        root.addView(summary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        fun startOfDay(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun setDay(day0: Long) {
            val len = s.end - s.start
            val st = day0 + (s.start - startOfDay(s.start))
            s = s.copy(start = st, end = st + len)
        }
        fun pickTime(isStart: Boolean) {
            val t = if (isStart) s.start else s.end
            val cal = Calendar.getInstance().apply { timeInMillis = t }
            TimePickerDialog(a, { _, h, m ->
                val c2 = Calendar.getInstance().apply { timeInMillis = if (isStart) s.start else s.start; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                if (isStart) {
                    val len = s.end - s.start
                    s = s.copy(start = c2.timeInMillis, end = c2.timeInMillis + len)
                } else {
                    var e = c2.timeInMillis
                    if (e <= s.start) e += 24 * 3600_000L // past midnight
                    s = s.copy(end = e)
                }
                refresh()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }

        refresh = {
            val now = System.currentTimeMillis()
            guideRow?.let { r ->
                r.removeAllViews()
                guide.filter { it.end > now }.take(6).forEach { g ->
                    val on = s.programme == g.title && s.start == g.start - EARLY
                    add(r, chip("${g.title}\n${dayLabel(g.start, dayFmt)} ${hm.format(Date(g.start))}–${hm.format(Date(g.end))}", on) {
                        s = s.copy(programme = g.title, start = maxOf(now + 30_000, g.start - EARLY), end = g.end + LATE); refresh()
                    })
                }
            }
            dayRow.removeAllViews()
            val today = startOfDay(now)
            val sel = startOfDay(s.start)
            listOf(0, 1, 2).forEach { k ->
                val d0 = today + k * 24 * 3600_000L
                val name = when (k) { 0 -> "Today · " ; 1 -> "Tomorrow · "; else -> "" } + dayFmt.format(Date(d0))
                add(dayRow, chip(name, sel == d0) { setDay(d0); refresh() })
            }
            add(dayRow, chip(if (sel > today + 2 * 24 * 3600_000L) "📅 " + dayFmt.format(Date(sel)) else "Other day…", sel > today + 2 * 24 * 3600_000L) {
                val cal = Calendar.getInstance().apply { timeInMillis = s.start }
                DatePickerDialog(a, { _, y, m, d ->
                    val c2 = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
                    setDay(c2.timeInMillis); refresh()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            })
            timeRow.removeAllViews()
            add(timeRow, chip(hm.format(Date(s.start)), true) { pickTime(true) })
            add(timeRow, chip("−5", false) { s = s.copy(start = s.start - 300_000L); refresh() })
            add(timeRow, chip("+5", false) { if (s.start + 300_000L < s.end) s = s.copy(start = s.start + 300_000L); refresh() })
            add(timeRow, TextView(a).apply { text = "  →  "; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) })
            add(timeRow, chip(hm.format(Date(s.end)), true) { pickTime(false) })
            add(timeRow, chip("−5", false) { if (s.end - 300_000L > s.start) s = s.copy(end = s.end - 300_000L); refresh() })
            add(timeRow, chip("+5", false) { s = s.copy(end = s.end + 300_000L); refresh() })
            margin.text = if (s.programme != null) "Margins added: starts 2 min early, ends 5 min late (programmes rarely run on time)" else ""
            margin.visibility = if (s.programme != null) View.VISIBLE else View.GONE
            repRow.removeAllViews()
            val wd = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(s.start))
            listOf("once" to "Once", "daily" to "Every day", "weekdays" to "Mon–Fri", "weekly" to "Every week ($wd)").forEach { (k, l) ->
                add(repRow, chip(l, s.repeat == k) { s = s.copy(repeat = k); refresh() })
            }
            thenRow.removeAllViews()
            listOf("none" to "Nothing", "close" to "Close Nova", "sleep" to "Box / phone to sleep (screen off)", "off" to "Switch off the box").forEach { (k, l) ->
                add(thenRow, chip(l, s.then == k) {
                    s = s.copy(then = k); refresh()
                    if (k == "sleep" && !RecSchedule.canSleep(a)) RecSchedule.askSleepPermission(a)
                    if (k == "off") Thread {
                        val ok = RecSchedule.canPowerOff()
                        a.runOnUiThread {
                            if (!ok) {
                                Toast.makeText(a, "This box doesn't let apps switch it off — Nova will put it to sleep instead.", Toast.LENGTH_LONG).show()
                                s = s.copy(then = "sleep"); refresh()
                                if (!RecSchedule.canSleep(a)) RecSchedule.askSleepPermission(a)
                            }
                        }
                    }.start()
                })
            }
            thenNote.text = "\"Switch off\" only where the box allows it (else: sleep). A switched-off box can't record the next schedule."
            val mins = (s.end - s.start) / 60_000
            val drive = runCatching { Locations.resolve(Locations.Kind.RECORDINGS).drive?.let { Locations.nameOf(it) } }.getOrNull()
            val overlap = RecSchedule.overlaps(a, s).firstOrNull()
            summary.text = "● ${s.title}" + (s.programme?.let { " · $it" } ?: "") + "\n" +
                SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(s.start)) + " → " + hm.format(Date(s.end)) +
                " (${if (mins >= 60) "${mins / 60} h %02d min".format(mins % 60) else "$mins min"})" +
                (drive?.let { " · on $it" } ?: "") + " · then: ${RecSchedule.thenLabel(s.then)}" +
                (if (s.repeat != "once") " · repeats" else "") +
                (overlap?.let { "\n⚠ Overlaps ${it.title} ${hm.format(Date(it.start))}–${hm.format(Date(it.end))}: only one records at a time" } ?: "") +
                (if (s.end <= now) "\n⚠ This time is already over" else "")
        }
        refresh()

        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("⏰ Schedule · ${s.title}")
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("✓ Save schedule") { _, _ ->
                if (s.end <= System.currentTimeMillis()) {
                    Toast.makeText(a, "That time is already over — not saved.", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                RecSchedule.save(a, s)
                Toast.makeText(a, "⏰ ${s.title}: ${SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(s.start))} → ${hm.format(Date(s.end))} · Downloads › Recordings", Toast.LENGTH_LONG).show()
                if (!RecSchedule.canExact(a) && Build.VERSION.SDK_INT >= 31) {
                    Toast.makeText(a, "Allow \"Alarms & reminders\" for Nova so recordings start exactly on time.", Toast.LENGTH_LONG).show()
                    runCatching { a.startActivity(android.content.Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + a.packageName))) }
                }
                onSaved?.invoke()
            }
            .setNegativeButton("✕ Cancel", null)
            .show()
    }

    private fun dayLabel(t: Long, fmt: SimpleDateFormat): String {
        val c = Calendar.getInstance()
        val today = c.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = t
        return when (c.get(Calendar.DAY_OF_YEAR) - today) { 0 -> "Today"; 1 -> "Tomorrow"; else -> fmt.format(Date(t)) }
    }

    /** A new schedule for a channel: the next programme if the guide has one, else the next half hour for 1 hour. */
    fun newFor(title: String, url: String, accountId: String, icon: String?, guide: List<EpgEntry>): RecSchedule.Sched {
        val now = System.currentTimeMillis()
        val next = guide.firstOrNull { it.start > now }
        if (next != null) return RecSchedule.Sched(title = title, url = url, accountId = accountId, icon = icon, programme = next.title,
            start = next.start - EARLY, end = next.end + LATE)
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 30 - get(Calendar.MINUTE) % 30) }
        return RecSchedule.Sched(title = title, url = url, accountId = accountId, icon = icon, start = c.timeInMillis, end = c.timeInMillis + 3600_000L)
    }
}
