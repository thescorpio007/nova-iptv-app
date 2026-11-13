package com.nova.iptv.player

import android.content.Context

/**
 * 🐈 CATWATCH — watches the live video as it is stored, well before you watch it.
 *
 * For every stored MPEG-TS packet of the video stream it notes:
 *  - where each KEY PICTURE starts (the only places a player can restart cleanly, every ~0.5–2 s);
 *  - DAMAGE: lost packets (a gap in the stream's packet counter), packets the provider marked as broken,
 *    garbage between packets, or a reconnection that joined in the middle of a picture group.
 * When a picture group is damaged, the part from the damage to the next key picture is noted as a SKIP: the
 * relay hands the player the next good key picture instead, so the player never decodes the broken part
 * (no frozen picture, no watchdog, no jump to live). Your cushion stays.
 *
 * Only reads the stored bytes (no second decoder): light for the box.
 */
class CatWatch {
    class Skip(val from: Long, val to: Long)

    private val carry = ByteArray(188)
    private var carryLen = 0
    private var carryAt = 0L

    private var pmtPid = -1
    private var videoPid = -1
    private var videoType = 0      // 0x1B H.264, 0x24 HEVC, 0x01/0x02 MPEG-2
    private var lastCc = -1
    private var joinPending = false

    private var damageAt = -1L
    private var lost = 0
    private var hard = false

    private val skips = ArrayDeque<Skip>()
    private val keys = LongArray(4096)
    private var keyCount = 0       // total written (ring index = count % size)

    /** Damaged parts found ahead / skipped by the relay, and how many seconds of stream were skipped. */
    @Volatile var found = 0
        private set

    /** A new connection to the provider: the new data starts in the middle of a picture group. */
    fun newConnection() {
        carryLen = 0
        lastCc = -1
        if (keyCount > 0) joinPending = true
    }

    /** Looks at [n] bytes from [from] that will be stored at stream offset [base]. */
    fun scan(b: ByteArray, from: Int, n: Int, base: Long) {
        var i = from
        val end = from + n
        if (carryLen > 0) {
            val take = minOf(188 - carryLen, n)
            System.arraycopy(b, i, carry, carryLen, take)
            carryLen += take
            i += take
            if (carryLen < 188) return
            if (carry[0] == SYNC) packet(carry, 0, carryAt) else damage(carryAt, true)
            carryLen = 0
        }
        while (end - i >= 188) {
            if (b[i] != SYNC || (end - i >= 376 && b[i + 188] != SYNC)) {
                // Out of step: garbage between packets.
                damage(base + (i - from), true)
                i++
                continue
            }
            packet(b, i, base + (i - from))
            i += 188
        }
        if (i < end) {
            System.arraycopy(b, i, carry, 0, end - i)
            carryLen = end - i
            carryAt = base + (i - from)
        }
    }

    private fun damage(at: Long, isHard: Boolean) {
        if (damageAt < 0) damageAt = at
        lost++
        if (isHard) hard = true
    }

    private fun packet(p: ByteArray, o: Int, at: Long) {
        val b1 = p[o + 1].toInt() and 0xFF
        val pid = ((b1 and 0x1F) shl 8) or (p[o + 2].toInt() and 0xFF)
        if (pid == 0x1FFF) return
        if (b1 and 0x80 != 0) { if (pid == videoPid || videoPid < 0) damage(at, true); return } // marked broken by the provider
        val pusi = b1 and 0x40 != 0
        val afc = (p[o + 3].toInt() shr 4) and 3
        val cc = p[o + 3].toInt() and 0x0F
        var payload = o + 4
        var rai = false
        var disc = false
        if (afc == 2 || afc == 3) {
            val al = p[o + 4].toInt() and 0xFF
            if (al > 183) { if (pid == videoPid) damage(at, true); return }
            if (al > 0) {
                val flags = p[o + 5].toInt() and 0xFF
                rai = flags and 0x40 != 0
                disc = flags and 0x80 != 0
            }
            payload = o + 5 + al
        }
        if (pusi && afc != 2 && payload < o + 188) {
            if (pid == 0) { readPat(p, payload, o + 188); return }
            if (pid == pmtPid) { readPmt(p, payload, o + 188); return }
        }
        if (pid != videoPid) return
        if (joinPending) { joinPending = false; damage(at, true) }
        if (afc and 1 != 0) {
            if (lastCc >= 0 && !disc && cc != lastCc && cc != ((lastCc + 1) and 15)) damage(at, false)
            lastCc = cc
        }
        if (pusi && (rai || startsKeyPicture(p, payload, o + 188))) keyPicture(at)
    }

    private fun keyPicture(at: Long) {
        // A damaged group (2+ lost packets, or anything marked broken): skip from the damage to this key picture.
        if (damageAt in 0 until at && (lost >= 2 || hard)) {
            synchronized(skips) {
                skips.addLast(Skip(damageAt, at))
                while (skips.size > 500) skips.removeFirst()
            }
            found++
        }
        damageAt = -1L; lost = 0; hard = false
        synchronized(keys) { keys[keyCount % keys.size] = at; keyCount++ }
    }

    private fun readPat(p: ByteArray, payload: Int, end: Int) {
        val t = payload + 1 + (p[payload].toInt() and 0xFF)
        if (t + 8 > end) return
        val len = ((p[t + 1].toInt() and 0x0F) shl 8) or (p[t + 2].toInt() and 0xFF)
        var i = t + 8
        val stop = minOf(t + 3 + len - 4, end)
        while (i + 4 <= stop) {
            val prog = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val pmt = ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
            if (prog != 0) { pmtPid = pmt; return }
            i += 4
        }
    }

