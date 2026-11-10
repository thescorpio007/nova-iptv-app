package com.nova.iptv.player

/**
 * "Play through damage" for live MPEG-TS (like VLC): a damaged feed often has jumps in its clock (PCR) or in the
 * time stamps of one stream (PTS / DTS). The player then waits for frames "in the future" (frozen picture, stuck
 * loading). This rewrites the time stamps as the packets arrive so the clock always runs on smoothly:
 *  - a PCR jump (backwards, or more than 3 s forwards) is folded away: the clock just continues;
 *  - a stream whose time stamps drift more than 5 s from the clock is put back next to the clock.
 * Clean channels never trigger any of this (their stamps stay inside the windows), so nothing changes for them.
 * Works on whole 188-byte packets; anything that doesn't look like MPEG-TS is passed on untouched.
 */
class TsClockRepair {
    private val carry = ByteArray(188)
    private var carryLen = 0

    private var havePcr = false
    private var lastPcrOut = 0L      // 90 kHz, 33 bits
    private var pcrOffset = 0L
    private val pidAdjust = HashMap<Int, Long>()

    /** Streams are only re-placed on a channel whose clock really jumped. */
    private val havePcrJumped get() = clockFixes > 0

    /** How many repairs were made (for the report). */
    @Volatile var clockFixes = 0
        private set
    @Volatile var streamFixes = 0
        private set

    /** A new connection to the provider: an unfinished packet from the old one is dropped. */
    fun newConnection() { carryLen = 0 }

    /** Repairs [n] bytes from [from] and hands whole packets (and anything unrecognised) to [out]. */
    fun feed(b: ByteArray, from: Int, n: Int, out: (ByteArray, Int, Int) -> Unit) {
        var i = from
        val end = from + n
        // Finish the packet left over from the last call.
        if (carryLen > 0) {
            val need = 188 - carryLen
            val take = minOf(need, end - i)
            System.arraycopy(b, i, carry, carryLen, take)
            carryLen += take
            i += take
            if (carryLen < 188) return
            if (carry[0] == SYNC) fix(carry, 0)
            out(carry, 0, 188)
            carryLen = 0
        }
        val runStart = i
        while (end - i >= 188) {
            if (b[i] != SYNC || (end - i >= 376 && b[i + 188] != SYNC)) {
                // Out of step: pass bytes on untouched until the next real packet start (two sync bytes 188 apart).
                i++
                continue
            }
            fix(b, i)
            i += 188
        }
        if (i > runStart) out(b, runStart, i - runStart)
        if (i < end) {
            System.arraycopy(b, i, carry, 0, end - i)
            carryLen = end - i
        }
    }

