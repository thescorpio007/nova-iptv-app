package com.nova.iptv.player

import android.content.Context
import android.content.res.Configuration
import android.widget.TextView

/**
 * Player buttons on a phone: the row of buttons is wider than a phone screen, so on phones (not TV boxes, not
 * tablets) the buttons are smaller and use short labels ("▭ Fit", "◔ 1x", "♡ Fav"…). The TV keeps the full ones.
 */
object PlayerUi {
    fun compact(context: Context): Boolean {
        val c = context.resources.configuration
        val tv = (c.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        return !tv && c.smallestScreenWidthDp < 600
    }

    private val RULES = listOf(
        Regex("^▭ Aspect: (.*)$") to "▭ $1",
        Regex("^Aspect: (.*)$") to "▭ $1",
        Regex("^◔ Speed \\((.*)\\)$") to "◔ $1",
        Regex("^CC Audio & subtitles$") to "CC Audio/Subs",
        Regex("^🌙 Night mode: (.*)$") to "🌙 $1",
        Regex("^([♡♥]) Favorite$") to "$1 Fav",
        Regex("^ℹ Video info$") to "ℹ Info",
        Regex("^↗ External player$") to "↗ External",
        Regex("^⬛ Hide picture$") to "⬛ Hide",
        Regex("^📺 Play on TV$") to "📺 TV",
    )

    fun short(text: CharSequence): CharSequence {
        val s = text.toString()
        for ((re, rep) in RULES) if (re.matches(s)) return re.replace(s, rep)
        return text
    }
}

/**
 * A player button whose label can be shortened: level 0 = full text, 1 = short label ("▭ Fit", "♡ Fav"…),
 * 2 = icon only. [shorten] = start at level 1 (phones). The full text is kept, so any level can be shown again.
 */
class PillText(context: Context, shorten: Boolean) : TextView(context) {
    private var full: CharSequence = ""
    val fullText: CharSequence get() = full
    var level: Int = if (shorten) 1 else 0
        set(v) { field = v; super.setText(show(full), BufferType.NORMAL) }

    private fun show(t: CharSequence): CharSequence = when (level) {
        0 -> t
        1 -> PlayerUi.short(t)
        else -> PlayerUi.short(t).toString().let { s -> s.substringBefore(' ').ifBlank { s } }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        // TextView's own constructor calls this before the fields exist: show the text as it is then.
        @Suppress("SENSELESS_COMPARISON")
        if (full == null) { super.setText(text, type); return }
        full = text ?: ""
        super.setText(show(full), type)
    }
}

/**
 * Option A: a row of player buttons that always fits the screen. Measures the row and steps down until it fits:
 * full labels → smaller text → short labels → icons only.
 */
object RowFit {
    fun fit(row: android.widget.LinearLayout, available: Int, density: Float, bigSp: Float, smallSp: Float) {
        if (available <= 0) return
        val pills = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<PillText>()
        fun dp(v: Int) = (v * density).toInt()
        fun apply(step: Int) {
            pills.forEach { p ->
                val small = step >= 1
                p.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (small) smallSp else bigSp)
                if (small) p.setPadding(dp(8), dp(6), dp(8), dp(6)) else p.setPadding(dp(14), dp(8), dp(14), dp(8))
                (p.layoutParams as? android.widget.LinearLayout.LayoutParams)?.marginEnd = dp(if (small) 5 else 8)
                p.level = when (step) { 0, 1 -> 0; 2 -> 1; else -> 2 }
            }
        }
        for (step in 0..3) {
            apply(step)
            row.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
            if (row.measuredWidth <= available) return
        }
    }

    /** Refits [row] whenever the screen width or a button's label changes (the space = the scroller's parent width). */
    fun attach(scroller: android.view.View, row: android.widget.LinearLayout, bigSp: Float, smallSp: Float) {
        scroller.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val parent = v.parent as? android.view.View ?: return@addOnLayoutChangeListener
            val avail = parent.width - parent.paddingLeft - parent.paddingRight - v.paddingLeft - v.paddingRight
            val labels = (0 until row.childCount).map { row.getChildAt(it) }
                .joinToString("|") { (it as? PillText)?.fullText?.toString().orEmpty() + (if (it.visibility == android.view.View.VISIBLE) "" else "-") }
            val sig = "$avail#$labels"
            if (row.tag != sig) {
                row.tag = sig
                v.post { fit(row, avail, v.resources.displayMetrics.density, bigSp, smallSp) }
            }
        }
    }
}
