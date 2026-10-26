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
 * Smart fill: a row of player buttons that always fits the screen width and uses the space it has.
 * Full labels if they fit (big, then smaller text); otherwise every button starts as an icon and the most used
 * buttons get their short label first (Next, Live, Rec, Hide, Night, Boost, TV, Fav…) while the row still fits.
 */
object RowFit {
    /** Lower = keeps its label longer. */
    private fun priority(t: String): Int = when {
        t.startsWith("Next") -> 1
        t.startsWith("Live") -> 2
        t.contains("Rec") -> 3
        t.startsWith("⬛") -> 4
        t.startsWith("🌙") || t.startsWith("🔊") -> 5
        t.startsWith("📺") || t.startsWith("♡") || t.startsWith("♥") -> 6
        t.startsWith("◀") || t.startsWith("⏪") -> 7
        t.startsWith("CC") || t.startsWith("◔") -> 8
        t.startsWith("⬇") -> 9
        t.startsWith("←") || t.startsWith("≈") -> 10
        t.startsWith("ℹ") || t.contains("Aspect") -> 11
        else -> 12
    }

    fun fit(row: android.widget.LinearLayout, available: Int, density: Float, bigSp: Float, smallSp: Float) {
        if (available <= 0) return
        val pills = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<PillText>()
        fun dp(v: Int) = (v * density).toInt()
        fun size(small: Boolean) = pills.forEach { p ->
            p.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (small) smallSp else bigSp)
            if (small) p.setPadding(dp(8), dp(6), dp(8), dp(6)) else p.setPadding(dp(14), dp(8), dp(14), dp(8))
            (p.layoutParams as? android.widget.LinearLayout.LayoutParams)?.marginEnd = dp(if (small) 5 else 8)
        }
        fun width(): Int {
            row.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
            return row.measuredWidth
        }
        size(false); pills.forEach { it.level = 0 }
        if (width() <= available) return
        size(true)
        if (width() <= available) return
        pills.forEach { it.level = 2 }
        if (width() > available) return
        for (p in pills.filter { it.visibility == android.view.View.VISIBLE }.sortedBy { priority(it.fullText.toString()) }) {
            p.level = 1
            if (width() > available) p.level = 2
        }
    }

    /** Refits [row] whenever the screen width or a button's label changes (the space = the scroller's parent width). */
    fun attach(scroller: android.view.View, row: android.widget.LinearLayout, bigSp: Float, smallSp: Float) {
        fun refit() {
            val parent = scroller.parent as? android.view.View ?: return
            val avail = parent.width - parent.paddingLeft - parent.paddingRight - scroller.paddingLeft - scroller.paddingRight
            val labels = (0 until row.childCount).map { row.getChildAt(it) }
                .joinToString("|") { (it as? PillText)?.fullText?.toString().orEmpty() + (if (it.visibility == android.view.View.VISIBLE) "" else "-") }
            val sig = "$avail#$labels"
            if (row.tag != sig) {
                row.tag = sig
                scroller.post { fit(row, avail, scroller.resources.displayMetrics.density, bigSp, smallSp) }
            }
        }
        scroller.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refit() }
        // The screen width changes the parent (the row itself may keep its size): listen to it too.
        scroller.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                (v.parent as? android.view.View)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refit() }
            }
            override fun onViewDetachedFromWindow(v: android.view.View) {}
        })
    }
}
