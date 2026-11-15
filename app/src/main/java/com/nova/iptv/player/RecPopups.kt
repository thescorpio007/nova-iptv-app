package com.nova.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nova.iptv.download.LiveRec
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** ● Recording popups with a 10 s countdown (the highlighted choice is taken when nobody answers). */
object RecPopups {

    class Choice(val title: String, val detail: String? = null, val action: () -> Unit)

    fun choose(a: Activity, title: String, message: String, choices: List<Choice>, default: Int, seconds: Int = 10, onTimeout: () -> Unit): AlertDialog {
        val h = Handler(Looper.getMainLooper())
        val dp = { v: Int -> (v * a.resources.displayMetrics.density).toInt() }
        var left = seconds.coerceAtLeast(1)
        var done = false
        lateinit var dialog: AlertDialog
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(10))
            addView(TextView(a).apply {
                text = message
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, 0, 0, dp(8))
            })
        }
        val buttons = choices.mapIndexed { i, c ->
            Button(a).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                text = if (c.detail != null) "${c.title}\n${c.detail}" else c.title
                setOnClickListener {
                    if (done) return@setOnClickListener
                    done = true
                    h.removeCallbacksAndMessages(null)
                    dialog.dismiss()
                    c.action()
                }
                box.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }
        dialog = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$title  ($left)")
            .setView(box)
            .setOnDismissListener { h.removeCallbacksAndMessages(null) }
            .create()
        dialog.show()
        buttons.getOrNull(default)?.requestFocus()
        h.postDelayed(object : Runnable {
            override fun run() {
                if (done) return
                left--
                if (left <= 0) {
                    done = true
                    runCatching { dialog.dismiss() }
                    onTimeout()
                    return
                }
                dialog.setTitle("$title  ($left)")
                h.postDelayed(this, 1000)
            }
        }, 1000)
        return dialog
    }

    private var askShownFor = 0L

    /** "⏰ Recording time is up": shown by whichever Nova screen is in front. No answer → the recording stops and is saved. */
    fun timeUp(a: Activity) {
        val s = LiveRec.state.value ?: return
        if (s.askingSince == 0L || askShownFor == s.askingSince) return
        askShownFor = s.askingSince
        val h = LiveRec.hours(a)
        val left = (10 - (System.currentTimeMillis() - s.askingSince) / 1000).toInt()
        if (left <= 0) return
        android.util.Log.i("NovaTest", "LIVEREC asking ${s.title}")
        choose(
            a, "⏰ Recording time is up",
            "${s.title} has recorded ${LiveRec.words(s.seconds)} (${LiveRec.size(s.bytes)}). Keep recording?",
            listOf(
                Choice("➕ Another $h h", "Until ${LiveRec.hhmm(System.currentTimeMillis() + h * 3_600_000L)}") { LiveRec.extend(a, h) },
                Choice("➕ 1 h") { LiveRec.extend(a, 1) },
                Choice("■ Stop and save", "Chosen in 10 s if you don't answer") { LiveRec.stop(a, "time is up — stopped by you") },
            ),
            default = 2, seconds = left,
        ) { /* the recording stops by itself (RecordService) */ }
    }

    /** Lets [a] show the time-up popup while it is in front. */
    fun watch(a: Activity) {
        val owner = a as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                LiveRec.state.distinctUntilChangedBy { it?.askingSince ?: 0L }.collect { s ->
                    if (s != null && s.askingSince > 0) timeUp(a)
                }
            }
        }
    }
}
