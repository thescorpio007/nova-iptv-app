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
    )

    fun short(text: CharSequence): CharSequence {
        val s = text.toString()
        for ((re, rep) in RULES) if (re.matches(s)) return re.replace(s, rep)
        return text
    }
}

/** A player button whose label is shortened automatically on phones, whatever text is set later. */
class PillText(context: Context, private val shorten: Boolean) : TextView(context) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        // Called by TextView's own constructor before [shorten] is set: it is false then (nothing to shorten).
        super.setText(if (shorten && text != null) PlayerUi.short(text) else text, type)
    }
}
