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
 * 🔊 Boost done inside Nova: the decoded sound is multiplied before it leaves the app (so a TV box's direct HDMI
 * path can't skip it), with a soft limiter above ~70 % of full scale so loud scenes don't crackle.
 * Not applied to sound sent untouched to a soundbar (Dolby / DTS passthrough).
 */
@OptIn(UnstableApi::class)
class GainProcessor : BaseAudioProcessor() {
    companion object {
        /** 1.0 = 100 %, 2.0 = 200 %. Changes apply at once. */
        @Volatile var gain = 1f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

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
        val g = gain
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val inF = inputBuffer.order(ByteOrder.nativeOrder())
            while (inF.remaining() >= 4) out.putFloat(if (g == 1f) inF.float else limit(inF.float * g))
        } else {
            val inS = inputBuffer.order(ByteOrder.nativeOrder())
            while (inS.remaining() >= 2) {
                val s = inS.short
                out.putShort(if (g == 1f) s else (limit(s / 32768f * g) * 32767f).toInt().toShort())
            }
        }
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
