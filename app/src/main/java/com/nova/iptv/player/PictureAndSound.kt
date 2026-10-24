package com.nova.iptv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 🔊 Boost and 🌙 Night mode done inside Nova: the decoded sound is changed before it leaves the app (so a TV box's
 * direct HDMI path can't skip it). Boost multiplies it; Night mode is a compressor (loud parts pulled down, quiet parts
 * lifted); a soft limiter above ~70 % of full scale keeps loud scenes from crackling.
 * Not applied to sound sent untouched to a soundbar (Dolby / DTS passthrough).
 */
@OptIn(UnstableApi::class)
class GainProcessor : BaseAudioProcessor() {
    companion object {
        /** 1.0 = 100 %, 2.0 = 200 %. Changes apply at once. */
        @Volatile var gain = 1f

        /** 🌙 Night mode (sound): loud parts pulled down above [nightThresholdDb], quiet parts lifted a little. */
        @Volatile var night = false
        @Volatile var nightThresholdDb = -18f
        @Volatile var nightRatio = 4f
        @Volatile var nightFast = true
        /** 5.1 sound: more weight on the centre channel (voices). */
        @Volatile var nightDialogue = true
    }

    private var envelope = 0f
    private var currentGain = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush() { envelope = 0f; currentGain = 1f }

    private fun limit(v: Float): Float {
        val a = abs(v)
        if (a <= 0.7f) return v
        val out = 0.7f + 0.3f * tanh((a - 0.7f) / 0.3f)
        return if (v < 0) -out else out
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytes = if (isFloat) 4 else 2
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        val inB = inputBuffer.order(ByteOrder.nativeOrder())
        val g = gain
        val nightOn = night
        if (g == 1f && !nightOn) {
            // Nothing to change: copy as it is.
            out.put(inB)
            out.flip()
            return
        }
        val thr = Math.pow(10.0, nightThresholdDb / 20.0).toFloat()
        val ratio = nightRatio
        // Quiet parts are lifted by half of what loud parts lose, so the overall level stays about the same.
        val makeup = if (nightOn) Math.pow(10.0, (-nightThresholdDb * (1f - 1f / ratio) * 0.35f) / 20.0).toFloat() else 1f
        val attack = 1f - kotlin.math.exp(-1f / (rate * (if (nightFast) 0.003f else 0.015f)))
        val release = 1f - kotlin.math.exp(-1f / (rate * (if (nightFast) 0.15f else 0.40f)))
        val centre = nightOn && nightDialogue && ch == 6
        val frame = FloatArray(ch)
        val frameBytes = ch * bytes
        while (inB.remaining() >= frameBytes) {
            var peak = 0f
            for (c in 0 until ch) {
                var v = if (isFloat) inB.float else inB.short / 32768f
                if (centre) v *= if (c == 2) 1.6f else if (c == 3) 0.7f else 0.85f // 5.1: FL FR FC LFE BL BR
                v *= g
                frame[c] = v
                val a = abs(v)
                if (a > peak) peak = a
            }
            var level = 1f
            if (nightOn) {
                envelope += (peak - envelope) * (if (peak > envelope) attack else release)
                level = makeup
                if (envelope * makeup > thr) {
                    // Above the ceiling: reduce by the ratio (4:1 = every 4 dB above becomes 1 dB).
                    val overDb = 20f * kotlin.math.log10(envelope * makeup / thr)
                    level = makeup * Math.pow(10.0, (-(overDb - overDb / ratio)) / 20.0).toFloat()
                }
                // Glide towards the new level (no clicks).
                currentGain += (level - currentGain) * 0.05f
                level = currentGain
            }
            for (c in 0 until ch) {
                val v = limit(frame[c] * level)
                if (isFloat) out.putFloat(v) else out.putShort((v * 32767f).toInt().toShort())
            }
        }
        // A partial frame (never expected): pass it on unchanged.
        while (inB.hasRemaining()) out.put(inB.get())
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}

/**
 * ☀ Picture brightness done by the player (the box can't change a TV's backlight over HDMI): every frame is multiplied
 * by [gain] (0.5 = darker … 1.5 = brighter). Read for each frame, so moving the bar changes the picture at once.
 */
@OptIn(UnstableApi::class)
object PictureBrightness : RgbMatrix {
    @Volatile var gain = 1f

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val g = gain
        // Brighter: also lift the darkest parts a little, like a TV's brightness control.
        val lift = if (g > 1f) (g - 1f) * 0.06f else 0f
        return floatArrayOf(
            g, 0f, 0f, 0f,
            0f, g, 0f, 0f,
            0f, 0f, g, 0f,
            lift, lift, lift, 1f,
        )
    }
}
