package com.nova.iptv.player

import android.content.Context

/** Player choices kept on this device: ⬇ speed on screen, 🪶 lighter version on a tight line. */
object PlayerPrefs {
    private fun p(c: Context) = c.getSharedPreferences("player_ui", Context.MODE_PRIVATE)

    /** "always" (default) / "buttons" (only while the player buttons show) / "off". */
    fun speedBadge(c: Context): String = p(c).getString("speed_badge", "always") ?: "always"
    fun setSpeedBadge(c: Context, v: String) = p(c).edit().putString("speed_badge", v).apply()

    /** "auto" (default: switch after a 10 s countdown) / "ask" / "never". */
    fun lighter(c: Context): String = p(c).getString("lighter", "auto") ?: "auto"
    fun setLighter(c: Context, v: String) = p(c).edit().putString("lighter", v).apply()

    /** How heavy a channel name sounds: 4 = UHD / 4K, 3 = FHD / 1080, 2 = HD / 720 (or nothing said), 1 = SD / 540 / 480. H265 = a bit lighter. */
    fun qualityRank(title: String): Double {
        val t = " " + title.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
        var r = when {
            Regex(" (uhd|4k|2160p?) ").containsMatchIn(t) -> 4.0
            Regex(" (fhd|1080p?|full hd) ").containsMatchIn(t) -> 3.0
            Regex(" (sd|540p?|480p?|576p?|360p?|lq|low) ").containsMatchIn(t) -> 1.0
            else -> 2.0
        }
        if (Regex(" (h265|hevc|x265) ").containsMatchIn(t)) r -= 0.5
        return r
    }
}