    private fun fix(p: ByteArray, o: Int) {
        val b1 = p[o + 1].toInt() and 0xFF
        val pid = ((b1 and 0x1F) shl 8) or (p[o + 2].toInt() and 0xFF)
        if (pid == 0x1FFF) return
        val pusi = b1 and 0x40 != 0
        val afc = (p[o + 3].toInt() shr 4) and 3
        var payload = o + 4
        if (afc == 2 || afc == 3) {
            val afLen = p[o + 4].toInt() and 0xFF
            if (afLen > 0 && afLen <= 183 && (p[o + 5].toInt() and 0x10) != 0 && afLen >= 7) {
                val pcr = readPcr(p, o + 6)
                var out = wrap(pcr + pcrOffset)
                if (havePcr) {
                    val d = signed(out - lastPcrOut)
                    // Only real, large jumps (backwards more than 2 s, forwards more than 10 s): normal channels are never touched.
                    if (d < -180_000 || d > 900_000) {
                        // Clock jump: continue 40 ms after the last value instead.
                        pcrOffset = wrap(pcrOffset + signed(wrap(lastPcrOut + 3_600) - out))
                        out = wrap(pcr + pcrOffset)
                        clockFixes++
                    }
                }
                if (pcrOffset != 0L) writePcr(p, o + 6, out)
                lastPcrOut = out
                havePcr = true
            }
            payload = o + 5 + afLen
        }
        if (!pusi || afc == 2 || payload + 14 > o + 188 || !havePcr) return
        // PES header: 00 00 01 stream_id …
        if (p[payload] != 0.toByte() || p[payload + 1] != 0.toByte() || p[payload + 2] != 1.toByte()) return
        val sid = p[payload + 3].toInt() and 0xFF
        if (sid == 0xBC || sid == 0xBE || sid == 0xBF || sid == 0xF0 || sid == 0xF1 || sid == 0xFF || sid == 0xF2 || sid == 0xF8) return
        val flags = (p[payload + 7].toInt() and 0xFF) shr 6
        if (flags < 2) return
        val ptsAt = payload + 9
        val pts = readTs(p, ptsAt)
        var adj = pidAdjust[pid] ?: 0L
        var out = wrap(pts + pcrOffset + adj)
        val d = signed(out - lastPcrOut)
        if (havePcrJumped && (d < -900_000 || d > 900_000)) {
            // This stream drifted away from the clock: put it back 0.5 s ahead of the clock (where streams normally sit).
            adj = signed(wrap(lastPcrOut + 45_000) - wrap(pts + pcrOffset))
            pidAdjust[pid] = adj
            out = wrap(pts + pcrOffset + adj)
            streamFixes++
        }
        val shift = wrap(pcrOffset + adj)
        if (shift == 0L) return
        writeTs(p, ptsAt, out, if (flags == 3) 3 else 2)
        if (flags == 3 && ptsAt + 10 <= o + 188) writeTs(p, ptsAt + 5, wrap(readTs(p, ptsAt + 5) + shift), 1)
    }

    private companion object {
        const val SYNC = 0x47.toByte()
        const val MASK = (1L shl 33) - 1
        fun wrap(v: Long) = v and MASK
        /** A 33-bit difference as a signed number. */
        fun signed(v: Long): Long { val w = v and MASK; return if (w >= (1L shl 32)) w - (1L shl 33) else w }

        fun readPcr(p: ByteArray, at: Int): Long =
            ((p[at].toLong() and 0xFF) shl 25) or ((p[at + 1].toLong() and 0xFF) shl 17) or ((p[at + 2].toLong() and 0xFF) shl 9) or
                ((p[at + 3].toLong() and 0xFF) shl 1) or ((p[at + 4].toLong() and 0xFF) shr 7)

        fun writePcr(p: ByteArray, at: Int, base: Long) {
            p[at] = (base shr 25).toByte()
            p[at + 1] = (base shr 17).toByte()
            p[at + 2] = (base shr 9).toByte()
            p[at + 3] = (base shr 1).toByte()
            // Keep the 6 reserved bits and the 9-bit extension.
            p[at + 4] = (((base and 1L) shl 7).toInt() or (p[at + 4].toInt() and 0x7F)).toByte()
        }

        fun readTs(p: ByteArray, at: Int): Long =
            (((p[at].toLong() and 0x0E) shr 1) shl 30) or ((p[at + 1].toLong() and 0xFF) shl 22) or
                (((p[at + 2].toLong() and 0xFE) shr 1) shl 15) or ((p[at + 3].toLong() and 0xFF) shl 7) or
                ((p[at + 4].toLong() and 0xFE) shr 1)

        /** [prefix]: 2 = PTS only, 3 = PTS before DTS, 1 = DTS. */
        fun writeTs(p: ByteArray, at: Int, v: Long, prefix: Int) {
            p[at] = ((prefix shl 4) or (((v shr 30) and 0x07).toInt() shl 1) or 1).toByte()
            p[at + 1] = (v shr 22).toByte()
            p[at + 2] = ((((v shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
            p[at + 3] = (v shr 7).toByte()
            p[at + 4] = (((v and 0x7F).toInt() shl 1) or 1).toByte()
        }
    }
}
