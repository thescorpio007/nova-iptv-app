package com.nova.iptv.data

import android.content.Context

/** 🔗 Web addresses of streams (Settings › 3): hidden by default — they hold the account name and password. */
object UrlPrivacy {
    private fun prefs(c: Context) = c.getSharedPreferences("privacy", Context.MODE_PRIVATE)
    fun show(c: Context): Boolean = prefs(c).getBoolean("show_urls", false)
    fun setShow(c: Context, v: Boolean) = prefs(c).edit().putBoolean("show_urls", v).apply()
    /** What to print for [url]: the address, or "hidden" (📋 Copy link / ▦ QR still give the real one). */
    fun label(c: Context, url: String): String = if (show(c)) "🔗 $url" else "🔗 hidden (Settings › 3)"
}