    private fun readPmt(p: ByteArray, payload: Int, end: Int) {
        val t = payload + 1 + (p[payload].toInt() and 0xFF)
        if (t + 12 > end) return
        val len = ((p[t + 1].toInt() and 0x0F) shl 8) or (p[t + 2].toInt() and 0xFF)
        val infoLen = ((p[t + 10].toInt() and 0x0F) shl 8) or (p[t + 11].toInt() and 0xFF)
        var i = t + 12 + infoLen
        val stop = minOf(t + 3 + len - 4, end)
        while (i + 5 <= stop) {
            val st = p[i].toInt() and 0xFF
            val pid = ((p[i + 1].toInt() and 0x1F) shl 8) or (p[i + 2].toInt() and 0xFF)
            val esLen = ((p[i + 3].toInt() and 0x0F) shl 8) or (p[i + 4].toInt() and 0xFF)
            if (st == 0x1B || st == 0x24 || st == 0x01 || st == 0x02) {
                if (pid != videoPid) { videoPid = pid; videoType = st; lastCc = -1 }
                return
            }
            i += 5 + esLen
        }
    }

    /** Does this video packet start a key picture (IDR / parameter sets / MPEG-2 sequence header)? */
    private fun startsKeyPicture(p: ByteArray, payload: Int, end: Int): Boolean {
        if (payload + 9 > end || p[payload] != 0.toByte() || p[payload + 1] != 0.toByte() || p[payload + 2] != 1.toByte()) return false
        var i = payload + 9 + (p[payload + 8].toInt() and 0xFF)
        while (i + 3 < end) {
            if (p[i] == 0.toByte() && p[i + 1] == 0.toByte() && p[i + 2] == 1.toByte()) {
                val h = p[i + 3].toInt() and 0xFF
                when (videoType) {
                    0x1B -> { val t = h and 0x1F; if (t == 5 || t == 7) return true }
                    0x24 -> { val t = (h shr 1) and 0x3F; if (t in 16..21 || t == 32 || t == 33) return true }
                    else -> if (h == 0xB3) return true
                }
                i += 3
            } else i++
        }
        return false
    }

    /** Reading at [pos]: the good key picture to continue from, when [pos] is inside a damaged part; else -1. */
    fun skipTarget(pos: Long): Long = synchronized(skips) {
        while (skips.isNotEmpty() && skips.first().to <= pos - 50_000_000L) skips.removeFirst()
        skips.firstOrNull { pos >= it.from && pos < it.to }?.to ?: -1L
    }

    /** The start of the next damaged part after [pos] (so a read stops just before it), or -1. */
    fun nextDamageAfter(pos: Long): Long = synchronized(skips) {
        var best = -1L
        for (s in skips) if (s.from > pos && (best < 0 || s.from < best)) best = s.from
        best
    }

    /** The first key picture stored after [pos], or -1. */
    fun nextKeyPictureAfter(pos: Long): Long = synchronized(keys) {
        val n = minOf(keyCount, keys.size)
        var best = -1L
        for (j in 0 until n) { val k = keys[j]; if (k > pos && (best < 0 || k < best)) best = k }
        best
    }

    companion object {
        private const val SYNC = 0x47.toByte()

        private fun prefs(c: Context) = c.getSharedPreferences("catwatch", Context.MODE_PRIVATE)
        fun on(c: Context): Boolean = prefs(c).getBoolean("on", true)
        fun setOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("on", v).apply()
        fun note(c: Context): Boolean = prefs(c).getBoolean("note", true)
        fun setNote(c: Context, v: Boolean) = prefs(c).edit().putBoolean("note", v).apply()

        /** Today's tally: found ahead, skipped, seconds skipped (list), jumps to live. */
        private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        fun record(c: Context, skippedSec: Double) {
            val p = prefs(c)
            val d = today()
            val e = p.edit()
            if (p.getString("day", "") != d) e.putString("day", d).putInt("skips", 0).putString("secs", "").putInt("live", 0)
            val n = (if (p.getString("day", "") == d) p.getInt("skips", 0) else 0) + 1
            val secs = (if (p.getString("day", "") == d) p.getString("secs", "") ?: "" else "")
            e.putInt("skips", n).putString("secs", (if (secs.isBlank()) "" else "$secs,") + "%.1f".format(java.util.Locale.US, skippedSec))
            e.apply()
        }
        fun recordLiveJump(c: Context) {
            val p = prefs(c)
            val d = today()
            val e = p.edit()
            if (p.getString("day", "") != d) e.putString("day", d).putInt("skips", 0).putString("secs", "").putInt("live", 0)
            e.putInt("live", (if (p.getString("day", "") == d) p.getInt("live", 0) else 0) + 1).apply()
        }
        /** "2 damaged parts skipped (0.8 s and 1.4 s) · 0 jumps to live" for today. */
        fun todayText(c: Context): String {
            val p = prefs(c)
            if (p.getString("day", "") != today()) return "nothing to fix yet today · 0 jumps to live"
            val n = p.getInt("skips", 0)
            val secs = (p.getString("secs", "") ?: "").split(',').filter { it.isNotBlank() }.takeLast(5).joinToString(", ") { "$it s" }
            return "$n damaged part${if (n == 1) "" else "s"} skipped" + (if (secs.isNotBlank()) " ($secs)" else "") + " · ${p.getInt("live", 0)} jumps to live"
        }
    }
}
