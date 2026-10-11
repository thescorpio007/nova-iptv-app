package com.nova.iptv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TeeDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.nova.iptv.NovaApp
import com.nova.iptv.data.SavedItem
import com.nova.iptv.download.RecorderSink

/** One entry the player can play. */
data class PlayItem(
    val saved: SavedItem,
    val url: String,
    val isLive: Boolean,
    val subtitle: String? = null,
    /** Movie / episode file extension, used for "Download". Null for live and local files. */
    val downloadExt: String? = null,
    /** A past programme of a channel (catch-up). */
    val catchup: Boolean = false,
    /** "Download & watch": play this download's file while it downloads. */
    val downloadId: String? = null,
    /** Opened from another app ("Open with"): not added to Recent / Favorites. */
    val temporary: Boolean = false,
    /** A subtitle file found next to an opened video (same name, .srt / .vtt / .ass). */
    val sidecarSubtitle: String? = null,
    /** An episode: its series (auto-play next episode, remembered per series). */
    val seriesKey: String? = null,
) {
    /** Which "Player selection" setting applies: live, catchup, movie, series or recordings. */
    val kind: String
        get() = when {
            catchup -> "catchup"
            isLive -> "live"
            isLocal -> "recordings"
            saved.type == "episode" -> "series"
            else -> "movie"
        }

    val key: String get() = saved.resumeKey
    val title: String get() = saved.name
    val isLocal: Boolean get() = url.startsWith("file:") || url.startsWith("/") || url.startsWith("content:")
    val isHls: Boolean get() = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
}

/** Hands the list of channels / the movie to the player screen. */
object PlaybackQueue {
    var items: List<PlayItem> = emptyList()
    var index: Int = 0
    /** Live TV group the list comes from (for the ◀ group overlay); null = search, recent… */
    var groupKey: String? = null
    /** An M3U playlist opened from another app: its groups (shown by ◀ instead of your accounts' groups). */
    var playlist: LinkedHashMap<String, List<PlayItem>>? = null
}

/**
 * Safer settings used when a film fails to decode, tried one after the other before giving up:
 * other video decoder, FFmpeg sound decoder first, no audio offload / tunneling.
 */
data class Tweaks(
    val step: Int = 0,
    val softwareVideo: Boolean? = null,
    val ffmpegAudioFirst: Boolean = false,
    val noOffload: Boolean = false,
    val noTunnel: Boolean = false,
)

/** How the player gets its data. */
enum class SourceMode {
    /** Live TV straight from the server, buffered in memory (recording copies the same data). */
    LIVE_DIRECT,
    /** Live MPEG-TS through the disk buffer (pause / rewind / keeps playing through drops). */
    LIVE_RELAY,
    /** Live HLS with video segments stored in the disk cache. */
    LIVE_HLS_DISK,
    /** Movies and episodes from the server, through the rolling disk cache. */
    VOD,
    /** Movies and episodes through the disk read-ahead (segments, one connection). */
    VOD_DISK,
    /** "Download & watch": the download's file, read while it grows. */
    GROWING,
    /** Files already downloaded to the device. */
    LOCAL;

    val isLive: Boolean get() = this == LIVE_DIRECT || this == LIVE_RELAY || this == LIVE_HLS_DISK
}

@OptIn(UnstableApi::class)
object PlayerFactory {


