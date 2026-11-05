package com.nova.iptv.download

import java.io.File

/** Looks at the first bytes of a saved file: is it really video? Returns what is wrong, or null when it looks fine. */
object FileCheck {
    fun problem(f: File): String? {
        val size = f.length()
        if (size == 0L) return "the file is empty"
        val head = ByteArray(minOf(size, 4096L).toInt())
        runCatching { f.inputStream().use { var n = 0; while (n < head.size) { val r = it.read(head, n, head.size - n); if (r < 0) break; n += r } } }
            .onFailure { return null }
        val text = String(head, Charsets.ISO_8859_1).trimStart('﻿', ' ', '\r', '\n', '\t').lowercase()
        if (text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("<?xml") || text.startsWith("<head") || text.startsWith("<body"))
            return "the server sent a web page"
        if (text.startsWith("#extm3u")) return "the server sent a playlist, not the video"
        if (text.startsWith("{") || text.startsWith("[")) return "the server sent a message, not the video"
        if (looksVideo(head)) return null
        return if (size < 200_000) "only ${size / 1024} KB and not a video format" else null
    }

    private fun looksVideo(b: ByteArray): Boolean {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        // MPEG-TS: sync byte every 188 bytes (sometimes after a few bytes of junk).
        for (start in 0 until minOf(188, b.size)) if (at(start) == 0x47 && at(start + 188) == 0x47 && (b.size < start + 377 || at(start + 376) == 0x47)) return true
        if (at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3) return true // MKV / WebM
        if (b.size >= 8 && String(b, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "moov", "mdat", "free", "skip", "wide")) return true // MP4
        if (at(0) == 'F'.code && at(1) == 'L'.code && at(2) == 'V'.code) return true
        if (at(0) == 0 && at(1) == 0 && at(2) == 1 && at(3) == 0xBA) return true // MPEG-PS
        if (at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code) return true // AVI
        return false
    }
}
