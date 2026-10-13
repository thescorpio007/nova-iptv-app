package com.nova.iptv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** "4K", "FHD 1080p", "HD 720p", "SD 576p" + tags (HDR10, Dolby Vision, HEVC, 50 fps) from the video really playing. */
object VideoBadge {
    data class Badge(val label: String, val color: Int, val textColor: Int, val tags: List<String>, val line: String)

    fun of(f: Format?): Badge? {
        if (f == null || f.width <= 0 || f.height <= 0) return null
        val w = f.width
        val h = f.height
        // Wide films (3840×1600) are still 4K: look at the width too.
        val (label, color, text) = when {
            w >= 7000 || h >= 4000 -> Triple("8K", 0xFFE8A317.toInt(), 0xFF2A1A00.toInt())
            w >= 3400 || h >= 2000 -> Triple("4K", 0xFFF5C542.toInt(), 0xFF2A1A00.toInt())
            w >= 1800 || h >= 1000 -> Triple("FHD 1080p", 0xFF2EE66B.toInt(), 0xFF06210F.toInt())
            w >= 1200 || h >= 700 -> Triple("HD 720p", 0xFF4AA3E8.toInt(), 0xFF04172A.toInt())
            else -> Triple("SD ${h}p", 0xFF8A93A3.toInt(), 0xFF111111.toInt())
        }
        val tags = ArrayList<String>()
        val dv = f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dvh") == true || f.codecs?.startsWith("dav1") == true
        when {
            dv -> tags += "DOLBY VISION"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> tags += "HDR10"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> tags += "HLG"
        }
        codecName(f)?.let { tags += it }
        val fps = f.frameRate.takeIf { it > 0 }?.let { Math.round(it) }
        val line = "${w}×$h" + (fps?.let { " · $it fps" } ?: "") + (f.bitrate.takeIf { it > 0 }?.let { " · %.1f Mbit/s".format(it / 1_000_000.0) } ?: "")
        return Badge(label, color, text, tags, line)
    }

    fun codecName(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_MP4V -> "MPEG-4"
        MimeTypes.VIDEO_DOLBY_VISION -> if (f.codecs?.startsWith("dav1") == true) "AV1" else "HEVC"
        else -> null
    }

    /** The full details for "ℹ Video info". */
    fun details(video: Format?, audio: Format?, decoder: String?): String {
        val b = of(video)
        val sb = StringBuilder()
        if (video == null || b == null) sb.append("Video: not known yet (still starting)\n")
        else {
            val quality = when {
                b.label.startsWith("8K") -> "8K"; b.label.startsWith("4K") -> "4K Ultra HD"
                b.label.startsWith("FHD") -> "Full HD 1080p"; b.label.startsWith("HD") -> "HD 720p"; else -> "SD (${video.height}p)"
            }
            sb.append("Quality:   $quality\n")
            val ratio = video.width.toDouble() / video.height
            sb.append("Picture:   ${video.width} × ${video.height} (%.2f:1)\n".format(ratio))
            val hdr = when {
                b.tags.contains("DOLBY VISION") -> "Dolby Vision" + (video.codecs?.let { " ($it)" } ?: "")
                b.tags.contains("HDR10") -> "HDR10"
                b.tags.contains("HLG") -> "HLG"
                else -> "no (SDR)"
            }
            sb.append("HDR:       $hdr\n")
            sb.append("Video:     ${codecName(video) ?: video.sampleMimeType}" + (video.frameRate.takeIf { it > 0 }?.let { " · ${Math.round(it)} fps" } ?: "") + "\n")
            if (video.bitrate > 0) sb.append("Speed:     %.1f Mbit/s\n".format(video.bitrate / 1_000_000.0))
        }
        if (audio != null) {
            val ch = when (audio.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (audio.channelCount > 0) "${audio.channelCount} ch" else "" }
            sb.append("Audio:     " + listOfNotNull(audio.language?.uppercase(), audio.sampleMimeType?.substringAfter('/')?.uppercase(), ch.ifBlank { null },
                audio.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbit/s" }).joinToString(" · ") + "\n")
        }
        decoder?.let { sb.append("Played by: ${if (it.startsWith("c2.android") || it.startsWith("OMX.google")) "software decoder" else "hardware decoder"} ($it)\n") }
        return sb.toString().trimEnd()
    }
}

/** The badge row shown in the player: coloured quality chip + tags + size. */
class VideoBadgeRow(val root: android.widget.LinearLayout, private val chip: android.widget.TextView, private val rest: android.widget.TextView) {
    fun show(b: VideoBadge.Badge?) {
        if (b == null) { root.visibility = android.view.View.GONE; return }
        root.visibility = android.view.View.VISIBLE
        chip.text = b.label
        chip.setTextColor(b.textColor)
        (chip.background as? android.graphics.drawable.GradientDrawable)?.setColor(b.color)
        rest.text = (b.tags.joinToString("") { "  $it  ·" } + "  " + b.line).trim()
    }

    companion object {
        fun make(ctx: android.content.Context, dp: (Int) -> Int): VideoBadgeRow {
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
                visibility = android.view.View.GONE
            }
            val chip = android.widget.TextView(ctx).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xFF2EE66B.toInt()) }
            }
            val rest = android.widget.TextView(ctx).apply {
                setTextColor(0xFFE0E6EE.toInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
            }
            row.addView(chip); row.addView(rest)
            return VideoBadgeRow(row, chip, rest)
        }
    }
}