    /**
     * @param relaySource used for [SourceMode.LIVE_RELAY]: reads from the current channel's disk buffer.
     */
    fun create(
        context: Context,
        app: NovaApp,
        mode: SourceMode,
        relaySource: DataSource.Factory? = null,
        tweaks: Tweaks = Tweaks(),
    ): ExoPlayer {
        val s = app.storage
        val live = mode.isLive

        // Rolling buffer:
        //  - cushion: video collected before playback starts / resumes. For live TV this is how
        //    far you sit behind the live edge and what keeps playing during a short drop.
        //  - max: keep reading ahead up to this many seconds (in memory).
        val cushionMs = (s.cushionSeconds * 1000).coerceIn(1000, 60_000)
        // With the disk holding the safety margin, keep only ~20 s in memory (good for 4 GB boxes).
        val diskBacked = mode == SourceMode.VOD_DISK || mode == SourceMode.GROWING
        val maxMs = if (diskBacked) maxOf(20_000, cushionMs + 5_000)
        else maxOf(s.maxBufferSeconds * 1000, cushionMs + 10_000, if (live) s.weakRefillSecs * 1000 + 15_000 else 0)
        val rebufferMs = (cushionMs * 3 / 2).coerceIn(2000, maxMs)
        // Live "fast start": begin with ~2 s near live; the player builds the rest of the cushion while playing.
        val startMs = if (live && s.liveStart == "fast") minOf(2000, cushionMs) else cushionMs

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(maxMs, maxMs, startMs, rebufferMs)
            .setTargetBufferBytes((if (diskBacked) minOf(s.bufferMb, 64) else s.bufferMb).coerceIn(16, 512) * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(if (live) 0 else 30_000, true)
            .build()

        val http = OkHttpDataSource.Factory(app.http).setUserAgent(s.userAgent)

        val dataSource: DataSource.Factory = when (mode) {
            SourceMode.LIVE_DIRECT ->
                DataSource.Factory { TeeDataSource(http.createDataSource(), RecorderSink()) }
            SourceMode.LIVE_RELAY ->
                relaySource ?: http
            SourceMode.LIVE_HLS_DISK -> {
                val cached = CacheDataSource.Factory()
                    .setCache(app.liveHlsCache)
                    .setUpstreamDataSourceFactory(http)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                DataSource.Factory { PlaylistBypassDataSource(http.createDataSource(), cached.createDataSource()) }
            }
            SourceMode.VOD ->
                CacheDataSource.Factory()
                    .setCache(app.vodCache)
                    .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, http))
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            SourceMode.VOD_DISK, SourceMode.GROWING ->
                relaySource ?: DefaultDataSource.Factory(context, http)
            SourceMode.LOCAL ->
                DefaultDataSource.Factory(context)
        }

        // IPTV MPEG-TS streams often start without a clean key frame; these flags let
        // playback start immediately instead of failing.
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)

        val mediaSources = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(RetryPolicy(live))

        // Decoders: the box's hardware first for video (4K/HDR). For audio, either the box first
        // (keeps Dolby/DTS passthrough to a soundbar) or the built-in FFmpeg decoders first, which
        // play DTS, AC3, E-AC3, TrueHD, MP2 … on any device.
        // Hardware decoder (the box's chip, needed for 4K/HDR) or software decoders first.
        val softwareFirst = tweaks.softwareVideo ?: (s.videoDecoder == "software")
        val codecSelector = MediaCodecSelector { mime, secure, tunneling ->
            val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            if (softwareFirst) all.sortedBy { if (it.softwareOnly) 0 else 1 } else all
        }
        val renderers = DefaultRenderersFactory(context)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (s.audioDecoder == "ffmpeg" || tweaks.ffmpegAudioFirst) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )

        // Tunneled playback (optional): smoother video and tighter audio sync on many
        // Amlogic TV boxes, but a few streams misbehave with it, so it is a setting.
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(s.tunneling && !tweaks.noTunnel)
                    // Try 4K / high-bitrate / HDR tracks even when the device reports it might not cope,
                    // instead of refusing the video.
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setAudioOffloadPreferences(
                        TrackSelectionParameters.AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(
                                if (s.audioOffload && !tweaks.noOffload) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                                else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                            )
                            .build()
                    )
                    .build()
            )
        }

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /** Retries failed downloads quietly with a growing pause before giving up. */
    private class RetryPolicy(private val live: Boolean) : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val base = super.getRetryDelayMsFor(loadErrorInfo)
            if (base == C.TIME_UNSET) return C.TIME_UNSET
            return minOf(loadErrorInfo.errorCount * 1000L, 5000L)
        }

        // Live: give up quickly so a dead channel can be detected and skipped.
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (live) 3 else 6
    }
}
