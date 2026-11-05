package com.nova.iptv.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import org.json.JSONObject

/**
 * 🔊 Sound sync: the picture waits for late sound (Bluetooth earbuds often play it 150–300 ms late).
 *  - one value per sound device (🎧 each pair of earbuds, 📺 TV speakers / HDMI), applied automatically;
 *  - plus a small extra per film / channel, for the rare video that is itself out of sync.
 * Positive = sound earlier (the picture is held back), negative = sound later.
 */
object SoundSync {
    const val TV = "TV speakers / HDMI"
    const val MAX_MS = 2000

    /** The total correction now, read by the sound path ([SyncSink]) on the player's thread. */
    @Volatile var offsetUs = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("sound_sync", Context.MODE_PRIVATE)

    /** The Bluetooth device the sound goes to now (name), or null (TV speakers / HDMI). */
    fun bluetoothDevice(c: Context): String? {
        if (Build.VERSION.SDK_INT < 23) return null
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val bt = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 23 /* hearing aid */, 26 /* BLE headset */, 27 /* BLE speaker */, 30 /* BLE broadcast */)
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in bt }?.let { d ->
                d.productName?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "Bluetooth device"
            }
        }.getOrNull()
    }

    /** Where the sound goes now: the earbuds' name, or [TV]. */
    fun device(c: Context): String = bluetoothDevice(c) ?: TV

    fun deviceMs(c: Context, name: String): Int = devices(c)[name] ?: 0

    /** Every device Nova has seen, with its value (TV speakers always listed). */
    fun devices(c: Context): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        runCatching {
            val o = JSONObject(prefs(c).getString("devices", "{}") ?: "{}")
            o.keys().forEach { k -> out[k] = o.optInt(k) }
        }
        if (TV !in out) out[TV] = 0
        return out
    }

    fun setDeviceMs(c: Context, name: String, ms: Int) {
        val all = devices(c)
        all[name] = ms.coerceIn(-MAX_MS, MAX_MS)
        prefs(c).edit().putString("devices", JSONObject(all as Map<*, *>).toString()).apply()
    }

    /** A device seen for the first time is listed (at 0) so it appears in Settings. */
    fun noteDevice(c: Context, name: String) { if (name !in devices(c)) setDeviceMs(c, name, 0) }

    fun forget(c: Context, name: String) {
        val all = devices(c); all.remove(name)
        prefs(c).edit().putString("devices", JSONObject(all as Map<*, *>).toString()).apply()
    }

    fun itemMs(c: Context, key: String): Int = prefs(c).getInt("item:$key", 0)
    fun setItemMs(c: Context, key: String, ms: Int) {
        val e = prefs(c).edit()
        if (ms == 0) e.remove("item:$key") else e.putInt("item:$key", ms.coerceIn(-MAX_MS, MAX_MS))
        e.apply()
    }

    /** Settings: with Bluetooth sound, no tunneling / audio offload (lets the box report the earbuds' delay; Nova's sync works). */
    fun safePath(c: Context): Boolean = prefs(c).getBoolean("safe_path", true)
    fun setSafePath(c: Context, on: Boolean) = prefs(c).edit().putBoolean("safe_path", on).apply()

    /** Tunneling / offload bypass Nova's sound path: off with Bluetooth (safe path) or when a correction is set. */
    fun avoidShortcuts(c: Context): Boolean = offsetUs != 0L || (safePath(c) && bluetoothDevice(c) != null)

    fun label(ms: Int): String = when {
        ms > 0 -> "+$ms ms"
        ms < 0 -> "−${-ms} ms"
        else -> "0 ms"
    }

    fun words(ms: Int): String = when {
        ms > 0 -> "sound $ms ms earlier"
        ms < 0 -> "sound ${-ms} ms later"
        else -> "sound as in the file"
    }
}

/** The sound path's clock, moved by [SoundSync.offsetUs]: the picture follows this clock, so it waits for late sound. */
@OptIn(UnstableApi::class)
class SyncSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (p == AudioSink.CURRENT_POSITION_NOT_SET) return p
        return maxOf(0L, p - SoundSync.offsetUs)
    }
}
