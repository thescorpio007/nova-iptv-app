package com.nova.iptv.player

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nova.iptv.NovaApp
import com.nova.iptv.data.Folders
import com.nova.iptv.data.RemoteKeys
import com.nova.iptv.data.StreamCheck
import com.nova.iptv.data.PlaybackLog
import com.nova.iptv.data.XtreamApi
import com.nova.iptv.download.DownloadCenter
import com.nova.iptv.download.Recorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var app: NovaApp
    private var player: ExoPlayer? = null
    private var playerMode: SourceMode? = null
    /** Safer settings tried after a decoding error (reset for each new video). */
    private var tweaks = Tweaks()
    private var playerTweaks = Tweaks()
    private var videoDecoderSwitched = false
    private var audioFixTried = 0
    private var current: PlayItem? = null
    private var relay: LiveRelay? = null
    /** Disk read-ahead of the current film / episode (Settings → Player). */
    private var readAhead: ReadAheadBuffer? = null
    private var hlsAhead: HlsReadAhead? = null

    // 🔊 Volume boost: Nova's own gain + limiter in the sound path ([GainProcessor]), 100–200 %.
    /** ☀ The picture brightness effect is in the player (needed once the brightness leaves "normal"). */
    private var brightnessInstalled = false
    /** "This video only" level (not saved); null = use the saved level. */
    private var boostSession: Int? = null
    private var boostKeyOf: String? = null
    private var boostBadge: TextView? = null

    // 🌙 Night mode (sound): the player button switches it for what is playing (else the automatic setting decides).
    private var nightSession: Boolean? = null
    private var nightKeyOf: String? = null
    private var nightPill: TextView? = null

    // ⬛ Hidden picture: a black cover with a fake "signal error"; the video keeps playing with its sound.
    private var pictureHidden = false
    private var hiddenAt = 0L
    private var okSinglePending: Runnable? = null
    private var hideMuted = false
    private var pendingUnhide: Runnable? = null
    private var autoUnhide: Runnable? = null
    /** "Show again by itself" for the current hide (ms, 0 = off); each new hide starts at 15 s. */
    private var hideAutoMs = 15_000L
    private var hideSkipTask: Runnable? = null
    private var hideWaitReady: Runnable? = null
    private val hideBadgeTask = Runnable { boostBadge?.visibility = View.GONE }
    private lateinit var hideCover: FrameLayout
    private lateinit var hideTitle: TextView
    private lateinit var hideLeft: TextView
    private lateinit var hideRight: TextView
    private lateinit var hideProgress: View
    private lateinit var hideTrack: FrameLayout
    private var spaceWarned = false

    private lateinit var playerView: PlayerView
    private lateinit var infoBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var liveBadge: VideoBadgeRow
    /** Name of the video decoder in use (for "ℹ Video info"). */
    private var videoDecoderName: String? = null
    private lateinit var epgView: TextView
    private lateinit var timeView: TextView
    private lateinit var statusView: TextView
    private lateinit var clockView: TextView
    private lateinit var aspectButton: TextView
    private lateinit var favButton: TextView
    private lateinit var similarButton: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var recButton: TextView
    private lateinit var rewindButton: TextView
    private lateinit var liveButton: TextView
    private lateinit var downloadButton: TextView
    private lateinit var externalButton: TextView
    private var externalSuggested = false

    // Remote control
    /**
     * Holds the focus even after a mouse click / touch ("touch mode"). Without it Android swallows the
     * first arrow press after a click just to leave touch mode, so ▲▼ seemed not to work.
     */
    private lateinit var keySink: View
    private lateinit var channelPanel: LinearLayout
    private lateinit var channelListView: android.widget.ListView
    private lateinit var numberView: TextView
    private var typedNumber = ""
    private val numberTask = Runnable { jumpToTypedNumber() }
    private var previousIndex = -1
    private var lastBackAt = 0L
    /** Keys whose press Nova handled, so their release doesn't also click something. */
    private val swallowUp = HashSet<Int>()

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var everReady = false
    private var autoSkips = 0
    private var lastDirection = 1
    private var epgJob: Job? = null

    private var reconnectTask: Runnable? = null
    private val startTimeoutTask = Runnable {
        if (!everReady) {
            val item = current
            // A repair restarted the picture on the video already stored on disk: try that once more (your place is
            // kept) instead of a fresh start at live.
            if (item != null && storedRestart && relay != null && storedRestartTries < 2) {
                storedRestartTries++
                logHealth(item, "restart on stored video took long", "tried again on stored video (cushion kept)")
                restartOnStoredVideo(item, newPlayer = storedRestartTries == 2)
                return@Runnable
            }
            if (item == null || !startRetry(item, "didn't start in time")) channelFailed("didn't start in time")
        }
    }
    /** The picture is being restarted on the stored video (a repair), and how many times that was tried again. */
    private var storedRestart = false
    private var storedRestartTries = 0
    /** A film resumed at a saved place that never started: started again from the beginning. */
    private var resumeFallbackDone = false
    private val resumeTimeoutTask = Runnable {
        val item = current ?: return@Runnable
        val p = player ?: return@Runnable
        if (everReady || item.isLive || resumeFallbackDone) return@Runnable
        resumeFallbackDone = true
        val at = app.storage.getPosition(item.key)
        app.storage.setPosition(item.key, 0)
        logHealth(item, "didn't start at the saved place ${formatDuration(at)}", "started from the beginning")
        Toast.makeText(this, "Couldn't resume at ${formatDuration(at)} — playing from the start", Toast.LENGTH_LONG).show()
        p.setMediaItem(buildMediaItem(item))
        p.prepare()
        p.playWhenReady = true
    }
    /** Start-up patience: pauses before each new attempt (a channel nobody watched may need a few seconds). */
    private val startDelays = longArrayOf(1000, 2000, 4000, 6000, 8000)
    private var startAttempts = 0
    private var startOtherTried = false

    // Weak signal: the channel's real bitrate, how fast video arrives, and the "pause & refill" state.
    private var mediaBps = 0.0
    private var bpsSampleBytes = -1L
    private var bpsSampleBuf = -1L
    private val arrivals = ArrayDeque<Pair<Long, Long>>()
    private var arrivalBps = 0.0
    private var weakChannel = false
    private var refilling = false
    private val rebuffers = ArrayDeque<Long>()
    private var notArrivingSince = 0L
    private val tickTask = object : Runnable {
        override fun run() {
            updateTimeLabel()
            updateClock()
            measureSignal()
            refillTick()
            watchdogTick()
            cushionTick()
            // Disk buffer: a channel the server refuses (404, 403…) is dead — no need to wait for the timeout.
            relay?.let { r ->
                val item = current
                if (item != null && item.isLive && !everReady && statusView.visibility != View.VISIBLE && r.lastHttpCode in listOf(401, 403, 404, 410) && r.consecutiveFailures >= 2) {
                    handler.removeCallbacks(startTimeoutTask)
                    channelFailed("server answered HTTP ${r.lastHttpCode}")
                }
            }
            if (::vod.isInitialized) {
                updateDiskStatus()
                vod.update()
            }
            if (pictureHidden) updateHideCover()
            badgeWithControls()
            nextEpisodeTick()
            // The channel list keeps the remote's focus while it is open.
            if (recentBarOpen && !recentBar.hasFocus()) recentRow.getChildAt(0)?.requestFocus()
            // Controls hid by themselves: give the focus back to the key holder.
            if (::keySink.isInitialized && !channelPanelOpen && !recentBarOpen && !vod.isPanelOpen) {
                val f = currentFocus
                if (f == null || !f.isShown) keySink.requestFocus()
            }
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * Films / episodes read from disk: the read-ahead segments, or the growing download file ("Download & watch").
     * Anything else (an added subtitle file) goes to the normal source.
     */
    private val diskSource = DataSource.Factory {
        val item = current ?: throw IllegalStateException("Nothing playing")
        // A streaming (HLS) download watched while it runs: the list of saved parts + the joined file.
        if (item.url.startsWith("nova-dl://") && item.downloadId != null) {
            return@Factory GrowingHlsList(item.downloadId, applicationContext, app.http, app.storage.userAgent)
        }
        val other = androidx.media3.datasource.DefaultDataSource.Factory(
            this, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(app.http).setUserAgent(app.storage.userAgent),
        ).createDataSource()
        val id = item.downloadId
        RoutingDataSource(item.url, {
            if (id != null) GrowingFileDataSource(id, applicationContext, app.http, app.storage.userAgent)
            else ReadAheadDataSource(readAhead ?: throw java.io.IOException("No read-ahead buffer"))
        }, other)
    }

    /** Lets the reused player read from whichever channel's disk buffer is current. */
    private val relaySource = DataSource.Factory {
        val r = relay ?: throw IllegalStateException("No live buffer")
        r.dataSourceFactory().createDataSource()
    }

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private val resizeNames = arrayOf("Fit", "Zoom", "Stretch")
    private var resizeIndex = 0

    /** Film / series controls (brightness, volume, ±10 s, tracks, subtitles…). */
    private lateinit var vod: VodControls

    /** Subtitle file the viewer added for the current video. */
    private var extraSubtitle: android.net.Uri? = null

    private val subtitlePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) addSubtitle(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as NovaApp
        if (PlaybackQueue.items.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startOrientation()
        hideSystemBars()
        buildViews()
        watchNetwork()
        // Back from any source (remote, gesture, system): close what is open first.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    channelPanelOpen -> closeChannelPanelToInfo()
                    recentBarOpen -> hideRecentBar()
                    vod.isPanelOpen -> { vod.hidePanel(); keySink.requestFocus() }
                    else -> handleBack(current)
                }
            }
        })
        startPlayback(PlaybackQueue.index)
        handler.post(tickTask)
        startCastReceiver()
        applyOrientationLayout()
        playerView.postDelayed({ applyOrientationLayout() }, 300)
    }

    // ------------------------------------------------------------------ 📱 phone upright (YouTube style) / sideways

    private var portrait = false
    private var layoutDone = false
    /** ⟲ Rotate or "Start in portrait": held until the phone is physically turned the same way, then it follows the phone again. */
    private var manualLock = false
    private var lockedPortrait = false
    private var startLock = false
    private var openedAt = 0L
    private var orientationListener: android.view.OrientationEventListener? = null
    private var portraitBox: LinearLayout? = null
    private var portraitList: android.widget.ListView? = null
    private var infoBarIndex = -1
    private var infoBarParams: ViewGroup.LayoutParams? = null

    private fun phoneMode(): String =
        if (!PlayerUi.compact(this)) "tv" else app.storage.phoneOrientation

    private fun baseOrientation(): Int = when (phoneMode()) {
        "tv", "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
    }

    private fun startOrientation() {
        openedAt = System.currentTimeMillis()
        val mode = phoneMode()
        if (mode == "portrait") {
            startLock = true
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else requestedOrientation = baseOrientation()
        if (mode == "tv") return
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val upright = deg < 25 || deg > 335 || deg in 155..205
                val sideways = deg in 65..115 || deg in 245..295
                when {
                    // "Start in portrait": turning the phone sideways goes landscape.
                    startLock && sideways && System.currentTimeMillis() - openedAt > 1500 -> {
                        startLock = false; requestedOrientation = baseOrientation()
                    }
                    // ⟲ Rotate: once the phone is held the same way, it follows the phone again.
                    manualLock && ((lockedPortrait && upright) || (!lockedPortrait && sideways)) -> {
                        manualLock = false; requestedOrientation = baseOrientation()
                    }
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    /** ⟲ Rotate: upright ⇄ sideways at once (even with the phone's auto-rotate off). */
    private fun rotateNow() {
        val nowPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        startLock = false
        manualLock = true
        lockedPortrait = !nowPortrait
        requestedOrientation = if (nowPortrait) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout()
        // The camera / status bar sizes are known once the screen has turned: place again.
        playerView.postDelayed({ applyOrientationLayout() }, 300)
    }

    /** Upright on a phone: video on top (16:9); under it the info + buttons, and for live TV the channels of the list. */
    private fun applyOrientationLayout() {
        if (!::playerView.isInitialized || !::infoBar.isInitialized) return
        val p = PlayerUi.compact(this) && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val w = resources.displayMetrics.widthPixels
        val vh = w * 9 / 16
        val live = current?.isLive == true
        val root = playerView.parent as? FrameLayout ?: return
        portrait = p
        layoutDone = true
        // Upright: the video starts below the front camera / status bar area (never under the camera hole).
        val ins = androidx.core.view.ViewCompat.getRootWindowInsets(root)
        val topInset = if (p && ins != null) maxOf(
            ins.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
            ins.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top,
        ) else 0
        playerView.layoutParams = if (p) FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, vh, Gravity.TOP).apply { topMargin = topInset }
            else FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (::vod.isInitialized) vod.setPortrait(p && !live, vh, (topInset - vod.view.paddingTop).coerceAtLeast(0))
        // Live TV upright: the info bar moves under the video, with the channel list below it.
        if (p && live) {
            val box = portraitBox ?: LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF0B0E14.toInt())
            }.also { portraitBox = it }
            if (infoBar.parent !== box) {
                infoBarIndex = root.indexOfChild(infoBar)
                infoBarParams = infoBar.layoutParams
                root.removeView(infoBar)
                box.removeAllViews()
                box.addView(infoBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(buildPortraitList(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                root.addView(box, infoBarIndex.coerceAtLeast(0))
            }
            box.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = vh + topInset }
            box.visibility = View.VISIBLE
            infoBar.visibility = View.VISIBLE
            infoBar.background = null
            refreshPortraitList()
        } else {
            val box = portraitBox
            if (box != null && infoBar.parent === box) {
                box.removeView(infoBar)
                root.removeView(box)
                root.addView(infoBar, infoBarIndex.coerceIn(0, root.childCount), infoBarParams)
                infoBar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xDD000000.toInt(), 0x00000000))
                infoBar.visibility = if (live && playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
            }
        }
        updateClock()
    }

    private fun buildPortraitList(): View {
        val list = android.widget.ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(0xFF161B24.toInt())
            dividerHeight = 1
            choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
            setSelector(android.graphics.drawable.ColorDrawable(0xFF16261C.toInt()))
            setOnItemClickListener { _, _, pos, _ ->
                if (pos != PlaybackQueue.index && pos in PlaybackQueue.items.indices) {
                    PlaybackQueue.index = pos
                    startPlayback(pos)
                }
            }
        }
        portraitList = list
        return list
    }

    private fun refreshPortraitList() {
        val list = portraitList ?: return
        val items = PlaybackQueue.items
        list.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int) = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
                val t = (convert as? TextView) ?: TextView(this@PlayerActivity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val on = i == PlaybackQueue.index
                t.text = "${i + 1}.  ${items[i].title}" + if (on) "  ▶" else ""
                t.setTextColor(if (on) 0xFF2EE66B.toInt() else Color.WHITE)
                return t
            }
        }
        val idx = PlaybackQueue.index
        if (idx in items.indices) { list.setItemChecked(idx, true); list.setSelection((idx - 2).coerceAtLeast(0)) }
    }

    // ================================================================== views

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun pill(label: String, onClick: () -> Unit): TextView {
        fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(color)
            if (stroke != 0) setStroke(dp(2), stroke)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xFF59606A.toInt(), 0xFF2EE66B.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF59606A.toInt(), 0))
            addState(intArrayOf(), shape(0x33FFFFFF, 0))
        }
        val small = PlayerUi.compact(this)
        return PillText(this, small).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 14f)
            background = states
            if (small) setPadding(dp(9), dp(6), dp(9), dp(6)) else setPadding(dp(14), dp(8), dp(14), dp(8))
            isFocusable = true
            isClickable = true
            setOnClickListener {
                onClick()
                playerView.showController()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(if (small) 5 else 8) }
        }
    }

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        epgView = TextView(this).apply {
            setTextColor(0xFFCFD8DC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, 0)
        }
        timeView = TextView(this).apply {
            setTextColor(0xFF2EE66B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(6))
            visibility = View.GONE
        }

        val backPill = pill("← Back") { finish() }
        prevButton = pill("◀ Prev") { zap(-1, auto = false) }
        nextButton = pill("Next ▶") { zap(1, auto = false) }
        rewindButton = pill("⏪ 30s") { timeshift(-30) }
        liveButton = pill("Live ⏭") { goLive() }
        recButton = pill("● Rec") { toggleRecording() }
        downloadButton = pill("⬇ Download") { downloadCurrent() }
        externalButton = pill("↗ External player") { openExternal() }
        val castButton = pill("📺 Play on TV") { sendToTv() }.also { if (com.nova.iptv.data.Cast.isTv(this)) it.visibility = View.GONE }
        val rotateButton = pill("⟲ Rotate") { rotateNow() }.also { if (!PlayerUi.compact(this)) it.visibility = View.GONE }
        aspectButton = pill("Aspect: Fit") { cycleAspect() }
        favButton = pill("♡ Favorite") { toggleFavorite() }
        val tracksButton = pill("CC Audio & subtitles") { vod.openPanel() }
        val infoButton = pill("ℹ Video info") { showVideoInfo() }
        similarButton = pill("≈ Similar") { showSimilar(auto = false) }
        val boostButton = pill("🔊 Boost") { showBoost() }
        val nightButton = pill("🌙 Night mode: Off") { toggleNight() }.also { nightPill = it }
        val hideButton = pill("⬛ Hide picture") { hidePicture() }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            listOf(backPill, prevButton, nextButton, rewindButton, liveButton, similarButton, recButton, tracksButton, nightButton, boostButton, hideButton, infoButton, favButton, externalButton, castButton, rotateButton, aspectButton, downloadButton)
                .forEach { addView(it) }
        }
        val buttonScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        }
        // Option A: the row always fits the screen width (smaller text → short labels → icons).
        RowFit.attach(buttonScroller, buttons, 14f, 12f)

        infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xDD000000.toInt(), 0x00000000),
            )
            addView(titleView)
            liveBadge = VideoBadgeRow.make(this@PlayerActivity, ::dp)
            addView(liveBadge.root)
            addView(epgView)
            addView(timeView)
            addView(buttonScroller)
            addView(linkLine())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
        }

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xCC000000.toInt())
            }
            visibility = View.GONE
            isClickable = true
            setOnClickListener { if (externalSuggested) openExternal() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }

        val surfaceLayout = if (app.storage.videoSurface == "texture") com.nova.iptv.R.layout.player_view_texture
        else com.nova.iptv.R.layout.player_view_surface
        playerView = (layoutInflater.inflate(surfaceLayout, root, false) as PlayerView).apply {
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 5000
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    infoBar.visibility = if (portrait && current?.isLive == true) View.VISIBLE else visibility
                    updateClock()
                    if (visibility != View.VISIBLE && ::keySink.isInitialized && ::vod.isInitialized && !channelPanelOpen && !vod.isPanelOpen) keySink.requestFocus()
                }
            )
        }

        // Clock in the corner chosen in Settings.
        val pos = app.storage.clockPosition
        clockView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xAA000000.toInt())
            }
            visibility = if (pos == "off") View.GONE else View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                when (pos) {
                    "tl" -> Gravity.TOP or Gravity.START
                    "bl" -> Gravity.BOTTOM or Gravity.START
                    "br" -> Gravity.BOTTOM or Gravity.END
                    else -> Gravity.TOP or Gravity.END
                },
            ).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) }
        }

        vod = VodControls(
            activity = this,
            playerView = playerView,
            storage = app.storage,
            player = { player },
            onBack = { finish() },
            onAspect = { cycleAspect(); resizeNames[resizeIndex] },
            onLoadSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
            onBrightness = { v -> setPictureBrightness(v) },
            sound = object : VodControls.SoundHooks {
                override fun nightOn() = this@PlayerActivity.nightOn()
                override fun setNightOn(on: Boolean) { nightSession = on; applyNight() }
                override fun nightChanged() = applyNight()
                override fun boost() = boostLevel()
                override fun setBoost(percent: Int) { boostSession = percent; applyBoost() }
            },
            extraButtons = listOf(
                "🌙 Night mode: Off" to { toggleNight() },
                "🔊 Boost" to { showBoost() },
                "⬛ Hide picture" to { hidePicture() },
                "⬇ Download" to { downloadCurrent() },
                "ℹ Video info" to { showVideoInfo() },
                "♡ Favorite" to { toggleFavorite() },
                "↗ External player" to { openExternal() },
                "📺 Play on TV" to { sendToTv() },
                "⟲ Rotate" to { rotateNow() },
            ),
        )
        if (!PlayerUi.compact(this)) vod.extraViews["⟲ Rotate"]?.visibility = View.GONE
        if (com.nova.iptv.data.Cast.isTv(this)) vod.extraViews["📺 Play on TV"]?.visibility = View.GONE
        vod.addBottomView(linkLine())
        // Keep the controls clear of the camera cut-out.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(vod.view) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cut.left, cut.top, cut.right, cut.bottom)
            insets
        }

        root.addView(playerView)
        root.addView(vod.view)
        // Live TV: the ⚙ and CC at the bottom right open Nova's settings panel (audio, subtitles, night mode…).
        listOf(androidx.media3.ui.R.id.exo_settings, androidx.media3.ui.R.id.exo_subtitle).forEach { id ->
            playerView.findViewById<View>(id)?.setOnClickListener { playerView.hideController(); vod.openPanel() }
        }
        boostBadge = TextView(this).apply {
            setTextColor(0xFF003311.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF39D98A.toInt()) }
            setPadding(dp(12), dp(5), dp(12), dp(5))
            visibility = View.GONE
        }
        root.addView(boostBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(18); rightMargin = dp(24)
        })
        root.addView(buildHideCover(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(infoBar)
        root.addView(statusView)
        root.addView(clockView)
        root.addView(vod.panel)
        buildChannelPanel(root)
        buildRecentBar(root)
        // Status dots: redraw the open channel list when a check finishes.
        lifecycleScope.launch {
            StreamCheck.version.collect {
                if (channelPanelOpen) { refreshOverlayCursor(); updateOverlayTitle() }
            }
        }
        numberView = TextView(this).apply {
            setTextColor(0xFF2EE66B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(8), dp(22), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xDD000000.toInt()) }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
            ).apply { setMargins(0, dp(60), dp(32), 0) }
        }
        root.addView(numberView)
        nextCard = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE6101A2E.toInt())
                setStroke(dp(2), 0xFF2EE66B.toInt())
            }
            visibility = View.GONE
            isClickable = true
            setOnClickListener { playNextEpisodeNow() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(0, 0, dp(32), dp(110)) }
        }
        root.addView(nextCard)
        keySink = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)
        }
        root.addView(keySink)
        setContentView(root)
        keySink.requestFocus()
    }

    // ================================================================== playback

    private fun modeFor(item: PlayItem): SourceMode = when {
        item.isLocal -> SourceMode.LOCAL
        item.downloadId != null -> SourceMode.GROWING
        !item.isLive && app.storage.readAheadMb > 0 && item.url.startsWith("http", ignoreCase = true) && item.isHls -> SourceMode.VOD_HLS_DISK
        !item.isLive && app.storage.readAheadMb > 0 && item.url.startsWith("http", ignoreCase = true) -> SourceMode.VOD_DISK
        !item.isLive -> SourceMode.VOD
        !app.storage.liveDiskBuffer -> SourceMode.LIVE_DIRECT
        item.isHls -> SourceMode.LIVE_HLS_DISK
        else -> SourceMode.LIVE_RELAY
    }

    private fun ensurePlayer(mode: SourceMode): ExoPlayer {
        val existing = player
        if (existing != null && playerMode == mode && playerTweaks == tweaks) return existing
        existing?.release()
        val source = when (mode) {
            SourceMode.VOD_DISK, SourceMode.GROWING -> diskSource
            else -> relaySource
        }
        val p = PlayerFactory.create(this, app, mode, source, tweaks)
        playerTweaks = tweaks
        p.addListener(listener)
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoderName = decoderName
            }
        })
        updateBadge()
        p.addAnalyticsListener(decoderLogger)
        player = p
        playerMode = mode
        playerView.player = p
        brightnessInstalled = false
        if (PictureBrightness.gain != 1f) installBrightness(p)
        applyBoost()
        return p
    }

    private fun buildMediaItem(item: PlayItem): MediaItem =
        MediaItem.Builder()
            .setUri(item.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
            .apply {
                if (item.isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
                // Growing download list: never speed up to "catch up" with its end.
                if (item.url.startsWith("nova-dl://")) setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()
                )
                (extraSubtitle ?: item.sidecarSubtitle?.let { android.net.Uri.parse(it) })?.let { sub ->
                    val name = sub.toString().lowercase()
                    val mime = when {
                        name.contains(".vtt") -> MimeTypes.TEXT_VTT
                        name.contains(".ass") || name.contains(".ssa") -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }
                    setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(sub)
                                .setMimeType(mime)
                                .setLabel("Added subtitles")
                                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                                .build()
                        )
                    )
                }
                if (item.isLive) {
                    val offset = maxOf(app.storage.cushionSeconds * 1000L, 3000L)
                    setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(offset).build())
                }
            }
            .build()

    private fun startPlayback(index: Int) = startPlayback(index, quiet = false, keepTweaks = false)

    /**
     * @param quiet a reconnect by the watchdog: no info bar, no toasts.
     * @param keepTweaks keep the safer player settings chosen by the watchdog.
     */
    private fun startPlayback(index: Int, quiet: Boolean, keepTweaks: Boolean) {
        val items = PlaybackQueue.items
        if (items.isEmpty()) return
        saveResumePosition()
        cancelTimers()
        if (Recorder.isRecording) {
            Recorder.stop()
            Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()
        }

        val i = index.coerceIn(0, items.lastIndex)
        if (current != null && PlaybackQueue.index != i) previousIndex = PlaybackQueue.index
        PlaybackQueue.index = i
        val item = items[i]
        current = item
        linkTexts.forEach { it.text = "🔗 " + item.url }
        if (otherAppOpen) closeOtherApp()
        if (similarOpen) closeSimilar()
        startSession(item, quiet)
        // Status dots: this server's connection is now the player's (a check running there stops).
        StreamCheck.playing(if (item.isLocal) null else item.saved.accountId)
        hideNextCard()
        nextCancelled = false
        extraSubtitle = null
        retryCount = 0
        everReady = false
        if (!quiet) { startAttempts = 0; startOtherTried = false; weakChannel = false; skipOtherApp = false; wdLiveFixes.clear(); containerRetries = 0 }
        storedRestart = false; storedRestartTries = 0
        handler.removeCallbacks(resumeTimeoutTask)
        lastErrorName = null
        lastGrowWantMs = 0L
        refilling = false
        mediaBps = 0.0; bpsSampleBytes = -1L; arrivals.clear(); arrivalBps = 0.0
        rebuffers.clear(); notArrivingSince = 0L
        if (!keepTweaks) tweaks = Tweaks()
        wdReset(android.os.SystemClock.elapsedRealtime())
        videoDecoderSwitched = false
        audioFixTried = 0
        externalSuggested = false
        hideStatus()

        if (app.storage.requireVpn && !item.isLocal && !Vpn.isActive(this)) {
            player?.stop()
            relay?.close()
            relay = null
            titleView.text = item.title
            showStatus("No VPN is connected.\nConnect your VPN app, then press OK to try again.")
            retryCount = 1 // lets OK retry
            return
        }

        val mode = modeFor(item)

        // A fresh disk buffer per channel. The old one is closed a little later, once the player has
        // let go of it (closing it first made the player read an empty end and report a format error).
        val oldRelay = relay
        relay = null
        if (oldRelay != null) handler.postDelayed({ oldRelay.close() }, 3000)
        if (mode == SourceMode.LIVE_RELAY) {
            Log.i("NovaTest", "RELAY new ${item.title}")
            val liveDir = Folders.bufferDir(this, Folders.BUFFER_LIVE, app.storage.usePublicFolder)
            // Time-based buffer (minutes); the size is only a safety limit, kept below the free space.
            val free = runCatching { android.os.StatFs(liveDir.apply { mkdirs() }.absolutePath).availableBytes }.getOrDefault(4L shl 30)
            val safety = minOf(12L shl 30, free - (500L shl 20)).coerceAtLeast(300L shl 20)
            // Little free space for the chosen length (about 30 MB a minute for HD): say how much will be kept.
            val keepMin = (safety / (30L shl 20)).toInt()
            if (keepMin < app.storage.liveDiskMinutes && !spaceWarned) {
                spaceWarned = true
                Toast.makeText(this, "Little free space: the live buffer keeps about $keepMin min (of ${app.storage.liveDiskMinutes} min)", Toast.LENGTH_LONG).show()
            }
            relay = LiveRelay(
                url = item.url,
                client = app.http,
                userAgent = app.storage.userAgent,
                dir = liveDir,
                maxBytes = safety,
                tap = { b, off, len -> Recorder.write(b, off, len) },
            ).also {
                it.maxSeconds = app.storage.liveDiskMinutes * 60
                it.balanced = false
            }
        }

        // A fresh read-ahead per film / episode (the old one's segments are deleted).
        readAhead?.close()
        readAhead = null
        hlsAhead?.close()
        hlsAhead = null
        if (mode == SourceMode.VOD_HLS_DISK) {
            val dir = Folders.bufferDir(this, "Buffer/readahead-hls", app.storage.usePublicFolder)
            val wanted = app.storage.readAheadMb * 1024L * 1024L
            val free = runCatching { android.os.StatFs(dir.absolutePath).availableBytes }.getOrDefault(wanted * 2)
            val usable = minOf(wanted, free - 200L * 1024 * 1024).coerceAtLeast(16L * 1024 * 1024)
            hlsAhead = HlsReadAhead(app.vodHlsCache, app.http, app.storage.userAgent, usable).also { it.start() }
        }
        if (mode == SourceMode.VOD_DISK) {
            val dir = java.io.File(Folders.bufferDir(this, Folders.BUFFER_MOVIES, app.storage.usePublicFolder), "readahead")
            dir.mkdirs()
            val wanted = app.storage.readAheadMb * 1024L * 1024L
            val free = runCatching { android.os.StatFs(dir.absolutePath).availableBytes }.getOrDefault(wanted * 2)
            val usable = minOf(wanted, free - 200L * 1024 * 1024).coerceAtLeast(16L * 1024 * 1024)
            if (usable < wanted && !spaceWarned) {
                spaceWarned = true
                Toast.makeText(this, "Little free space: read-ahead uses ${usable / (1024 * 1024)} MB instead of ${app.storage.readAheadMb} MB", Toast.LENGTH_LONG).show()
            }
            readAhead = ReadAheadBuffer(item.url, app.http, app.storage.userAgent, dir, usable).also { it.start() }
        }

        val p = ensurePlayer(mode)
        val growingHls = item.url.startsWith("nova-dl://") && item.url.endsWith(".m3u8")
        // Streaming download while it runs: always from the beginning (jump anywhere inside the saved part).
        val resumeAt = if (growingHls) 0L else if (mode == SourceMode.VOD || mode == SourceMode.VOD_DISK || mode == SourceMode.VOD_HLS_DISK || mode == SourceMode.GROWING || mode == SourceMode.LOCAL) {
            app.storage.getPosition(item.key)
        } else 0L
        if (!quiet) resumeFallbackDone = false
        if (resumeAt > 0) {
            if (!resumeFallbackDone) handler.postDelayed(resumeTimeoutTask, 25_000)
            p.setMediaItem(buildMediaItem(item), resumeAt)
            if (!quiet) Toast.makeText(this, "Resuming from ${formatDuration(resumeAt)}", Toast.LENGTH_SHORT).show()
        } else if (growingHls) {
            // The list grows like a live one: start explicitly at its beginning, not at its end.
            p.setMediaItem(buildMediaItem(item), 0L)
        } else {
            p.setMediaItem(buildMediaItem(item))
        }
        p.prepare()
        p.playWhenReady = true
        applyBoost()
        applyNight()

        if (item.isLive) {
            // Never give up before the safety cushion has had time to fill.
            handler.postDelayed(startTimeoutTask, startTimeoutMs())
        }

        val accountLabel = if (app.storage.accounts.size > 1) {
            app.storage.account(item.saved.accountId)?.name?.let { "   ·   $it" }.orEmpty()
        } else ""
        titleView.text = (if (item.isLive && items.size > 1) "${i + 1}. ${item.title}" else item.title) + accountLabel
        val multi = item.isLive && items.size > 1
        prevButton.visibility = if (multi) View.VISIBLE else View.GONE
        nextButton.visibility = if (multi) View.VISIBLE else View.GONE
        val timeshiftVisible = if (mode == SourceMode.LIVE_RELAY) View.VISIBLE else View.GONE
        rewindButton.visibility = timeshiftVisible
        liveButton.visibility = timeshiftVisible
        timeView.visibility = timeshiftVisible
        recButton.visibility = if (item.isLive) View.VISIBLE else View.GONE
        similarButton.visibility = if (item.isLive && !item.catchup && !item.temporary && item.saved.accountId.isNotBlank()) View.VISIBLE else View.GONE
        downloadButton.visibility = if (!item.isLive && !item.isLocal && item.downloadExt != null) View.VISIBLE else View.GONE
        favButton.visibility = if (item.isLocal || item.temporary) View.GONE else View.VISIBLE
        updateRecButton()
        updateFavButton()
        loadEpg(item)
        if (item.isLive) {
            vod.enabled = false
            playerView.useController = true
            if (!quiet) playerView.showController()
        } else {
            // Films, series, catch-up and downloads use the film controls.
            playerView.useController = false
            infoBar.visibility = View.GONE
            vod.enabled = true
            vod.resetForNewItem(titleView.text.toString(), resizeNames[resizeIndex])
            vod.showAndFocus()
        }
        if (layoutDone) applyOrientationLayout()
    }

    /** Adds a subtitle file (.srt, .vtt, .ass) to the current video and keeps the position. */
    private fun addSubtitle(uri: android.net.Uri) {
        val p = player ?: return
        val item = current ?: return
        extraSubtitle = uri
        val pos = p.currentPosition
        p.setMediaItem(buildMediaItem(item), pos)
        p.prepare()
        p.playWhenReady = true
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            .build()
        Toast.makeText(this, "Subtitles added", Toast.LENGTH_SHORT).show()
    }

    /** Restarts reading from the disk buffer at its new position (after rewind / go live). */
    private fun restartFromRelay() {
        val p = player ?: return
        val item = current ?: return
        p.setMediaItem(buildMediaItem(item))
        p.prepare()
        p.playWhenReady = true
    }

    /** The badge follows the real quality (live streams change it with the internet speed). */
    private fun updateBadge() {
        val b = VideoBadge.of(player?.videoFormat)
        if (::liveBadge.isInitialized) liveBadge.show(b)
        if (::vod.isInitialized) vod.setBadge(b)
    }

    /** The boost for what is playing: "this video only" level, else this channel / film's saved level, else the general one. */
    private fun boostLevel(): Int {
        val key = current?.key
        if (key != boostKeyOf) { boostKeyOf = key; boostSession = null }
        boostSession?.let { return it }
        val saved = key?.let { app.storage.boostFor(it) } ?: 0
        return (if (saved > 0) saved else app.storage.boostAll).coerceIn(100, 200)
    }

    private fun applyBoost() {
        val level = boostLevel()
        GainProcessor.gain = level / 100f
        updateSoundBadge()
    }

    private fun updateSoundBadge() {
        val level = boostLevel()
        val parts = mutableListOf<String>()
        if (GainProcessor.night) parts += "🌙 Night mode · " + app.storage.nightStrength.replaceFirstChar { it.uppercase() }
        if (level > 100) parts += "🔊 $level %"
        boostBadge?.let { b ->
            b.text = parts.joinToString("   ")
            b.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
        // Shown 5 s, then hidden (comes back for 5 s whenever the player buttons are opened).
        handler.removeCallbacks(hideBadgeTask)
        if (parts.isNotEmpty()) handler.postDelayed(hideBadgeTask, 5_000)
    }

    /** Player buttons on screen: show the 🌙 / 🔊 badge again for 5 s. */
    private fun badgeWithControls() {
        val b = boostBadge ?: return
        if (b.text.isNullOrEmpty() || pictureHidden) return
        val shown = playerView.isControllerFullyVisible || (::vod.isInitialized && vod.controlsVisible)
        if (shown) {
            b.visibility = View.VISIBLE
            handler.removeCallbacks(hideBadgeTask)
            handler.postDelayed(hideBadgeTask, 5_000)
        }
    }

    // ------------------------------------------------------------------ 🌙 Night mode (sound)

    /** Settings › Night mode › Switch on automatically / Applies to. */
    private fun nightAutoOn(item: PlayItem): Boolean {
        val st = app.storage
        val scopeOk = when (st.nightScope) { "vod" -> !item.isLive; "live" -> item.isLive; else -> true }
        if (!scopeOk) return false
        return when (st.nightAuto) {
            "always" -> true
            "hours" -> {
                val c = java.util.Calendar.getInstance()
                val now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
                val from = st.nightFrom; val to = st.nightTo
                if (from <= to) now in from until to else now >= from || now < to
            }
            else -> false
        }
    }

    private fun nightOn(): Boolean {
        val item = current ?: return false
        if (item.key != nightKeyOf) { nightKeyOf = item.key; nightSession = null }
        return nightSession ?: nightAutoOn(item)
    }

    private fun applyNight() {
        val st = app.storage
        val on = nightOn()
        GainProcessor.nightThresholdDb = when (st.nightCeiling) { "high" -> -12f; "low" -> -24f; else -> -18f }
        GainProcessor.nightRatio = when (st.nightStrength) { "light" -> 2f; "strong" -> 8f; else -> 4f }
        GainProcessor.nightFast = st.nightSpeed != "smooth"
        GainProcessor.nightDialogue = st.nightDialogue
        GainProcessor.night = on
        val label = "🌙 Night mode: " + if (on) "On" else "Off"
        nightPill?.text = label
        if (::vod.isInitialized) vod.extraViews["🌙 Night mode: Off"]?.text = label
        updateSoundBadge()
    }

    private fun toggleNight() {
        nightSession = !nightOn()
        applyNight()
        Toast.makeText(
            this,
            if (GainProcessor.night) "🌙 Night mode on · loud scenes softened, voices kept clear" else "🌙 Night mode off",
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ------------------------------------------------------------------ ⬛ Hidden picture

    private fun buildHideCover(): View {
        hideCover = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true // touches never reach the controls under it
        }
        hideTitle = TextView(this).apply { setTextColor(0xFF444444.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        hideCover.addView(hideTitle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(32); topMargin = dp(24)
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF1A1A1A.toInt()); setStroke(dp(1), 0xFF333333.toInt()) }
            setPadding(dp(18), dp(12), dp(20), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "!"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFC0392B.toInt()) }
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PlayerActivity).apply {
                text = "Video signal error"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@PlayerActivity).apply {
                text = "The picture could not be displayed · code E-204"; setTextColor(0xFF999999.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        })
        hideCover.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val times = FrameLayout(this)
        hideLeft = TextView(this).apply { setTextColor(0xFF555555.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        hideRight = TextView(this).apply { setTextColor(0xFF555555.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        times.addView(hideLeft, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START))
        times.addView(hideRight, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END))
        bottom.addView(times)
        hideTrack = FrameLayout(this).apply { setBackgroundColor(0xFF1C1C1C.toInt()) }
        hideProgress = View(this).apply { setBackgroundColor(0xFF3A4A63.toInt()) }
        hideTrack.addView(hideProgress, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        bottom.addView(hideTrack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(4) })
        hideCover.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(32); rightMargin = dp(32); bottomMargin = dp(22)
        })
        // Phone: a double tap on the cover opens its options (a single tap never shows the picture).
        val taps = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean { showHideOptions(); return true }
        })
        hideCover.setOnTouchListener { _, e -> taps.onTouchEvent(e); true }
        return hideCover
    }

    private fun updateHideCover() {
        val item = current ?: return
        val p = player
        hideTitle.text = item.title.substringBefore("   ·   ")
        if (item.isLive) {
            hideLeft.text = "Live"
            hideRight.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            hideTrack.visibility = View.GONE
        } else {
            val pos = p?.currentPosition ?: 0L
            val dur = p?.duration?.takeIf { it > 0 } ?: 0L
            hideLeft.text = formatDuration(pos)
            hideRight.text = if (dur > 0) formatDuration(dur) else ""
            hideTrack.visibility = View.VISIBLE
            val w = hideTrack.width
            if (w > 0 && dur > 0) hideProgress.layoutParams = hideProgress.layoutParams.apply { width = (w * pos / dur).toInt() }
        }
    }

    private fun hidePicture() {
        if (pictureHidden) return
        pictureHidden = true
        hiddenAt = android.os.SystemClock.uptimeMillis()
        playerView.hideController()
        if (::vod.isInitialized) vod.hide()
        // Sound off too. Then, by the setting (Settings › Players › Hide picture, or OK OK):
        //  skip = black 2 s, then jump ahead by the rest of the hide time; wait = black until it is over; stay = until OK.
        player?.volume = 0f
        hideMuted = true
        hideAutoMs = app.storage.hideSeconds.coerceIn(5, 120) * 1000L
        when (app.storage.hideMode) {
            "stay" -> { hideAutoMs = 0L; scheduleAutoUnhide() }
            "wait" -> scheduleAutoUnhide()
            else -> Runnable { hideSkipTask = null; skipHiddenPart() }.also { hideSkipTask = it; handler.postDelayed(it, 2_000) }
        }
        boostBadge?.visibility = View.GONE
        updateHideCover()
        hideCover.visibility = View.VISIBLE
        hideCover.bringToFront()
        if (::keySink.isInitialized) keySink.requestFocus()
        Log.i("NovaTest", "PICTURE hidden")
    }

    /** Skip mode, 2 s after hiding: jump ahead by the rest of the hide time (live TV: only inside what is stored ahead). */
    private fun skipHiddenPart() {
        if (!pictureHidden) return
        val item = current ?: return
        val p = player ?: return
        val want = ((hideAutoMs - 2_000L) / 1000L).toInt().coerceAtLeast(1)
        var jumped: Int
        if (!item.isLive) {
            vod.seekBy(want * 1000L)
            jumped = want
        } else {
            // Keep at least 5 s ahead so the picture doesn't stall right after the jump.
            val ahead = (behindLiveSeconds() ?: 0) - 5
            jumped = minOf(want, ahead).coerceAtLeast(0)
            if (jumped > 0) {
                if (relay != null) timeshift(jumped)
                else p.seekTo(minOf(p.currentPosition + jumped * 1000L, p.bufferedPosition - 2_000L))
            }
        }
        Log.i("NovaTest", "PICTURE hidden: skipped $jumped of $want s")
        // Not enough stored ahead (live): stay black until the rest of the time has really passed.
        val rest = (want - jumped) * 1000L
        showWhenPlaying(rest)
    }

    /** Shows the picture once the player plays again after the jump (never the old frame), after [extraMs]. */
    private fun showWhenPlaying(extraMs: Long) {
        hideWaitReady?.let { handler.removeCallbacks(it) }
        val start = android.os.SystemClock.uptimeMillis()
        val check = object : Runnable {
            override fun run() {
                if (!pictureHidden) return
                val p = player
                val elapsed = android.os.SystemClock.uptimeMillis() - start
                val ready = p != null && p.playbackState == Player.STATE_READY && p.isPlaying
                if ((ready && elapsed >= extraMs + 500) || elapsed > extraMs + 8_000) { hideWaitReady = null; showPicture() }
                else handler.postDelayed(this, 200)
            }
        }
        hideWaitReady = check
        handler.postDelayed(check, 300)
    }

    private fun showPicture() {
        hideSkipTask?.let { handler.removeCallbacks(it) }; hideSkipTask = null
        hideWaitReady?.let { handler.removeCallbacks(it) }; hideWaitReady = null
        pendingUnhide?.let { handler.removeCallbacks(it) }; pendingUnhide = null
        autoUnhide?.let { handler.removeCallbacks(it) }; autoUnhide = null
        if (hideMuted) { player?.volume = 1f; hideMuted = false }
        pictureHidden = false
        hideCover.visibility = View.GONE
        Log.i("NovaTest", "PICTURE shown")
    }

    private fun scheduleAutoUnhide() {
        autoUnhide?.let { handler.removeCallbacks(it) }
        autoUnhide = if (hideAutoMs <= 0) null else Runnable { autoUnhide = null; if (pictureHidden) showPicture() }.also { handler.postDelayed(it, hideAutoMs) }
    }

    /** OK twice while hidden (double tap on a phone): show · skip · sound · show again by itself (Off / 15 s / 30 s / 1 min). */
    private fun showHideOptions() {
        val item = current ?: return
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        choices += "👁 Show picture" to { showPicture() }
        if (!item.isLive) {
            choices += "⏩ Skip 30 s" to { vod.seekBy(30_000) }
            choices += "⏩ Skip 1 min" to { vod.seekBy(60_000) }
        } else if (relay != null) {
            choices += "⏩ Skip 30 s" to { timeshift(30) }
        }
        choices += (if (hideMuted) "🔊 Sound back on (muted while hidden)" else "🔇 Mute too") to {
            hideMuted = !hideMuted
            player?.volume = if (hideMuted) 0f else 1f
        }
        // When I hide the picture (saved for the next times too).
        val st = app.storage
        fun mode(label: String, m: String) {
            choices += ((if (st.hideMode == m) "✓ " else "") + label) to { st.hideMode = m }
        }
        mode("Skip ahead (black 2 s, then jump)", "skip")
        mode("Wait (black until the time is over)", "wait")
        mode("Stay hidden until I press OK", "stay")
        fun time(label: String, sec: Int) {
            choices += ((if (st.hideSeconds == sec) "✓ " else "") + label) to {
                st.hideSeconds = sec
                // Waiting now: the new time applies to this hide too.
                if (st.hideMode == "wait" && hideSkipTask == null && hideWaitReady == null) { hideAutoMs = sec * 1000L; scheduleAutoUnhide() }
            }
        }
        time("Hide time: 15 s", 15)
        time("Hide time: 30 s", 30)
        time("Hide time: 1 min", 60)
        android.app.AlertDialog.Builder(this)
            .setTitle("Hidden picture options")
            .setItems(choices.map { it.first }.toTypedArray()) { _, i -> choices[i].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showControlsNow(item: PlayItem) {
        if (item.isLive) playerView.showController() else vod.showAndFocus()
    }

    /** 🔊 Boost panel: 100–200 %, then where it applies (this video only / always for this channel or film / everything). */
    private fun showBoost() {
        val item = current ?: return
        val levels = listOf(100, 125, 150, 175, 200)
        var chosen = boostLevel()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val passthrough = app.storage.audioDecoder == "device"
        box.addView(TextView(this).apply {
            text = "For films and channels whose sound is too low. Above 100 % Nova raises the sound itself; a limiter stops loud scenes from crackling." +
                (if (passthrough) "\nNote: with “Box first (passthrough)”, Dolby / DTS sound going straight to a soundbar can't be boosted." else "")
            setTextColor(0xFFB8C4DD.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
        val chips = mutableListOf<TextView>()
        fun mark() = chips.forEachIndexed { i, c -> c.text = (if (levels[i] == chosen) "● " else "") + "${levels[i]} %" }
        levels.forEach { l ->
            val c = pill("$l %") {
                chosen = l
                boostSession = l
                applyBoost()
                mark()
            }
            chips += c
            row.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        mark()
        box.addView(row)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("🔊 Volume boost")
            .setView(box)
            .setNegativeButton("This video only") { _, _ -> boostSession = chosen; applyBoost() }
            .setNeutralButton("Everything") { _, _ ->
                app.storage.boostAll = chosen
                app.storage.setBoostFor(item.key, 0)
                boostSession = null; applyBoost()
            }
            .setPositiveButton(if (item.isLive) "Always for this channel" else "Always for this film") { _, _ ->
                app.storage.setBoostFor(item.key, chosen)
                boostSession = null; applyBoost()
            }
            .create()
        dialog.setOnShowListener { chips.getOrNull(levels.indexOf(chosen).coerceAtLeast(0))?.requestFocus() }
        dialog.show()
    }

    private fun installBrightness(p: ExoPlayer): Boolean {
        if (brightnessInstalled) return true
        val h = p.videoFormat?.height ?: 0
        if (h > 1200) return false // 4K: the extra picture step would be too heavy for the box
        return runCatching {
            p.setVideoEffects(listOf<androidx.media3.common.Effect>(PictureBrightness))
            brightnessInstalled = true
            Log.i("NovaTest", "BRIGHTNESS effect on")
        }.onFailure { Log.w("NovaTest", "BRIGHTNESS not available: ${it.message}") }.isSuccess
    }

    /** ☀ bar: 0 = darker (50 %), 0.5 = normal, 1 = brighter (150 %). */
    private fun setPictureBrightness(v: Float) {
        val g = (0.5f + v.coerceIn(0f, 1f)).let { if (kotlin.math.abs(it - 1f) < 0.03f) 1f else it }
        PictureBrightness.gain = g
        val p = player ?: return
        val item = current ?: return
        if (g == 1f || brightnessInstalled) return
        if ((p.videoFormat?.height ?: 0) > 1200) {
            PictureBrightness.gain = 1f
            Toast.makeText(this, "Brightness isn't available for 4K videos (too heavy for the box)", Toast.LENGTH_SHORT).show()
            return
        }
        // The effect is fitted when the video starts: restart quietly at the same place once.
        if (installBrightness(p)) {
            if (item.isLive) startPlayback(PlaybackQueue.index, quiet = true, keepTweaks = true)
            else restartAt(item, p.currentPosition.coerceAtLeast(0L))
        }
    }

    private fun showVideoInfo() {
        val p = player
        val text = VideoBadge.details(p?.videoFormat, p?.audioFormat, videoDecoderName)
        android.app.AlertDialog.Builder(this)
            .setTitle("ℹ Video now · " + (current?.title ?: ""))
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
            .findViewById<TextView>(android.R.id.message)?.typeface = Typeface.MONOSPACE
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { updateBadge() }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            updateBadge()
            // A picture is there but none of its formats can be decoded here: orange dot.
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (video.isNotEmpty() && video.none { g -> (0 until g.length).any { g.isTrackSupported(it) } }) {
                current?.let { c -> checkKey(c)?.let { StreamCheck.deviceFailed(it, "video format") } }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (everReady && player?.playWhenReady == true) Log.i("NovaTest", "PLAYING again ${current?.title}")
                    if (!everReady) {
                        Log.i("NovaTest", "READY ${current?.title}")
                        current?.let { c -> checkKey(c)?.let { StreamCheck.played(it, videoDecoded = player?.videoFormat != null) } }
                        session?.let { ss ->
                            if (ss.startMs < 0) {
                                ss.startMs = System.currentTimeMillis() - ss.startedAt
                                current?.takeIf { it.isLive && !storedRestart }?.let { learnStart(it.saved.accountId, ss.startMs) }
                            }
                            ss.mode = playerMode?.name?.lowercase()?.replace('_', ' ').orEmpty()
                            player?.videoFormat?.let { f ->
                                ss.video = listOfNotNull(
                                    if (f.width > 0) "${f.width}×${f.height}" else null,
                                    f.sampleMimeType?.substringAfter('/')?.uppercase(),
                                    f.codecs,
                                ).joinToString(" ")
                            }
                            ss.decoder = videoDecoderName.orEmpty()
                        }
                        // Remember the channel for "Recent" and the recently watched bar.
                        current?.takeIf { it.isLive && !it.catchup && !it.temporary }?.let { app.storage.addRecent(it.saved) }
                    }
                    retryCount = 0
                    if (bufferingAt > 0) { session?.let { it.rebufferMs += android.os.SystemClock.elapsedRealtime() - bufferingAt }; bufferingAt = 0L }
                    everReady = true
                    autoSkips = 0
                    handler.removeCallbacks(startTimeoutTask)
                    handler.removeCallbacks(resumeTimeoutTask)
                    if (storedRestart) {
                        storedRestart = false; storedRestartTries = 0
                        // Check where the repair landed (it must keep your place, not jump to live).
                        val after = behindLiveSeconds()
                        if (repairBehindBefore != null && after != null) Log.i("NovaTest", "REPAIR landed: behind live ${repairBehindBefore} s -> $after s")
                        repairNote = if (repairBehindBefore != null && after != null) " · behind live ${repairBehindBefore} s → $after s" else ""
                        repairNote.takeIf { it.isNotEmpty() }?.let { n -> current?.let { logHealth(it, "repair result", n.removePrefix(" · ")) } }
                        repairBehindBefore = null
                    }
                    hideStatus()
                    // The buttons shown at the channel start hide 5 s after the picture appears (they were shown
                    // before playback began, when the player keeps them up until a key is pressed).
                    if (current?.isLive == true && player?.playWhenReady == true && playerView.isControllerFullyVisible) playerView.showController()
                }
                Player.STATE_BUFFERING -> {
                    if (everReady) {
                        Log.i("NovaTest", "BUFFERING ${current?.title}")
                        val t = android.os.SystemClock.elapsedRealtime()
                        session?.let { it.rebuffers++; bufferingAt = t }
                        rebuffers.addLast(t)
                        while (rebuffers.isNotEmpty() && t - rebuffers.first() > 180_000) rebuffers.removeFirst()
                    }
                }
                Player.STATE_ENDED -> {
                    // Live MPEG-TS streams "end" when the server drops the connection.
                    if (current?.isLive == true) scheduleReconnect(null)
                    else {
                        current?.let { app.storage.setPosition(it.key, 0) }
                        if (nextCardShown) playNextEpisodeNow()
                        else if (nextEpisodeIndex() != null && !nextCancelled) playNextEpisodeNow()
                    }
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleReconnect(error)
        }
    }

    /** Logs which decoders are used (shown in test logs; helps when a format doesn't play). */
    private val decoderLogger = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            Log.i("NovaTest", "DECODER video ${current?.title}: $name")
        }

        override fun onAudioDecoderInitialized(t: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            Log.i("NovaTest", "DECODER audio ${current?.title}: $name")
        }
    }

    /**
     * A film / episode failed to decode: try safer settings before giving up.
     * Sound: FFmpeg decoders first (no offload / tunneling), then another sound track, then no sound.
     * Video: the other decoder (hardware ↔ software). Subtitles: switched off.
     */
    private fun tryRecover(item: PlayItem, error: PlaybackException?): Boolean {
        if (item.isLive) return false
        val e = error as? ExoPlaybackException ?: return false
        if (e.type != ExoPlaybackException.TYPE_RENDERER) return false
        val p = player ?: return false
        val format = e.rendererFormat
        val type = MimeTypes.getTrackType(format?.sampleMimeType)
        val position = p.currentPosition.coerceAtLeast(0L)
        val what = describeFormat(format)
        when (type) {
            C.TRACK_TYPE_TEXT -> {
                Log.i("NovaTest", "RECOVER ${item.title}: subtitles off ($what)")
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                p.prepare()
                p.playWhenReady = true
                Toast.makeText(this, "These subtitles ($what) can't be shown — subtitles off", Toast.LENGTH_LONG).show()
                return true
            }
            C.TRACK_TYPE_AUDIO -> {
                if (audioFixTried == 0) {
                    audioFixTried = 1
                    Log.i("NovaTest", "RECOVER ${item.title}: FFmpeg sound decoder ($what)")
                    tweaks = tweaks.copy(step = tweaks.step + 1, ffmpegAudioFirst = true, noOffload = true, noTunnel = true)
                    restartAt(item, position)
                    return true
                }
                if (audioFixTried == 1) {
                    audioFixTried = 2
                    val badMime = format?.sampleMimeType
                    val alt = p.currentTracks.groups.firstNotNullOfOrNull { g ->
                        if (g.type != C.TRACK_TYPE_AUDIO) return@firstNotNullOfOrNull null
                        (0 until g.length).firstOrNull { i ->
                            g.isTrackSupported(i) && g.getTrackFormat(i).sampleMimeType != badMime
                        }?.let { i -> g to i }
                    }
                    if (alt != null) {
                        val (g, i) = alt
                        Log.i("NovaTest", "RECOVER ${item.title}: other sound track ${describeFormat(g.getTrackFormat(i))}")
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                        Toast.makeText(this, "Sound $what isn't supported — switched to ${describeFormat(g.getTrackFormat(i))}", Toast.LENGTH_LONG).show()
                    } else {
                        Log.i("NovaTest", "RECOVER ${item.title}: no sound ($what)")
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                        Toast.makeText(
                            this,
                            "The sound of this video ($what) can't be decoded here — playing without sound. Use ↗ External player for sound.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    p.prepare()
                    p.playWhenReady = true
                    return true
                }
                return false
            }
            C.TRACK_TYPE_VIDEO -> {
                if (videoDecoderSwitched) return false
                videoDecoderSwitched = true
                val wasSoftware = playerTweaks.softwareVideo ?: (app.storage.videoDecoder == "software")
                Log.i("NovaTest", "RECOVER ${item.title}: ${if (wasSoftware) "hardware" else "software"} video decoder ($what)")
                tweaks = tweaks.copy(step = tweaks.step + 1, softwareVideo = !wasSoftware, noTunnel = true)
                restartAt(item, position)
                return true
            }
        }
        return false
    }

    /** Rebuilds the player with the current [tweaks] and continues at [position]. */
    private fun restartAt(item: PlayItem, position: Long) {
        val p = ensurePlayer(modeFor(item))
        p.setMediaItem(buildMediaItem(item), position)
        p.prepare()
        p.playWhenReady = true
    }

    /** "video HEVC (H.265) 10-bit 3840×2160 HDR", "sound DTS 6 ch", … */
    private fun describeFormat(f: Format?): String {
        if (f == null) return "unknown format"
        val mime = f.sampleMimeType ?: f.containerMimeType ?: "unknown"
        val name = when (mime) {
            MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_MP4V -> "MPEG-4 (Xvid/DivX)"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            MimeTypes.VIDEO_VC1 -> "VC-1"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus (E-AC3)"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (E-AC3)"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC3)"
            MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.TEXT_SSA -> "ASS/SSA"
            MimeTypes.APPLICATION_PGS -> "PGS (Blu-ray pictures)"
            MimeTypes.APPLICATION_VOBSUB -> "VobSub (DVD pictures)"
            else -> mime.substringAfter('/')
        }
        val parts = mutableListOf(name)
        if (MimeTypes.isVideo(mime)) {
            val c = f.colorInfo
            if (c != null && (c.lumaBitdepth == 10 || c.colorTransfer == C.COLOR_TRANSFER_ST2084 || c.colorTransfer == C.COLOR_TRANSFER_HLG)) parts += "10-bit"
            if (f.width > 0) parts += "${f.width}×${f.height}"
            if (c != null && (c.colorTransfer == C.COLOR_TRANSFER_ST2084 || c.colorTransfer == C.COLOR_TRANSFER_HLG)) parts += "HDR"
        } else if (MimeTypes.isAudio(mime) && f.channelCount > 0) {
            parts += "${f.channelCount} ch"
        }
        f.codecs?.takeIf { it.isNotBlank() }?.let { parts += "($it)" }
        return parts.joinToString(" ")
    }

    private fun scheduleReconnect(error: PlaybackException?) {
        val item = current ?: return
        val code = error?.errorCode
        lastErrorName = error?.let { e ->
            e.errorCodeName.removePrefix("ERROR_CODE_") + (generateSequence(e.cause) { it.cause }.lastOrNull()?.let { c -> " (${c.javaClass.simpleName}: ${c.message?.take(80)})" } ?: "")
        }
        // "Stream not recognised": usually the first data arrived broken or empty (server hiccup), not this device.
        // Retry quietly; only after that is it reported as a file the player can't read.
        if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) {
            if (containerRetries < 3) {
                containerRetries++
                Log.i("NovaTest", "UNRECOGNISED ${item.title}: retry $containerRetries ($lastErrorName)")
                session?.action("stream not recognised → retry $containerRetries")
                val pos = if (item.isLive) 0L else (player?.currentPosition ?: 0L).coerceAtLeast(0L)
                handler.postDelayed({
                    if (current !== item) return@postDelayed
                    if (item.isLive) startPlayback(PlaybackQueue.index, quiet = true, keepTweaks = true) else restartAt(item, pos)
                }, 1200L * containerRetries)
                return
            }
            session?.fail(if (item.isLive) "FAIL" else "FILE", "stream / file not readable by Nova [$lastErrorName]")
            if (item.isLive) { channelFailed("stream not recognised"); return }
            if (app.storage.failApp != "none") offerOtherApp(item, "Nova can't read this file's format", format = true)
            else showStatus("Nova can't read this file's format (not a problem of this device).\nPress OK (or tap here) to open it in an external player.").also { externalSuggested = true }
            return
        }
        if (code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            (code == PlaybackException.ERROR_CODE_DECODING_FAILED && !item.isLive) ||
            // Sound output problems (often Dolby/DTS passthrough or offload) on films: try the fixes.
            (!item.isLive && code in listOf(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            ))
        ) {
            val detail = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.let {
                val kind = when (MimeTypes.getTrackType(it.rendererFormat?.sampleMimeType)) {
                    C.TRACK_TYPE_VIDEO -> "video"
                    C.TRACK_TYPE_AUDIO -> "sound"
                    C.TRACK_TYPE_TEXT -> "subtitles"
                    else -> "track"
                }
                "$kind ${describeFormat(it.rendererFormat)}"
            }
            Log.i(
                "NovaTest",
                "FORMAT ${item.title}: ${error?.errorCodeName} ${detail.orEmpty()} | " +
                    generateSequence(error as Throwable?) { it.cause }.take(4).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" },
            )
            if (tryRecover(item, error)) return
            checkKey(item)?.let { StreamCheck.deviceFailed(it, detail ?: "format") }
            session?.fail("DEVICE", "can't decode ${detail ?: "the format"} [$lastErrorName]")
            val dolby = detail?.contains("Dolby Vision") == true
            val reason = if (dolby) "\nThis device can't play Dolby Vision (your TV box can). Look for the same film in HD / without “4K”, or use VLC / MX Player."
                else detail?.let { "\nThis device can't decode the $it." }.orEmpty()
            if (app.storage.failApp != "none") {
                // Codec problem: offer this channel / video to the chosen app (3 s countdown, can be stopped).
                offerOtherApp(item, if (dolby) "This device can't play Dolby Vision" else "This device can't decode its ${detail ?: "format"}", format = true)
            } else if (item.isLive) {
                channelFailed("format not supported by this device")
            } else {
                externalSuggested = true
                showStatus("The built-in player can't play this format.$reason\nPress OK (or tap here) to open it in an external player.")
            }
            return
        }
        val httpCode = (error?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: relay?.lastHttpCode?.takeIf { it >= 400 }
        retryCount++

        if (item.isLive) {
            // Decide whether this channel is dead.
            val refused = httpCode != null && httpCode in 400..599
            val hardRefused = httpCode == 401 || httpCode == 403 || httpCode == 404 || httpCode == 410
            if (!everReady) {
                val reason = if (refused) "server answered HTTP $httpCode" else "no video"
                // A channel that doesn't exist (404…) is skipped at once; anything else gets several attempts.
                if (!hardRefused && startRetry(item, reason)) return
                channelFailed(reason)
                return
            }
            if (retryCount > 6) {
                channelFailed(if (refused) "server answered HTTP $httpCode" else "no video")
                return
            }
        } else {
            val limit = if (httpCode == 401 || httpCode == 403 || httpCode == 404) 3 else 30
            if (retryCount > limit) {
                if (httpCode == 404) checkKey(item)?.let { StreamCheck.failed(it, "not found") }
                session?.fail("FAIL", (if (httpCode != null) "server answered HTTP $httpCode" else "video unavailable") + (lastErrorName?.let { " [$it]" } ?: ""))
                showStatus(
                    when (httpCode) {
                        401, 403 -> "Server refused the video (HTTP $httpCode). Check the account or its connection limit."
                        404 -> "This video is not available on the server (HTTP 404)."
                        else -> "Video unavailable. Press OK to try again."
                    }
                )
                return
            }
        }

        val delay = minOf(1000L * retryCount, 8000L)
        showStatus("Connection lost — reconnecting ($retryCount)…", transient = true)
        reconnectTask?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            val p = player ?: return@Runnable
            if (current !== item) return@Runnable
            if (playerMode == SourceMode.LIVE_DIRECT || playerMode == SourceMode.LIVE_HLS_DISK ||
                code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
            ) {
                p.seekToDefaultPosition()
            }
            // In disk-buffer mode the player simply continues reading where it stopped.
            p.prepare()
            p.playWhenReady = true
        }
        reconnectTask = task
        handler.postDelayed(task, delay)
    }

    /**
     * A live channel didn't start: try again by itself before giving up — up to 5 more attempts over ~30 s,
     * re-using the video already stored on disk, then safer player settings, then the same channel on
     * your other account. Returns false when everything was tried.
     */
    private fun startRetry(item: PlayItem, reason: String): Boolean {
        if (!item.isLive || everReady || current !== item) return false
        val s = app.storage
        val index = PlaybackQueue.index
        if (startAttempts >= startDelays.size) {
            if (startOtherTried || !s.watchdogOtherAccount || s.accounts.size < 2 || item.temporary) return false
            startOtherTried = true
            cancelTimers()
            showStatus("Looking for “${item.title}” on your other account…")
            lifecycleScope.launch {
                val other = runCatching { LiveCatalog.sameChannelElsewhere(app, item) }.getOrNull()
                if (current !== item) return@launch
                if (other != null) {
                    PlaybackQueue.items = PlaybackQueue.items.toMutableList().also { it[index] = other }
                    val acc = s.accounts.firstOrNull { it.id == other.saved.accountId }?.name ?: "another account"
                    logHealth(item, "didn't start ($reason)", "switched to “${other.title}” on $acc")
                    Toast.makeText(this@PlayerActivity, "“${item.title}” didn't start — playing it from $acc", Toast.LENGTH_LONG).show()
                    startPlayback(index, quiet = true, keepTweaks = false)
                } else {
                    channelFailed(reason)
                }
            }
            return true
        }
        val n = ++startAttempts
        cancelTimers()
        val stored = relay?.secondsStored() ?: 0
        showStatus("Starting “${item.title}”…  attempt ${n + 1} of ${startDelays.size + 1}")
        Log.i("NovaTest", "START RETRY ${item.title}: $reason -> attempt ${n + 1}")
        logHealth(item, "didn't start ($reason)", "start attempt ${n + 1}")
        val task = Runnable {
            if (current !== item || everReady) return@Runnable
            val p = player
            if (n <= 2 && stored > 0 && relay != null && p != null) {
                // Video is arriving: restart the player on what is already stored.
                p.prepare()
                p.playWhenReady = true
                handler.removeCallbacks(startTimeoutTask)
                handler.postDelayed(startTimeoutTask, startTimeoutMs())
            } else {
                if (n == 4) {
                    // Safer settings: the other video decoder, no tunneling / audio offload.
                    val software = tweaks.softwareVideo ?: (s.videoDecoder == "software")
                    tweaks = tweaks.copy(step = tweaks.step + 1, softwareVideo = !software, noTunnel = true, noOffload = true)
                    player?.release(); player = null; playerMode = null
                }
                startPlayback(index, quiet = true, keepTweaks = true)
            }
        }
        reconnectTask = task
        handler.postDelayed(task, startDelays[n - 1])
        return true
    }

    private fun startTimeoutMs(): Long {
        val startCushion = if (app.storage.liveStart == "fast") minOf(2, app.storage.cushionSeconds) else app.storage.cushionSeconds
        val base = maxOf(app.storage.skipTimeoutSec, startCushion + 6) * 1000L
        // Learned per server: a server whose channels usually need 11 s to start gets ~20 s before a new attempt.
        val learned = current?.saved?.accountId?.let { startLearnedMs(it) } ?: 0L
        return maxOf(base, minOf(learned * 18 / 10 + 2000, 40_000L))
    }

    private val startPrefs by lazy { getSharedPreferences("start_times", MODE_PRIVATE) }
    private fun startLearnedMs(account: String): Long = startPrefs.getLong(account, 0L)
    /** Remembers how long channels of this server take to start (average of the recent ones, slow ones count fully). */
    private fun learnStart(account: String, ms: Long) {
        if (account.isBlank() || ms <= 0 || ms > 120_000) return
        val old = startLearnedMs(account)
        val v = if (old == 0L) ms else (old * 3 + ms) / 4
        startPrefs.edit().putLong(account, v).apply()
    }

    /** A live channel doesn't work: move on to the next one, or explain. */
    private fun channelFailed(reason: String) {
        val item = current ?: return
        if (!reason.contains("format")) checkKey(item)?.let { k ->
            StreamCheck.failed(k, when {
                reason.contains("404") || reason.contains("410") -> "not found"
                reason.contains("HTTP 5") -> "server error"
                reason.contains("HTTP") -> reason.replace("server answered ", "")
                else -> "no signal"
            })
        }
        // "Open in another app" for any failure: offer it before skipping / retrying.
        if (!skipOtherApp && app.storage.failApp != "none" && app.storage.failAppWhen == "any" && !reason.contains("format")) {
            offerOtherApp(item, "It isn't working here ($reason)", format = false)
            return
        }
        skipOtherApp = false
        val items = PlaybackQueue.items
        val maxSkips = minOf(items.size - 1, 25)
        Log.i("NovaTest", "SKIP ${item.title}: $reason")
        session?.fail(if (reason.contains("format")) "DEVICE" else "FAIL", reason + (lastErrorName?.let { " [$it]" } ?: "") +
            if (app.storage.autoSkip && items.size > 1) " · skipped automatically" else "")
        if (app.storage.autoSkip && items.size > 1 && autoSkips < maxSkips) {
            autoSkips++
            Toast.makeText(this, "“${item.title}” isn't working ($reason) — next channel", Toast.LENGTH_SHORT).show()
            zap(lastDirection, auto = true)
        } else {
            cancelTimers()
            player?.stop()
            showStatus(
                if (autoSkips > 0) "Several channels in a row aren't working. Check your connection or account.\nPress OK to retry."
                else "“${item.title}” isn't working ($reason).\nPress OK to retry, or change channel."
            )
            retryCount = 1 // lets OK retry
        }
    }

    // ------------------------------------------------------------------ playback report (Settings › Last problem)

    private var session: PlaybackLog.Session? = null
    /** Last player error, for the report ("DECODING_FORMAT_UNSUPPORTED (MediaCodec…: …)"). */
    private var lastErrorName: String? = null
    /** "Stream not recognised" retries for the item playing. */
    private var containerRetries = 0
    private var bufferingAt = 0L

    private fun startSession(item: PlayItem, quiet: Boolean) {
        // A quiet restart of the same channel / film (retry, watchdog) stays in the same report line.
        if (quiet && session?.key == item.key) return
        endSession()
        val server = app.storage.accounts.firstOrNull { it.id == item.saved.accountId }?.name
            ?: if (item.isLocal) "this device" else "link"
        val group = if (item.isLive) LiveCatalog.groupName(PlaybackQueue.groupKey) else null
        if (item.isLive && group == null && PlaybackQueue.groupKey?.contains('|') == true) lifecycleScope.launch {
            runCatching { LiveCatalog.groups(app) }
            val ss = session ?: return@launch
            if (ss.title == item.title) ss.group = LiveCatalog.groupName(PlaybackQueue.groupKey)
        }
        session = PlaybackLog.Session(item.title, item.kind, server, group, item.key).also {
            // Only the file type, never the address (it holds the login).
            it.container = item.url.substringBefore('?').substringAfterLast('.', "").takeIf { e -> e.length in 2..5 }?.uppercase().orEmpty()
        }
        bufferingAt = 0L
    }

    private fun endSession() {
        session?.let { PlaybackLog.end(it) }
        session = null
    }

    // ------------------------------------------------------------------ ≈ Similar: same channel, same server, other groups

    private var similarPanel: LinearLayout? = null
    private var similarCloseTask: Runnable? = null
    private val similarOpen: Boolean get() = similarPanel?.visibility == View.VISIBLE

    private fun showSimilar(auto: Boolean) {
        val item = current ?: return
        if (!item.isLive || item.saved.accountId.isBlank()) return
        playerView.hideController()
        val server = app.storage.accounts.firstOrNull { it.id == item.saved.accountId }?.name ?: "this server"
        val panel = similarPanel ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0xF00A0E14.toInt()); setStroke(dp(2), 0xFFFFA31A.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.46f).toInt().coerceIn(dp(300), dp(560)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP,
            ).apply { setMargins(dp(20), dp(30), dp(24), dp(20)) }
            (playerView.parent as ViewGroup).addView(this)
            similarPanel = this
        }
        panel.removeAllViews()
        fun text(t: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        panel.addView(text(if (auto) "⚠ The line is too slow for “${item.title}”" else "≈ Same channel on $server, other groups", 17f, Color.WHITE, true))
        if (auto && mediaBps > 0) panel.addView(text(
            "Arriving ${mbit(arrivalBps)} of ${mbit(mediaBps)} Mbit/s · cushion left ${behindLiveSeconds() ?: 0} s · same channel on $server:",
            13f, 0xFFFFD08A.toInt(),
        ).apply { setPadding(0, dp(2), 0, dp(8)) })
        val loading = text("Looking in all groups of $server…", 13f, 0xFFBBBBBB.toInt()).apply { setPadding(0, dp(6), 0, dp(6)) }
        panel.addView(loading)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(rows)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun button(t: String, action: () -> Unit) = pill(t) {}.apply {
            setOnClickListener { action() }
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, dp(10), 0)
        }
        similarCountdown = text("", 12f, 0xFFFFD08A.toInt()).apply { setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        val stay = button("Stay on this one") { closeSimilar() }
        buttons.addView(stay)
        panel.addView(buttons)
        panel.addView(similarCountdown)
        panel.visibility = View.VISIBLE
        stay.requestFocus()
        armSimilarClose(auto)
        lifecycleScope.launch {
            val res = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LiveCatalog.similarOnSameServer(app, item) }
            }
            // "Try my other server" only when the channel really is there.
            val other = if (app.storage.accounts.size > 1) runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LiveCatalog.sameChannelElsewhere(app, item) }
            }.getOrNull() else null
            if (!similarOpen || current !== item) return@launch
            if (other != null) {
                val otherServer = app.storage.accounts.firstOrNull { it.id == other.saved.accountId }?.name ?: "my other server"
                buttons.addView(button("Try $otherServer: ${other.title}") { closeSimilar(); playOther(item, other) })
            }
            val r = res.getOrNull()
            if (r == null) {
                loading.text = "Couldn't load the channel list from $server — try again in a moment."
                session?.action("similar: list didn't load (${res.exceptionOrNull()?.javaClass?.simpleName})")
                return@launch
            }
            val found = r.found
            Log.i("NovaTest", "SIMILAR ${item.title} [${r.key}]: ${found.size} of ${r.searched} on $server")
            session?.action("similar “${r.key}”: ${found.size} found in ${r.searched}" + (other?.let { " · also on another server" } ?: ""))
            if (found.isEmpty()) {
                loading.text = "Searched ${java.text.NumberFormat.getIntegerInstance().format(r.searched)} channels of $server for “${r.key}” — " +
                    "no other version." + (if (other == null && app.storage.accounts.size > 1) " Not on your other server either." else "")
                return@launch
            }
            panel.removeView(loading)
            var sepAdded = false
            var first: View? = null
            found.take(10).forEach { sim ->
                if (!sim.lighter && !sepAdded && found.any { it.lighter }) {
                    sepAdded = true
                    rows.addView(text("Same quality (usually the same source — rarely faster):", 12f, 0xFF999999.toInt()).apply { setPadding(0, dp(8), 0, dp(2)) })
                }
                val sb = android.text.SpannableStringBuilder()
                checkKey(sim.item)?.let { k ->
                    val st = StreamCheck.status(k).state
                    val c = when (st) { StreamCheck.State.OK -> 0xFF2EE66B.toInt(); StreamCheck.State.BAD -> 0xFFFF4D4D.toInt(); StreamCheck.State.DEVICE -> 0xFFFFA31A.toInt(); else -> 0xFF8A9099.toInt() }
                    sb.append("● ", android.text.style.ForegroundColorSpan(c), 0)
                }
                sb.append(sim.item.title)
                if (sim.qualityLabel.isNotBlank()) {
                    val s0 = sb.length
                    sb.append("  ").append(sim.qualityLabel)
                    sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), s0, sb.length, 0)
                }
                if (sim.group.isNotBlank()) {
                    val s0 = sb.length
                    sb.append("   ·  ").append(sim.group)
                    sb.setSpan(android.text.style.RelativeSizeSpan(0.8f), s0, sb.length, 0)
                    sb.setSpan(android.text.style.ForegroundColorSpan(0xFFAABBCC.toInt()), s0, sb.length, 0)
                }
                val row = pill("") {}.apply {
                    text = sb
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, dp(3)) }
                    setOnClickListener { closeSimilar(); playSimilar(item, sim) }
                }
                rows.addView(row)
                if (first == null) first = row
            }
            first?.requestFocus()
        }
    }

    /** Auto-opened (line too slow): closes by itself after 20 s without a choice. */
    private fun armSimilarClose(auto: Boolean) {
        similarCloseTask?.let { handler.removeCallbacks(it) }
        similarCloseTask = null
        if (!auto) { similarCountdown?.text = ""; similarCountdown?.visibility = View.GONE; return }
        var left = 10
        val t = object : Runnable {
            override fun run() {
                if (!similarOpen) return
                if (left <= 0) { closeSimilar(); return }
                similarCountdown?.visibility = View.VISIBLE
                similarCountdown?.text = "Staying on this channel in $left s — press any key to choose"
                left--
                handler.postDelayed(this, 1000)
            }
        }
        similarCloseTask = t
        t.run()
    }

    private var similarCountdown: TextView? = null

    private fun closeSimilar() {
        similarCloseTask?.let { handler.removeCallbacks(it) }
        similarCloseTask = null
        similarPanel?.visibility = View.GONE
        if (::keySink.isInitialized) keySink.requestFocus()
    }

    private fun playSimilar(from: PlayItem, sim: LiveCatalog.Similar) {
        val index = PlaybackQueue.index
        if (PlaybackQueue.items.getOrNull(index) !== from) return
        session?.action("similar → ${sim.item.title}${if (sim.group.isNotBlank()) " (${sim.group})" else ""}")
        slowSwitched.add(sim.item.saved.key)
        logHealth(from, "chose a similar channel", "“${sim.item.title}” ${sim.group}")
        PlaybackQueue.items = PlaybackQueue.items.toMutableList().also { it[index] = sim.item }
        startPlayback(index, quiet = false, keepTweaks = false)
    }

    private fun playOther(from: PlayItem, other: PlayItem) {
        val index = PlaybackQueue.index
        if (PlaybackQueue.items.getOrNull(index) !== from) return
        slowSwitched.add(other.saved.key)
        session?.action("other server → ${other.title}")
        PlaybackQueue.items = PlaybackQueue.items.toMutableList().also { it[index] = other }
        startPlayback(index, quiet = false, keepTweaks = false)
    }

    private fun switchToOtherServer(item: PlayItem) {
        val index = PlaybackQueue.index
        lifecycleScope.launch {
            val other = runCatching { LiveCatalog.sameChannelElsewhere(app, item) }.getOrNull()
            if (current !== item) return@launch
            if (other == null) {
                Toast.makeText(this@PlayerActivity, "“${item.title}” wasn't found on your other server", Toast.LENGTH_LONG).show()
                return@launch
            }
            slowSwitched.add(other.saved.key)
            PlaybackQueue.items = PlaybackQueue.items.toMutableList().also { it[index] = other }
            startPlayback(index, quiet = false, keepTweaks = false)
        }
    }

    // ------------------------------------------------------------------ open a channel that fails in another app

    private var otherAppPanel: LinearLayout? = null
    private var otherAppTask: Runnable? = null
    /** "Stay in Nova" was chosen: the next failure of this channel goes the usual way (skip / retry). */
    private var skipOtherApp = false

    private val otherAppOpen: Boolean get() = otherAppPanel?.visibility == View.VISIBLE
    private var otherAppCountdown: TextView? = null
    private var otherAppWhy = ""

    /** A key pressed in a popup: its countdown stops, you choose calmly. */
    private fun stopOtherAppCountdown() {
        val t = otherAppTask ?: return
        handler.removeCallbacks(t)
        otherAppTask = null
        otherAppCountdown?.text = "$otherAppWhy · Choose below"
    }

    private fun failAppLabel(): String {
        val pkg = app.storage.failApp
        if (pkg == "ask") return "another app"
        return ExternalPlayer.installed(this).firstOrNull { it.packageName == pkg }?.label ?: "another app"
    }

    /** "… can't be played here · Opening in VLC in 3 s" with Open now / Stay in Nova / Next channel. */
    private fun offerOtherApp(item: PlayItem, why: String, format: Boolean) {
        cancelTimers()
        player?.stop() // frees the server connection for the other app
        hideStatus()
        val label = failAppLabel()
        Log.i("NovaTest", "OTHER APP offer ${item.title}: $why -> $label")
        val panel = otherAppPanel ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(20), dp(26), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0xEE0A0E14.toInt()); setStroke(dp(2), 0xFFFFA31A.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.6f).toInt().coerceIn(dp(300), dp(680)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
            (playerView.parent as ViewGroup).addView(this)
            otherAppPanel = this
        }
        panel.removeAllViews()
        panel.addView(TextView(this).apply {
            text = "“${item.title}” can't be played here"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        val countdown = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(10))
        }
        panel.addView(countdown)
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFA31A.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { bottomMargin = dp(14) }
        }
        panel.addView(bar)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun button(text: String, action: () -> Unit) = pill(text) {}.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener { action() }
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(6), 0, dp(6), 0)
        }
        val openNow = button("▶ Open in $label now") { closeOtherApp(); openFailApp(item) }
        buttons.addView(openNow)
        buttons.addView(button("Stay in Nova") {
            closeOtherApp()
            skipOtherApp = true
            if (format) {
                if (item.isLive) channelFailed("format not supported by this device")
                else showStatus("The built-in player can't play this format.\n$why.")
            } else channelFailed("not working")
        })
        if (item.isLive && PlaybackQueue.items.size > 1) buttons.addView(button("Next channel") { closeOtherApp(); zap(1, auto = false) })
        panel.addView(buttons)
        panel.visibility = View.VISIBLE
        playerView.hideController()
        openNow.requestFocus()
        var ticks = 0
        val task = object : Runnable {
            override fun run() {
                if (!otherAppOpen || current !== item) return
                ticks++
                bar.progress = ticks
                val left = ((100 - ticks) + 9) / 10
                countdown.text = "$why · Opening in $label in $left s…"
                if (ticks >= 100) { closeOtherApp(); openFailApp(item) } else handler.postDelayed(this, 100)
            }
        }
        countdown.text = "$why · Opening in $label in 10 s…"
        otherAppCountdown = countdown
        otherAppWhy = why
        otherAppTask = task
        handler.postDelayed(task, 100)
    }

    private fun closeOtherApp() {
        otherAppTask?.let { handler.removeCallbacks(it) }
        otherAppTask = null
        otherAppPanel?.visibility = View.GONE
        if (::keySink.isInitialized) keySink.requestFocus()
    }

    private fun openFailApp(item: PlayItem) {
        saveResumePosition()
        player?.stop()
        val pkg = app.storage.failApp.takeIf { it != "ask" && it != "none" }
        Log.i("NovaTest", "OTHER APP open ${item.title} in ${pkg ?: "chooser"}")
        if (ExternalPlayer.open(this, item, pkg)) finish()
    }

    private fun cancelTimers() {
        reconnectTask?.let { handler.removeCallbacks(it) }
        reconnectTask = null
        handler.removeCallbacks(startTimeoutTask)
    }

    // ================================================================== auto-play next episode

    private lateinit var nextCard: TextView
    private var nextCardShown = false
    /** Back pressed on the card: this episode ends without playing the next one. */
    private var nextCancelled = false

    /** The next episode of the same series in the queue, when auto-play is on for that series. */
    private fun nextEpisodeIndex(): Int? {
        val item = current ?: return null
        val key = item.seriesKey ?: return null
        if (!app.storage.autoNext(key)) return null
        val n = PlaybackQueue.index + 1
        return n.takeIf { it in PlaybackQueue.items.indices && PlaybackQueue.items[it].seriesKey == key }
    }

    /** Shows the card during the last 15 seconds, counting down. */
    private fun nextEpisodeTick() {
        if (!::nextCard.isInitialized) return
        val p = player ?: return
        val item = current ?: return
        if (item.isLive || nextCancelled || !everReady) { if (nextCardShown && item.isLive) hideNextCard(); return }
        val n = nextEpisodeIndex() ?: run { if (nextCardShown) hideNextCard(); return }
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 30_000) return
        val left = dur - p.currentPosition
        if (left in 1..15_000) {
            val secs = ((left + 999) / 1000).toInt()
            val title = PlaybackQueue.items[n].title.substringAfter(" · ", PlaybackQueue.items[n].title)
            nextCard.text = "⏭  Next episode in $secs s\n$title\nOK = play now   ·   Back = cancel"
            if (!nextCardShown) {
                nextCardShown = true
                nextCard.visibility = View.VISIBLE
                Log.i("NovaTest", "NEXT episode card: $title")
            }
        } else if (nextCardShown && left > 15_000) hideNextCard()
    }

    private fun hideNextCard() {
        nextCardShown = false
        if (::nextCard.isInitialized) nextCard.visibility = View.GONE
    }

    private fun playNextEpisodeNow() {
        val n = nextEpisodeIndex() ?: run { hideNextCard(); return }
        hideNextCard()
        current?.let { app.storage.setPosition(it.key, 0) }
        Log.i("NovaTest", "NEXT episode: ${PlaybackQueue.items[n].title}")
        startPlayback(n)
    }

    /** A passing note ("reconnecting…") — the watchdog keeps watching; a final message (OK needed) stops it. */
    private var statusTransient = false

    private fun showStatus(text: String, transient: Boolean = false) {
        statusView.text = text
        statusView.visibility = View.VISIBLE
        statusTransient = transient
    }

    private fun hideStatus() {
        statusView.visibility = View.GONE
    }

    private fun zap(delta: Int, auto: Boolean) {
        val items = PlaybackQueue.items
        if (items.size < 2) return
        if (!auto) {
            autoSkips = 0
            lastDirection = if (delta < 0) -1 else 1
        }
        val next = (PlaybackQueue.index + delta + items.size) % items.size
        startPlayback(next)
    }

    // ================================================================== timeshift

    private fun timeshift(seconds: Int) {
        val r = relay ?: return
        r.moveBy(seconds)
        restartFromRelay()
    }

    private fun goLive() {
        val r = relay
        if (r == null) {
            // Without the disk buffer: back to the live edge of the stream.
            player?.let { it.seekToDefaultPosition(); it.prepare(); it.playWhenReady = true }
            return
        }
        // Exactly the cushion behind live, measured with the channel's real bitrate when known.
        if (mediaBps > 0) r.jumpBackFromNewest((app.storage.cushionSeconds * mediaBps).toLong())
        else r.jumpToLive(app.storage.cushionSeconds)
        bpsSampleBytes = -1L
        restartFromRelay()
    }

    private fun updateClock() {
        if (!::clockView.isInitialized || clockView.visibility != View.VISIBLE) return
        // Clock at the bottom: moves to the top right while the player buttons show (never over the time counter).
        val pos = app.storage.clockPosition
        if (pos == "bl" || pos == "br") {
            val shown = playerView.isControllerFullyVisible || (::vod.isInitialized && vod.controlsVisible)
            val want = if (shown) Gravity.TOP or Gravity.END else if (pos == "bl") Gravity.BOTTOM or Gravity.START else Gravity.BOTTOM or Gravity.END
            val lp = clockView.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && lp.gravity != want) { lp.gravity = want; clockView.layoutParams = lp }
        }
        clockView.text = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date())
    }

    /** Seconds of video kept ahead of what you see (disk + memory): how far behind live you are. */
    private fun behindLiveSeconds(): Int? {
        val p = player ?: return null
        val inMemory = ((p.bufferedPosition - p.currentPosition) / 1000).toInt().coerceAtLeast(0)
        val r = relay ?: return (p.totalBufferedDuration / 1000).toInt()
        val bps = mediaBps
        return (if (bps > 0) (r.bytesAhead() / bps).toInt() else r.secondsAhead()) + inMemory
    }

    private fun updateTimeLabel() {
        val r = relay ?: return
        if (timeView.visibility != View.VISIBLE) return
        val bps = mediaBps
        // Seconds from the channel's real bitrate when known (the download speed misleads on a weak signal).
        val behind = behindLiveSeconds() ?: 0
        val stored = if (bps > 0) (r.bytesStored() / bps).toInt() else r.secondsStored()
        val signal = if (bps > 0 && arrivalBps > 0) "   ·   Signal ${mbit(arrivalBps)} / needs ${mbit(bps)} Mbit/s" else ""
        timeView.text = if (stored <= 0) "Disk buffer: filling…"
        else "Behind live: ${formatDuration(behind * 1000L)}   ·   Stored on disk: ${formatDuration(stored * 1000L)}$signal"
    }

    // ================================================================== recording & downloads

    private fun toggleRecording() {
        val item = current ?: return
        if (Recorder.isRecording) {
            val f = Recorder.stop()
            Toast.makeText(this, "Recording saved to ${f?.parent ?: "Recordings"}", Toast.LENGTH_LONG).show()
        } else {
            if (playerMode == SourceMode.LIVE_HLS_DISK || item.isHls) {
                Toast.makeText(this, "Recording needs the MPEG-TS live format (Settings).", Toast.LENGTH_LONG).show()
                return
            }
            val f = Recorder.start(this, item.title, item.saved.icon)
            Toast.makeText(this, "Recording to ${f.absolutePath}", Toast.LENGTH_LONG).show()
        }
        updateRecButton()
    }

    private fun updateRecButton() {
        recButton.text = if (Recorder.isRecording) "■ Stop rec" else "● Rec"
        recButton.setTextColor(if (Recorder.isRecording) 0xFFFF6B6B.toInt() else Color.WHITE)
    }

    /** Plays the current channel / video in the external player app and closes this one. */
    // ------------------------------------------------------------------ 🔗 link of what plays (copy / QR)

    private val linkTexts = mutableListOf<TextView>()

    /** "🔗 http://…  [📋 Copy link] [▦ QR]" under the buttons. */
    private fun linkLine(): View {
        val url = TextView(this).apply {
            setTextColor(0xFFBFE9FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            text = current?.url?.let { "🔗 $it" } ?: ""
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        linkTexts += url
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(url)
            addView(pill("📋 Copy link") { copyLink() })
            addView(pill("▦ QR") { showLinkQr() })
        }
    }

    private fun copyLink() {
        val u = current?.url ?: return
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Nova link", u))
        Toast.makeText(this, "✓ Link copied — paste it in another app (1DM, VLC, a browser…)", Toast.LENGTH_LONG).show()
    }

    private fun showLinkQr() {
        val item = current ?: return
        val bmp = com.nova.iptv.ui.qrBitmap(item.url, 600) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(android.widget.ImageView(this@PlayerActivity).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            })
            addView(TextView(this@PlayerActivity).apply {
                text = "Scan with the phone camera to get the full link on the phone.\n\n⚠ This link contains your account user name and password — share it only with your own devices."
                setPadding(0, dp(10), 0, 0)
            })
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("▦ Link of " + item.title)
            .setView(box)
            .setPositiveButton("Close", null)
            .show()
    }

    /** 📺 Play on TV (phones): sends this video to Nova on the TV box; the phone pauses when asked. */
    private fun sendToTv() {
        val item = current ?: return
        val pos = player?.currentPosition ?: 0L
        com.nova.iptv.data.Cast.open(this, item, if (item.isLive) 0 else pos) { stop ->
            runOnUiThread { if (stop) { saveResumePosition(); player?.pause() } }
        }
    }

    /** Sent by a phone (📺 Play on TV): who sent it; the phone's remote reads the position and sends ⏸ / ±30 s / ■. */
    private var castFrom: String? = null
    private var castGen = -1
    private var spaceWarned = false

    private fun startCastReceiver() {
        val from = intent?.getStringExtra(com.nova.iptv.data.Cast.EXTRA_FROM) ?: return
        castFrom = from
        castGen = com.nova.iptv.data.Cast.generation.value
        // A newer video sent to this TV: this one closes.
        lifecycleScope.launch { com.nova.iptv.data.Cast.generation.collect { g -> if (g != castGen) finish() } }
        val at = PlaybackQueue.items.getOrNull(PlaybackQueue.index)?.let { if (it.isLive) 0L else app.storage.getPosition(it.key) } ?: 0L
        Toast.makeText(this, "📲 From $from" + (if (at > 0) " · continuing at ${formatDuration(at)}" else "") + "\nBack = return to what you were watching", Toast.LENGTH_LONG).show()
        val publish = object : Runnable {
            override fun run() {
                val p = player
                val item = current
                if (p != null && item != null && com.nova.iptv.data.Cast.generation.value == castGen) {
                    com.nova.iptv.data.Cast.now = com.nova.iptv.data.Cast.Now(item.title, p.currentPosition, if (item.isLive) 0 else p.duration.coerceAtLeast(0), p.playWhenReady)
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(publish, 1000)
        lifecycleScope.launch {
            com.nova.iptv.data.Cast.commands.collect { c ->
                val p = player ?: return@collect
                if (com.nova.iptv.data.Cast.generation.value != castGen) return@collect
                when {
                    c == "toggle" -> p.playWhenReady = !p.playWhenReady
                    c == "play" -> p.playWhenReady = true
                    c == "pause" -> p.playWhenReady = false
                    c.startsWith("seekto:") -> if (current?.isLive != true) p.seekTo((c.removePrefix("seekto:").toLongOrNull() ?: 0L).coerceAtLeast(0))
                    c == "stop" -> finish()
                    c.startsWith("seek:") -> if (current?.isLive != true) vod.seekBy(c.removePrefix("seek:").toLongOrNull() ?: 0L)
                }
            }
        }
    }

    private fun openExternal() {
        val item = current ?: return
        saveResumePosition()
        player?.stop()
        val choice = app.storage.playerFor(item.kind)
        val pkg = choice.takeIf { it != "builtin" && it != "ask" }
        if (ExternalPlayer.open(this, item, pkg)) finish()
    }

    private fun downloadCurrent() {
        val item = current ?: return
        val ext = item.downloadExt ?: return
        DownloadCenter.enqueue(this, item.title, item.url, ext, item.saved.type, item.saved.icon)
        Toast.makeText(this, "Download started — see the Downloads tab", Toast.LENGTH_LONG).show()
    }

    // ================================================================== misc

    private fun cycleAspect() {
        resizeIndex = (resizeIndex + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIndex]
        aspectButton.text = "Aspect: ${resizeNames[resizeIndex]}"
    }

    private fun toggleFavorite() {
        val item = current ?: return
        if (item.temporary) return
        val nowFav = app.storage.toggleFavorite(item.saved)
        Toast.makeText(this, if (nowFav) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        updateFavButton()
    }

    private fun updateFavButton() {
        val item = current ?: return
        favButton.text = if (app.storage.isFavorite(item.saved)) "♥ Favorite" else "♡ Favorite"
    }

    private fun loadEpg(item: PlayItem) {
        epgJob?.cancel()
        epgView.text = item.subtitle.orEmpty()
        epgView.visibility = if (epgView.text.isNullOrEmpty() && !item.isLive) View.GONE else View.VISIBLE
        if (!item.isLive) return
        val account = app.storage.account(item.saved.accountId) ?: return
        epgJob = lifecycleScope.launch {
            val list = try {
                app.api.shortEpg(account, item.saved.id)
            } catch (e: Exception) {
                emptyList()
            }
            if (current !== item) return@launch
            val (now, next) = XtreamApi.nowNext(list)
            val lines = buildList {
                if (now != null) add("Now  ${XtreamApi.formatTime(now.start)}–${XtreamApi.formatTime(now.end)}   ${now.title}")
                if (next != null) add("Next ${XtreamApi.formatTime(next.start)}   ${next.title}")
            }
            epgView.text = if (lines.isEmpty()) "No programme guide for this channel" else lines.joinToString("\n")
        }
    }

    private fun saveResumePosition() {
        val item = current ?: return
        val p = player ?: return
        if (item.isLive) return
        val duration = p.duration
        val pos = p.currentPosition
        if (duration <= 0) return
        if (pos > 10_000 && pos < duration - 60_000) app.storage.setPosition(item.key, pos)
        else if (pos >= duration - 60_000) app.storage.setPosition(item.key, 0)
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ================================================================== remote keys

    private var longPressHandled = false

    // ================================================================== remote control

    // ------------------------------------------------------------------ group & channel overlay (◀)

    private lateinit var groupListView: android.widget.ListView
    private var overlayGroups: List<LiveCatalog.Group> = emptyList()
    private var overlayChannels: List<PlayItem> = emptyList()
    private var overlayGroupKey: String? = null
    private var overlayJob: Job? = null
    /** The overlay's own highlight (works the same with any remote): column 0 = groups, 1 = channels. */
    private var panelColumn = 0
    private var groupCursor = 0
    private var channelCursor = 0

    /** Background of the overlays: see-through level from Settings → Display → Live TV view. */
    private fun overlayColor(): Int {
        val opaque = (100 - app.storage.overlayTransparency).coerceIn(10, 100)
        return ((opaque * 255 / 100) shl 24) or 0x0B0E12
    }

    private fun overlayList(): android.widget.ListView = android.widget.ListView(this).apply {
        divider = null
        selector = StateListDrawable().apply {
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xCC59606A.toInt()); setStroke(dp(2), 0xFF2EE66B.toInt()) })
        }
        isFocusable = false
        itemsCanFocus = false
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    }

    private fun overlayAdapter(
        labels: List<String>, cursor: () -> Int?,
        decorate: ((Int, String) -> CharSequence)? = null,
        highlight: (Int) -> Boolean,
    ) =
        object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                if (decorate != null) labels.getOrNull(position)?.let { v.text = decorate(position, it) }
                v.background = if (cursor() == position) GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat(); setColor(0xCC59606A.toInt()); setStroke(dp(2), 0xFF2EE66B.toInt())
                } else null
                v.setTextColor(if (highlight(position)) 0xFF2EE66B.toInt() else Color.WHITE)
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                v.maxLines = 1
                v.ellipsize = android.text.TextUtils.TruncateAt.END
                v.setShadowLayer(4f, 0f, 1f, Color.BLACK)
                return v
            }
        }

    /** Groups on the left, the highlighted group's channels on the right; the video keeps playing behind. */
    private fun buildChannelPanel(root: FrameLayout) {
        groupListView = overlayList().apply {
            setOnItemClickListener { _, _, position, _ ->
                groupCursor = position
                refreshOverlayCursor()
                overlayGroups.getOrNull(position)?.let { g -> loadOverlayGroup(g, delayMs = 0, focusChannels = true) }
            }
        }
        channelListView = overlayList().apply {
            setOnItemClickListener { _, _, position, _ -> pickOverlayChannel(position) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pill("←") { closeChannelPanelToInfo() })
            addView(TextView(this@PlayerActivity).apply {
                overlayTitle = this
                text = "Groups  ·  Channels"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        val lists = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(groupListView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f).apply { marginEnd = dp(8) })
            addView(channelListView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f))
        }
        channelPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            visibility = View.GONE
            addView(header)
            addView(lists, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val w = (resources.displayMetrics.widthPixels * 0.66f).toInt().coerceIn(dp(320), dp(820))
            layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        }
        root.addView(channelPanel)
    }

    private val channelPanelOpen: Boolean get() = ::channelPanel.isInitialized && channelPanel.visibility == View.VISIBLE

    private fun showChannelPanel() {
        playerView.hideController()
        hideRecentBar()
        channelPanel.setBackgroundColor(overlayColor())
        channelPanel.visibility = View.VISIBLE
        // Start with the list being watched, then fill in the groups.
        panelColumn = 0
        groupCursor = 0
        overlayGroups = listOf(LiveCatalog.Group(LiveCatalog.QUEUE, "▶ Current list"))
        groupListView.adapter = overlayAdapter(overlayGroups.map { it.title }, { groupCursorIfActive() }) { true }
        showOverlayChannels(LiveCatalog.QUEUE, PlaybackQueue.items)
        lifecycleScope.launch {
            val groups = runCatching { LiveCatalog.groups(app) }.getOrDefault(overlayGroups)
            if (!channelPanelOpen) return@launch
            overlayGroups = groups
            val currentKey = PlaybackQueue.groupKey?.takeIf { k -> groups.any { it.key == k } } ?: LiveCatalog.QUEUE
            groupCursor = groups.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
            groupListView.adapter = overlayAdapter(groups.map { it.title }, { groupCursorIfActive() }) { i -> groups[i].key == currentKey }
            groupListView.setSelection(maxOf(0, groupCursor - 3))
            Log.i("NovaTest", "OVERLAY groups ${groups.size}")
        }
    }

    private fun groupCursorIfActive(): Int? = groupCursor
    private fun channelCursorIfActive(): Int? = if (panelColumn == 1) channelCursor else null

    private fun refreshOverlayCursor() {
        (groupListView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
        (channelListView.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
    }

    /** Keeps the highlighted row on screen. */
    private fun scrollIntoView(list: android.widget.ListView, pos: Int) {
        val first = list.firstVisiblePosition
        val last = list.lastVisiblePosition
        if (pos < first || pos > last || last < 0) list.setSelection(maxOf(0, pos - 2))
        else if (pos == last) list.setSelection(maxOf(0, first + 1))
        else if (pos == first && first > 0) list.setSelection(first - 1)
    }

    private var overlayOkDown = false
    private var overlayOkLong = false

    /** Remote keys inside the overlay. Returns true when used. */
    private fun overlayKey(code: Int): Boolean {
        val step = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> -8
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> 8
            else -> 0
        }
        if (step != 0) {
            if (panelColumn == 0) {
                if (overlayGroups.isEmpty()) return true
                groupCursor = (groupCursor + step).coerceIn(0, overlayGroups.lastIndex)
                scrollIntoView(groupListView, groupCursor)
                overlayGroups.getOrNull(groupCursor)?.let { loadOverlayGroup(it, delayMs = 300) }
            } else {
                if (overlayChannels.isEmpty()) return true
                channelCursor = (channelCursor + step).coerceIn(0, overlayChannels.lastIndex)
                scrollIntoView(channelListView, channelCursor)
            }
            refreshOverlayCursor()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (panelColumn == 0 && overlayChannels.isNotEmpty()) { panelColumn = 1; scrollIntoView(channelListView, channelCursor) }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> panelColumn = 0
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (panelColumn == 0) {
                    overlayGroups.getOrNull(groupCursor)?.let { loadOverlayGroup(it, delayMs = 0, focusChannels = true) }
                } else {
                    pickOverlayChannel(channelCursor)
                    return true
                }
            }
            else -> return false
        }
        refreshOverlayCursor()
        return true
    }

    private fun loadOverlayGroup(g: LiveCatalog.Group, delayMs: Long, focusChannels: Boolean = false) {
        if (g.key == overlayGroupKey && overlayChannels.isNotEmpty()) {
            if (focusChannels) {
                panelColumn = 1; refreshOverlayCursor()
            }
            return
        }
        overlayJob?.cancel()
        overlayJob = lifecycleScope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            val list = runCatching { LiveCatalog.channels(app, g.key) }.getOrDefault(emptyList())
            if (!channelPanelOpen) return@launch
            showOverlayChannels(g.key, list)
            Log.i("NovaTest", "OVERLAY group ${g.title}: ${list.size} channels")
            if (focusChannels && list.isNotEmpty()) { panelColumn = 1; refreshOverlayCursor() }
        }
    }

    private fun showOverlayChannels(key: String, list: List<PlayItem>) {
        overlayGroupKey = key
        overlayChannels = list
        val playingKey = current?.saved?.key
        val labels = if (list.isEmpty()) listOf("(no channels)") else list.mapIndexed { i, it -> "${i + 1}.  ${it.title}" }
        val playing = list.indexOfFirst { it.saved.key == playingKey }
        channelCursor = playing.coerceAtLeast(0)
        channelListView.adapter = overlayAdapter(labels, { channelCursorIfActive() }, { i, label ->
            list.getOrNull(i)?.let { dotLabel(it, label, it.saved.key == playingKey) } ?: label
        }) { i -> list.getOrNull(i)?.saved?.key == playingKey }
        channelListView.setSelection(maxOf(0, channelCursor - 3))
        updateOverlayTitle()
    }

    // ------------------------------------------------------------------ status dots in the channel list

    private var overlayTitle: TextView? = null

    private fun checkKey(item: PlayItem): String? =
        if (item.isLocal || item.catchup || item.temporary || item.saved.accountId.isBlank()) null
        else StreamCheck.key(item.saved.type, item.saved.accountId, item.saved.id)


    /** "●  12.  M6 4K   no signal" with the dot in its colour. */
    private fun dotLabel(item: PlayItem, label: String, watching: Boolean): CharSequence {
        if (!StreamCheck.enabled) return label
        val k = checkKey(item) ?: return label
        val st = if (watching && everReady) StreamCheck.Status(StreamCheck.State.OK) else StreamCheck.status(k)
        // Live: only channels you actually played have a dot.
        if (st.state != StreamCheck.State.OK && st.state != StreamCheck.State.BAD && st.state != StreamCheck.State.DEVICE) return label
        val sb = android.text.SpannableStringBuilder()
        val mark = if (st.state == StreamCheck.State.CHECKING) "◌" else "●"
        val color = when (st.state) {
            StreamCheck.State.OK -> 0xFF2EE66B.toInt()
            StreamCheck.State.BAD -> 0xFFFF4D4D.toInt()
            StreamCheck.State.DEVICE -> 0xFFFFA31A.toInt()
            StreamCheck.State.CHECKING -> Color.WHITE
            else -> 0xFF8A9099.toInt()
        }
        sb.append(mark, android.text.style.ForegroundColorSpan(color), 0)
        sb.append("  ").append(label)
        if (watching) sb.append("   ▶")
        val note = when (st.state) {
            StreamCheck.State.BAD, StreamCheck.State.WAITING -> st.note
            StreamCheck.State.DEVICE -> "this device can't play its format"
            else -> null
        }
        if (note != null) {
            val start = sb.length
            sb.append("   ").append(note)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.72f), start, sb.length, 0)
            sb.setSpan(
                android.text.style.ForegroundColorSpan(
                    when (st.state) { StreamCheck.State.BAD -> 0xFFFFB3B3.toInt(); StreamCheck.State.DEVICE -> 0xFFFFD08A.toInt(); else -> 0xFFBBBBBB.toInt() },
                ),
                start, sb.length, 0,
            )
        }
        return sb
    }

    private fun updateOverlayTitle() {
        overlayTitle?.text = "Groups  ·  Channels"
    }

    private fun pickOverlayChannel(position: Int) {
        val list = overlayChannels
        if (position !in list.indices) return
        val key = overlayGroupKey
        hideChannelPanel()
        if (key != LiveCatalog.QUEUE) {
            PlaybackQueue.items = list
            PlaybackQueue.groupKey = key
            previousIndex = -1
        }
        if (list[position].saved.key != current?.saved?.key || key != LiveCatalog.QUEUE) startPlayback(position)
    }

    /** Back from the channel list (live TV): the channel info and buttons show, Next highlighted; they hide after 5 s or with Back. */
    private fun closeChannelPanelToInfo() {
        hideChannelPanel()
        if (current?.isLive != true || pictureHidden) return
        playerView.showController()
        handler.post { if (nextButton.visibility == View.VISIBLE) nextButton.requestFocus() }
    }

    private fun hideChannelPanel() {
        Log.i("NovaTest", "channel list closed")
        overlayJob?.cancel()
        channelPanel.visibility = View.GONE
        keySink.requestFocus()
    }

    // ------------------------------------------------------------------ recently watched bar

    private lateinit var recentBar: LinearLayout
    private lateinit var recentRow: LinearLayout
    private var recentItems: List<PlayItem> = emptyList()
    private val hideRecentTask = Runnable { hideRecentBar() }

    private val recentBarOpen: Boolean get() = ::recentBar.isInitialized && recentBar.visibility == View.VISIBLE

    private fun buildRecentBar(root: FrameLayout) {
        recentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recentBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(14))
            visibility = View.GONE
            addView(TextView(this@PlayerActivity).apply {
                text = "Recently watched"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), 0, 0, dp(6))
            })
            addView(HorizontalScrollView(this@PlayerActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(recentRow)
            })
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        root.addView(recentBar)
    }

    private fun showRecentBar() {
        val items = LiveCatalog.recentChannels(app, current?.saved?.key)
        if (items.isEmpty()) {
            Toast.makeText(this, "No other channels watched yet", Toast.LENGTH_SHORT).show()
            return
        }
        playerView.hideController()
        if (channelPanelOpen) hideChannelPanel()
        recentItems = items
        recentBar.setBackgroundColor(overlayColor())
        recentRow.removeAllViews()
        items.forEachIndexed { i, item -> recentRow.addView(recentTile(item, i)) }
        recentBar.visibility = View.VISIBLE
        recentRow.getChildAt(0)?.requestFocus()
        Log.i("NovaTest", "RECENT bar: " + items.joinToString(", ") { it.title })
        bumpRecentBar()
    }

    private fun bumpRecentBar() {
        handler.removeCallbacks(hideRecentTask)
        handler.postDelayed(hideRecentTask, 10_000)
    }

    private fun hideRecentBar() {
        if (!::recentBar.isInitialized) return
        handler.removeCallbacks(hideRecentTask)
        if (recentBar.visibility == View.VISIBLE) {
            recentBar.visibility = View.GONE
            keySink.requestFocus()
        }
    }

    private fun recentTile(item: PlayItem, index: Int): View {
        fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color)
            if (stroke != 0) setStroke(dp(3), stroke)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xCC59606A.toInt(), 0xFF2EE66B.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xCC59606A.toInt(), 0))
            addState(intArrayOf(), shape(0x33FFFFFF, 0))
        }
        val logo = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(64))
        }
        item.saved.icon?.takeIf { it.isNotBlank() }?.let { url -> runCatching { coil.Coil.imageLoader(this).enqueue(coil.request.ImageRequest.Builder(this).data(url).target(logo).build()) } }
        val name = TextView(this).apply {
            text = item.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = states
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            addView(logo)
            addView(name)
            setOnFocusChangeListener { _, has -> if (has) bumpRecentBar() }
            setOnClickListener { pickRecent(index) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
        }
    }

    private fun pickRecent(index: Int) {
        val item = recentItems.getOrNull(index) ?: return
        hideRecentBar()
        val inQueue = PlaybackQueue.items.indexOfFirst { it.saved.key == item.saved.key }
        if (inQueue >= 0) {
            startPlayback(inQueue)
        } else {
            // Not in the list being watched: zap through the recent channels instead.
            PlaybackQueue.items = recentItems
            PlaybackQueue.groupKey = null
            previousIndex = -1
            startPlayback(index)
        }
    }

    private fun typeDigit(d: Int) {
        if (typedNumber.length >= 5) return
        typedNumber += d
        numberView.text = typedNumber + "_"
        numberView.visibility = View.VISIBLE
        handler.removeCallbacks(numberTask)
        handler.postDelayed(numberTask, 2000)
    }

    private fun jumpToTypedNumber() {
        val n = typedNumber.toIntOrNull()
        typedNumber = ""
        numberView.visibility = View.GONE
        val items = PlaybackQueue.items
        if (n == null) return
        if (n in 1..items.size) {
            if (n - 1 != PlaybackQueue.index) startPlayback(n - 1)
        } else {
            Toast.makeText(this, "No channel $n in this list (1–${items.size})", Toast.LENGTH_SHORT).show()
        }
    }

    /** Runs a remote action; returns false if it doesn't apply right now. */
    private fun runAction(action: String): Boolean {
        val item = current ?: return false
        Log.i("NovaTest", "KEY $action")
        when (action) {
            "live_next" -> zap(1, auto = false)
            "live_prev" -> zap(-1, auto = false)
            "live_list" -> showChannelPanel()
            "live_recent" -> showRecentBar()
            "live_info" -> playerView.showController()
            "live_recall" -> {
                val prev = previousIndex
                if (prev in PlaybackQueue.items.indices && prev != PlaybackQueue.index) startPlayback(prev)
                else Toast.makeText(this, "No previous channel yet", Toast.LENGTH_SHORT).show()
            }
            "live_pause", "vod_play_pause" -> {
                if (!item.isLive) { vod.togglePlay(); vod.show() } else player?.let { it.playWhenReady = !it.playWhenReady }
            }
            "live_golive" -> {
                Log.i("NovaTest", "KEY jump to live")
                goLive()
            }
            "live_rewind", "live_forward" -> {
                if (relay == null) Toast.makeText(this, "Rewind needs Settings → Live TV buffer on disk", Toast.LENGTH_SHORT).show()
                else timeshift(if (action == "live_rewind") -30 else 30)
            }
            "live_record" -> toggleRecording()
            "live_favorite", "vod_favorite" -> toggleFavorite()
            "live_aspect", "vod_aspect" -> {
                cycleAspect()
                vod.setAspectLabel(resizeNames[resizeIndex])
                Toast.makeText(this, "Aspect: ${resizeNames[resizeIndex]}", Toast.LENGTH_SHORT).show()
            }
            "live_tracks", "vod_tracks" -> { playerView.hideController(); vod.openPanel() }
            "live_external", "vod_external" -> openExternal()
            "vod_back" -> { vod.seekBy(-10_000); vod.show() }
            "vod_forward" -> { vod.seekBy(10_000); vod.show() }
            "vod_back_long" -> { vod.seekBy(-60_000); vod.show() }
            "vod_forward_long" -> { vod.seekBy(60_000); vod.show() }
            "vod_controls" -> vod.showAndFocus()
            "vod_next", "vod_prev" -> {
                val target = PlaybackQueue.index + if (action == "vod_next") 1 else -1
                if (target in PlaybackQueue.items.indices) startPlayback(target)
                else Toast.makeText(this, if (action == "vod_next") "This is the last episode" else "This is the first episode", Toast.LENGTH_SHORT).show()
            }
            "vod_download" -> if (!item.isLocal && item.downloadExt != null) downloadCurrent() else return false
            "play_hide" -> if (pictureHidden) showPicture() else hidePicture()
            "play_night" -> toggleNight()
            "play_boost" -> showBoost()
            else -> return false
        }
        return true
    }

    private fun focusFirstPill() {
        val row = listOf(prevButton, nextButton, rewindButton, liveButton, recButton, favButton)
        (row.firstOrNull { it.visibility == View.VISIBLE } ?: favButton).requestFocus()
    }

    // ================================================================== stream watchdog

    private var wdLastPos = -1L
    private var wdPosSince = 0L
    private var wdLastBuffered = -1L
    private var wdBufSince = 0L
    private var wdBufferingSince = 0L
    private var wdLastFrames = -1
    private var wdFramesSince = 0L
    private var wdGraceUntil = 0L
    private val wdEvents = ArrayDeque<Long>()
    private var networkLostAt = 0L
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun wdReset(now: Long) {
        wdLastPos = -1L; wdPosSince = now
        wdLastBuffered = -1L; wdBufSince = now
        wdBufferingSince = 0L
        wdLastFrames = -1; wdFramesSince = now
    }

    /**
     * Every second: is the stream still moving? Catches the stops that give no error
     * (provider keeps the connection open but sends nothing, frozen picture, frozen clock).
     */
    private var cushionSlow = false

    /**
     * Live "fast start": the channel starts with ~2 s collected, close to live; the full safety cushion
     * is then built while you watch by playing 3 % slower (voice pitch unchanged) until it is there.
     * The same quietly rebuilds the cushion after a hiccup used part of it.
     */
    private fun cushionTick() {
        val item = current
        val p = player
        // Target: the cushion, grown while you watch up to "Grow while watching" (60 s by default).
        val grow = app.storage.liveGrowTarget
        val base = if (weakChannel && app.storage.weakRefillSecs > 0) maxOf(app.storage.cushionSeconds, app.storage.weakRefillSecs)
        else app.storage.cushionSeconds
        relay?.let { r -> if (mediaBps > 0) r.mediaBytesPerSec = mediaBps }
        session?.let { ss ->
            if (item != null && item.isLive && p != null && p.isPlaying) {
                if (mediaBps > 0) ss.needMbit = mediaBps * 8 / 1_000_000
                if (arrivalBps > 0) ss.arrival(arrivalBps * 8 / 1_000_000)
                ss.cushion(behindLiveSeconds() ?: -1)
            }
        }
        // Disk buffer: grow to the chosen cushion (the disk keeps the rest for rewind / pauses, up to its length).
        // Memory only: at most the read-ahead, and on HLS channels at most what the server still keeps.
        var want = if (relay != null) minOf(maxOf(base, grow), app.storage.liveDiskMinutes * 60 - 30) * 1000L
        else minOf(maxOf(base, grow), maxOf(base, app.storage.maxBufferSeconds - 5)) * 1000L
        if (relay == null && p != null) serverWindowMs(p)?.let { w -> if (w > 20_000) want = minOf(want, maxOf(base * 1000L, w - 10_000)) }
        lastGrowWantMs = want
        val active = item != null && item.isLive && !item.catchup && p != null &&
            (app.storage.liveStart == "fast" || weakChannel || grow > base) && !refilling &&
            p.isPlaying && p.playbackState == Player.STATE_READY
        if (!active) {
            if (cushionSlow) p?.setPlaybackSpeed(1f)
            cushionSlow = false
            return
        }
        val ahead = (behindLiveSeconds() ?: 0) * 1000L
        slowLineTick(item!!, ahead)
        if (!cushionSlow && ahead < want - 1500) {
            cushionSlow = true
            p!!.setPlaybackSpeed(0.96f)
            Log.i("NovaTest", "CUSHION building: ${ahead / 1000} s of ${want / 1000} s")
        } else if (cushionSlow && ahead >= want) {
            cushionSlow = false
            p!!.setPlaybackSpeed(1f)
            Log.i("NovaTest", "CUSHION ready: ${ahead / 1000} s")
        }
    }

    /** The cushion the player is growing to (for the "drifting behind live" check). */
    private var lastGrowWantMs = 0L

    /** How much of a live HLS channel the server still keeps (its window), null when not known. */
    private fun serverWindowMs(p: Player): Long? {
        val tl = p.currentTimeline
        if (tl.isEmpty) return null
        val w = tl.getWindow(p.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        return if (w.isLive() && w.durationMs != C.TIME_UNSET && w.durationMs > 0) w.durationMs else null
    }

    private var slowSince = 0L
    private var lowWarnAt = 0L
    /** Channels already switched because the line was too slow (no back-and-forth). */
    private val slowSwitched = HashSet<String>()

    /**
     * The line stays slower than the channel needs: warn when the cushion is almost used up, and after ~20 s
     * play the same channel from your other server (once per channel).
     */
    private fun slowLineTick(item: PlayItem, aheadMs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        val tooSlow = mediaBps > 0 && arrivalBps > 0 && arrivalBps < mediaBps * 0.9
        if (aheadMs in 1..5_000 && everReady && now - lowWarnAt > 60_000) {
            lowWarnAt = now
            Toast.makeText(this, "⚠ Slow connection — only ${aheadMs / 1000} s of cushion left" +
                (if (tooSlow) " (${mbit(arrivalBps)} of ${mbit(mediaBps)} Mbit/s)" else ""), Toast.LENGTH_SHORT).show()
        }
        if (!tooSlow) { slowSince = 0L; return }
        if (slowSince == 0L) slowSince = now
        val s = app.storage
        if (now - slowSince >= 20_000) session?.let { ss ->
            if (ss.result == "OK") { ss.result = "SLOW"; ss.note = "line too slow: ${mbit(arrivalBps)} of ${mbit(mediaBps)} Mbit/s" }
        }
        if (now - slowSince >= 20_000 && s.liveSlowAction == "similar" && !item.temporary && !similarOpen && slowSwitched.add(item.saved.key)) {
            slowSince = 0L
            showSimilar(auto = true)
            return
        }
        if (now - slowSince < 20_000 || !s.liveSwitchWhenSlow || s.accounts.size < 2 || item.temporary) return
        if (!slowSwitched.add(item.saved.key)) return
        slowSince = 0L
        val index = PlaybackQueue.index
        lifecycleScope.launch {
            val other = runCatching { LiveCatalog.sameChannelElsewhere(app, item) }.getOrNull()
            if (other == null || current !== item) return@launch
            slowSwitched.add(other.saved.key)
            PlaybackQueue.items = PlaybackQueue.items.toMutableList().also { it[index] = other }
            val from = s.accounts.firstOrNull { it.id == item.saved.accountId }?.name ?: "this server"
            val acc = s.accounts.firstOrNull { it.id == other.saved.accountId }?.name ?: "another account"
            logHealth(item, "line too slow (${mbit(arrivalBps)} of ${mbit(mediaBps)} Mbit/s)", "switched to “${other.title}” on $acc")
            Log.i("NovaTest", "SLOW LINE ${item.title} -> ${other.title} on $acc")
            Toast.makeText(this@PlayerActivity, "Switched to “${other.title}” ($acc) — $from too slow", Toast.LENGTH_LONG).show()
            startPlayback(index, quiet = true, keepTweaks = false)
        }
    }

    private var wdDiagAt = 0L

    private fun watchdogTick() {
        val s = app.storage
        if (!s.watchdog) return
        val item = current ?: return
        val p = player ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (item.isLive && now - wdDiagAt > 5000) {
            wdDiagAt = now
            Log.i("NovaTest", "WD ${item.title}: state=${p.playbackState} pwr=${p.playWhenReady} ready=$everReady grace=${(wdGraceUntil - now).coerceAtLeast(0) / 1000}s " +
                "status=${statusView.visibility == View.VISIBLE}/$statusTransient mode=${s.watchdogMode} buf=${if (wdBufferingSince > 0) (now - wdBufferingSince) / 1000 else 0}s " +
                "arriving=${videoArriving()} quiet=${if (notArrivingSince > 0) (now - notArrivingSince) / 1000 else 0}s arrBps=${arrivalBps.toInt()} mediaBps=${mediaBps.toInt()} " +
                "ahead=${relay?.bytesAhead() ?: -1} frames=$wdLastFrames refill=$refilling")
        }
        if (!everReady || !p.playWhenReady || now < wdGraceUntil || (statusView.visibility == View.VISIBLE && !statusTransient)) { wdReset(now); return }
        val liveOnly = s.watchdogMode == "live" && item.isLive
        val limit = if (liveOnly) maxOf(10, s.watchdogSecs) * 1000L else s.watchdogSecs * 1000L
        if (liveOnly) {
            // Track how long no video has been arriving (renew the connection only after 30 s of that).
            if (relay != null && !videoArriving()) { if (notArrivingSince == 0L) notArrivingSince = now }
            else notArrivingSince = 0L
        }
        when (p.playbackState) {
            Player.STATE_BUFFERING -> {
                if (wdBufferingSince == 0L) wdBufferingSince = now
                // Video still arriving, just too slowly: pause and refill instead of reconnecting.
                if (item.isLive && playerMode == SourceMode.LIVE_RELAY && s.weakRefillSecs > 0 && s.watchdogMode == "full" &&
                    rebuffers.size >= 2 && now - wdBufferingSince > 1500 && videoArriving()
                ) {
                    startRefill(item)
                    return
                }
                val allowed = if (item.isLive) limit else limit * 2
                if (now - wdBufferingSince > allowed) watchdogAct(item, "stuck loading for ${(now - wdBufferingSince) / 1000} s")
                return
            }
            Player.STATE_READY -> wdBufferingSince = 0L
            else -> { wdReset(now); return }
        }
        // Playback clock.
        val pos = p.currentPosition
        if (pos != wdLastPos) { wdLastPos = pos; wdPosSince = now }
        else if (now - wdPosSince > maxOf(5000L, limit * 3 / 4)) { watchdogAct(item, "clock frozen for ${(now - wdPosSince) / 1000} s"); return }
        // New video frames.
        val counters = p.videoDecoderCounters
        if (counters != null && p.videoFormat != null) {
            counters.ensureUpdated()
            val frames = counters.renderedOutputBufferCount
            if (frames != wdLastFrames) { wdLastFrames = frames; wdFramesSince = now }
            else if (now - wdFramesSince > maxOf(5000L, limit * 5 / 8)) {
                val sound = p.audioDecoderCounters?.let { a -> a.ensureUpdated(); val n = a.renderedOutputBufferCount; val moved = n != wdLastAudio; wdLastAudio = n; moved } ?: false
                val ahead = relay?.let { r -> if (mediaBps > 0) " · ${(r.bytesAhead() / mediaBps).toInt()} s stored ahead" else "" } ?: ""
                watchdogAct(item, "picture frozen for ${(now - wdFramesSince) / 1000} s" + (if (sound) " (sound kept playing)" else "") + ahead)
                return
            }
        }
        if (!item.isLive) return
        // Data still arriving from the provider (reconnect before the buffer runs out).
        val buffered = p.bufferedPosition
        if (buffered > wdLastBuffered + 200) { wdLastBuffered = buffered; wdBufSince = now }
        else if (now - wdBufSince > limit) { watchdogAct(item, "no data from the provider for ${(now - wdBufSince) / 1000} s"); return }
        // Drifting behind live (memory buffer only; the disk buffer is behind on purpose).
        if (playerMode != SourceMode.LIVE_RELAY) {
            val offset = p.currentLiveOffset
            // Never below the cushion being grown (+30 s): growing it must not count as "drifting".
            val driftLimit = maxOf(60_000L, lastGrowWantMs + 30_000L, app.storage.cushionSeconds * 1000L + 30_000L)
            if (offset != androidx.media3.common.C.TIME_UNSET && offset > driftLimit) {
                p.seekToDefaultPosition()
                logHealth(item, "${offset / 1000} s behind live", "jumped back to live")
                wdGraceUntil = now + 10_000
            }
        }
    }

    /**
     * "Live button only" watchdog: does exactly what the Live ⏭ button does (jump to live in the disk buffer and
     * restart the picture there), at most once every 20 s. Renews the connection only after 30 s with no video at all.
     */
    private val wdLiveFixes = ArrayDeque<Long>()
    private var repairBehindBefore: Int? = null
    private var wdLastAudio = -1
    private var repairNote = ""

    private fun liveOnlyFix(item: PlayItem, problem: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val r = relay
        val quietFor = if (notArrivingSince > 0) now - notArrivingSince else 0L
        val action: String? = when {
            r != null && quietFor >= 30_000 -> {
                r.reconnect()
                notArrivingSince = 0L
                restartOnStoredVideo(item, newPlayer = false)
                "reconnected to the provider (stored video kept)"
            }
            // Nothing new stored to jump to: let the stream come back by itself (no needless restarts).
            r != null && notArrivingSince > 0 -> null
            r != null && problem.startsWith("no data") -> null
            // Waiting for video that isn't there yet (nothing stored ahead, on disk or in memory) because it arrives
            // slowly: let it come. When data arrives at full speed and the player still waits, the picture itself is
            // stuck: jump to live (below).
            r != null && mediaBps > 0 && problem.startsWith("stuck loading") && arrivalBps < mediaBps * 0.9 &&
                r.bytesAhead() / mediaBps * 1000 + (player?.totalBufferedDuration ?: 0L) < 2000 -> null
            // First time: restart the picture at the same place (the cushion is kept). Again within 2 minutes:
            // 10 s further on. Only a third time: back to live (still the safety cushion behind it).
            else -> {
                while (wdLiveFixes.isNotEmpty() && now - wdLiveFixes.first() > 120_000) wdLiveFixes.removeFirst()
                wdLiveFixes.addLast(now)
                when {
                    r != null && wdLiveFixes.size == 1 -> { restartOnStoredVideo(item, newPlayer = false); "restarted the picture (cushion kept)" }
                    r != null && wdLiveFixes.size == 2 -> { r.moveBy(10); restartOnStoredVideo(item, newPlayer = true); "skipped 10 s, new player (cushion kept)" }
                    // Plenty stored ahead (30 s or more): never throw it away — restart again a bit further on.
                    r != null && mediaBps > 0 && r.bytesAhead() / mediaBps >= 30 -> { r.moveBy(5); restartOnStoredVideo(item, newPlayer = true); "skipped 5 s, new player (cushion kept)" }
                    else -> { goLive(); "jumped to live" }
                }
            }
        }
        wdReset(now)
        if (action == null) { Log.i("NovaTest", "WD ${item.title}: $problem -> waiting (quiet ${quietFor / 1000} s)"); return }
        wdGraceUntil = now + 20_000
        logHealth(item, problem, action)
        if (app.storage.watchdogNotes) Toast.makeText(this, "⟳ ${action.replaceFirstChar { it.uppercase() }}", Toast.LENGTH_SHORT).show()
    }

    /** Is video still coming in from the provider (at least a fifth of what the channel needs)? */
    private fun videoArriving(): Boolean {
        val r = relay ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (r.lastDataAt == 0L || now - r.lastDataAt > 4000) return false
        val need = if (mediaBps > 0) mediaBps * 0.2 else 20_000.0
        return arrivals.size < 3 || arrivalBps >= need
    }

    /** Every second: the channel's real bitrate (bytes per second of video) and how fast video arrives. */
    private fun measureSignal() {
        val r = relay
        val p = player
        if (r == null || p == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        arrivals.addLast(now to r.bytesStoredTotal())
        while (arrivals.size > 6) arrivals.removeFirst()
        if (arrivals.size >= 3) {
            val (t0, b0) = arrivals.first()
            val (t1, b1) = arrivals.last()
            if (t1 > t0) arrivalBps = (b1 - b0) * 1000.0 / (t1 - t0)
        }
        if (!everReady) return
        val bytes = r.bytesRead
        val buf = p.bufferedPosition
        if (bpsSampleBytes < 0 || buf < bpsSampleBuf || bytes < bpsSampleBytes) { bpsSampleBytes = bytes; bpsSampleBuf = buf; return }
        val dt = buf - bpsSampleBuf
        if (dt >= 4000) {
            val est = (bytes - bpsSampleBytes) * 1000.0 / dt
            if (est > 10_000) mediaBps = if (mediaBps == 0.0) est else mediaBps * 0.7 + est * 0.3
            bpsSampleBytes = bytes; bpsSampleBuf = buf
        }
    }

    private fun refillTarget(): Long = app.storage.weakRefillSecs * 1000L

    /** Weak signal: pause, keep everything stored, play on once enough video is stored. */
    private fun startRefill(item: PlayItem) {
        if (refilling) return
        val p = player ?: return
        refilling = true
        weakChannel = true
        p.playWhenReady = false
        if (p.playbackParameters.speed != 1f) p.setPlaybackSpeed(1f)
        cushionSlow = false
        Log.i("NovaTest", "WEAK refill started ${item.title}")
        logHealth(item, "weak signal (video arriving too slowly)", "paused to refill ${app.storage.weakRefillSecs} s (stored video kept)")
        refillTick()
    }

    private fun refillTick() {
        if (!refilling) return
        val p = player
        val item = current
        if (p == null || item == null) { refilling = false; return }
        val ahead = p.totalBufferedDuration
        val target = refillTarget()
        if (ahead >= target) {
            finishRefill("buffer refilled (${ahead / 1000} s)")
            return
        }
        val r = relay
        val now = android.os.SystemClock.elapsedRealtime()
        if (r != null && (r.lastDataAt == 0L || now - r.lastDataAt > 10_000)) {
            // Nothing at all for 10 s: renew the connection, keep what is stored.
            r.reconnect()
            logHealth(item, "no video for 10 s while refilling", "reconnected to the provider (stored video kept)")
        }
        val sb = StringBuilder("Weak signal — building a buffer: ${ahead / 1000} / ${target / 1000} s")
        val dl = arrivalBps
        if (mediaBps > 0 && dl > 0) {
            sb.append("\nYour connection: ${mbit(dl)} Mbit/s  ·  this channel needs ${mbit(mediaBps)}")
            if (dl < mediaBps * 0.9) sb.append("\nYour connection is slower than this channel needs — an SD version may play better.")
        }
        sb.append("\nOK: play now")
        showStatus(sb.toString())
    }

    private fun finishRefill(why: String) {
        if (!refilling) return
        refilling = false
        hideStatus()
        val now = android.os.SystemClock.elapsedRealtime()
        wdReset(now)
        wdGraceUntil = now + 10_000
        player?.playWhenReady = true
        current?.let { Log.i("NovaTest", "WEAK refill done ${it.title}: $why") }
    }

    private fun mbit(bytesPerSec: Double): String = String.format(java.util.Locale.US, "%.1f", bytesPerSec * 8 / 1_000_000)

    /** Watchdog with the disk buffer: renew the provider connection and/or restart the player — never throw the stored video away. */
    private fun recoverKeepingBuffer(item: PlayItem, problem: String): String {
        val r = relay ?: return "nothing to do"
        val providerQuiet = !videoArriving()
        if (providerQuiet) r.reconnect()
        val p = player
        val restart = p == null || p.playbackState != Player.STATE_READY || problem.contains("frozen")
        if (restart) restartOnStoredVideo(item, newPlayer = false)
        return when {
            providerQuiet && restart -> "reconnected to the provider and restarted the player (stored video kept)"
            providerQuiet -> "reconnected to the provider (stored video kept)"
            else -> "restarted the player on the stored video"
        }
    }

    /** Restarts the player on the same disk buffer, re-reading what it had in memory. */
    private fun restartOnStoredVideo(item: PlayItem, newPlayer: Boolean) {
        val r = relay ?: run { startPlayback(PlaybackQueue.index, quiet = true, keepTweaks = true); return }
        if (!storedRestart) { storedRestart = true; storedRestartTries = 0; repairBehindBefore = behindLiveSeconds() }
        val p = player
        if (p != null && mediaBps > 0) {
            val memMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
            r.moveBackBytes((memMs / 1000.0 * mediaBps).toLong())
        }
        if (newPlayer) { player?.release(); player = null; playerMode = null } else player?.stop()
        everReady = false
        refilling = false
        bpsSampleBytes = -1L
        val np = ensurePlayer(SourceMode.LIVE_RELAY)
        np.setMediaItem(buildMediaItem(item))
        np.prepare()
        np.playWhenReady = true
        handler.removeCallbacks(startTimeoutTask)
        handler.postDelayed(startTimeoutTask, startTimeoutMs())
    }

    /** Fixes a stopped stream, a bit more firmly each time it happens within 2 minutes. */
    private fun watchdogAct(item: PlayItem, problem: String) {
        val s = app.storage
        if (s.watchdogMode == "live" && item.isLive) { liveOnlyFix(item, problem); return }
        val now = android.os.SystemClock.elapsedRealtime()
        while (wdEvents.isNotEmpty() && now - wdEvents.first() > 120_000) wdEvents.removeFirst()
        wdEvents.addLast(now)
        val n = wdEvents.size
        wdGraceUntil = now + s.watchdogSecs * 1000L + 10_000
        wdReset(now)
        val index = PlaybackQueue.index
        val action: String = when {
            !item.isLive -> {
                val p = player
                if (p != null) { p.seekTo(p.currentPosition); p.prepare(); p.playWhenReady = true }
                "reloaded the video"
            }
            n <= 2 && playerMode == SourceMode.LIVE_RELAY && relay != null -> recoverKeepingBuffer(item, problem)
            n <= 2 -> {
                startPlayback(index, quiet = true, keepTweaks = true)
                "reconnected"
            }
            n == 3 -> {
                tweaks = tweaks.copy(step = tweaks.step + 1, noTunnel = true, noOffload = true)
                if (playerMode == SourceMode.LIVE_RELAY && relay != null) {
                    if (!videoArriving()) relay?.reconnect()
                    restartOnStoredVideo(item, newPlayer = true)
                    "restarted the player with safer settings (stored video kept)"
                } else {
                    player?.release(); player = null; playerMode = null
                    startPlayback(index, quiet = true, keepTweaks = true)
                    "restarted the player with safer settings"
                }
            }
            // Video still coming in: never give up on it — refill instead.
            playerMode == SourceMode.LIVE_RELAY && videoArriving() && s.weakRefillSecs > 0 -> {
                startRefill(item)
                "paused to refill (weak signal)"
            }
            n == 4 && s.watchdogOtherAccount && s.accounts.size > 1 -> {
                lifecycleScope.launch {
                    val other = runCatching { LiveCatalog.sameChannelElsewhere(app, item) }.getOrNull()
                    if (other != null && current?.saved?.key == item.saved.key) {
                        PlaybackQueue.items = PlaybackQueue.items.toMutableList().also { it[index] = other }
                        val acc = s.accounts.firstOrNull { it.id == other.saved.accountId }?.name ?: "another account"
                        logHealth(item, "keeps failing", "switched to “${other.title}” on $acc")
                        Toast.makeText(this@PlayerActivity, "Channel unstable — switched to $acc", Toast.LENGTH_LONG).show()
                        startPlayback(index, quiet = true, keepTweaks = false)
                    } else {
                        unstable(item)
                    }
                }
                "looking for the same channel on your other account"
            }
            else -> {
                unstable(item)
                "gave up (channel unstable)"
            }
        }
        logHealth(item, problem, action)
        if (s.watchdogNotes && n <= 3) {
            Toast.makeText(this, "⟳ ${action.replaceFirstChar { it.uppercase() }} ($problem)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unstable(item: PlayItem) {
        player?.stop()
        retryCount = 1 // OK retries
        showStatus("“${item.title}” is unstable right now.\nPress OK to try again, or change channel.")
    }

    private fun logHealth(item: PlayItem, problem: String, action: String) {
        val time = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        Log.i("NovaTest", "WATCHDOG ${item.title}: $problem -> $action")
        if (current === item) session?.action("$problem → $action")
        app.storage.addHealthEvent("$time|${item.title}|$problem|$action")
    }

    /** Wi-Fi / network or VPN dropped and came back: reconnect the live channel right away. */
    private fun watchNetwork() {
        val cm = (getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) {
                networkLostAt = android.os.SystemClock.elapsedRealtime()
            }

            override fun onAvailable(network: android.net.Network) {
                if (networkLostAt == 0L) return
                networkLostAt = 0L
                handler.post {
                    val item = current ?: return@post
                    if (!item.isLive || !app.storage.watchdog) return@post
                    logHealth(item, "network came back", "reconnected")
                    startPlayback(PlaybackQueue.index, quiet = true, keepTweaks = true)
                }
            }
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(cb)
            else cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb,
            )
        }.onSuccess { networkCallback = cb }
    }

    /**
     * 4K and other big films: the chosen size (e.g. 100 MB) holds only a few seconds of them, so allow enough for
     * about two minutes ahead (plus the 20 % kept behind), at most 2 GB, and only while 1 GB stays free on the drive.
     */
    private fun growReadAhead(ra: ReadAheadBuffer, total: Long, durMs: Long) {
        val bytesPerSec = total * 1000.0 / durMs
        val want = (bytesPerSec * 120 / 0.8).toLong()
        if (want <= ra.limitBytes) return
        val dir = java.io.File(Folders.bufferDir(this, Folders.BUFFER_MOVIES, app.storage.usePublicFolder), "readahead")
        val free = runCatching { android.os.StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
        val allowed = minOf(want, 2048L * 1024 * 1024, ra.limitBytes + free - 1024L * 1024 * 1024)
        if (allowed > ra.limitBytes + 8L * 1024 * 1024) {
            ra.growTo(allowed)
            Log.i("NovaTest", "READAHEAD grown to ${allowed / (1024 * 1024)} MB (${(bytesPerSec * 8 / 1_000_000).toInt()} Mbit/s film)")
        }
    }

    /** "⬇ Buffered ahead: 2:35" (read-ahead) or "⬇ Downloaded 45 %" (download & watch), and the seek limit. */
    private fun updateDiskStatus() {
        val item = current
        val p = player
        if (item == null || p == null || item.isLive) {
            vod.diskStatus = null; vod.seekLimitMs = null; vod.diskFraction = null
            return
        }
        val dur = p.duration
        val ra = readAhead
        val ha = hlsAhead
        val id = item.downloadId
        when {
            ha != null -> {
                val pl = (p.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest)?.mediaPlaylist
                ha.update(pl, p.currentPosition, p.bufferedPosition)
                if (dur > 0 && ha.bytesPerSec > 0) {
                    val want = (ha.bytesPerSec * 120 / 0.8).toLong()
                    if (want > ha.limitBytes) {
                        val dir = Folders.bufferDir(this, "Buffer/readahead-hls", app.storage.usePublicFolder)
                        val free = runCatching { android.os.StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
                        val allowed = minOf(want, 2048L * 1024 * 1024, ha.limitBytes + free - 1024L * 1024 * 1024)
                        if (allowed > ha.limitBytes + 8L * 1024 * 1024) ha.growTo(allowed)
                    }
                }
                val memoryMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
                val mb = ha.aheadBytes / (1024 * 1024)
                vod.diskStatus = when {
                    pl == null || !pl.hasEndTag -> "⬇ Buffered ahead: " + formatDuration(memoryMs) + "  (memory only)"
                    !ha.started && ha.aheadBytes == 0L -> "⬇ Filling the read-ahead…  (memory " + formatDuration(memoryMs) + ")"
                    else -> "⬇ Buffered ahead: " + formatDuration(memoryMs + ha.aheadMs) + "  (" + mb + " MB on disk" +
                        (if (ha.expiring) " · site limits reading ahead" else "") + ")"
                }
                vod.diskFraction = if (dur > 0) ((p.bufferedPosition + ha.aheadMs).toDouble() / dur).toFloat().coerceIn(0f, 1f) else null
                vod.seekLimitMs = null
            }
            ra != null -> {
                val total = ra.totalBytes
                if (total in 1 until 2L * 1024 * 1024 && dur > 600_000) {
                    // A tiny "file" for a long film is a playlist, not the film: only the memory buffer protects it.
                    val memoryMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
                    vod.diskStatus = "⬇ Buffered ahead: " + formatDuration(memoryMs) + "  (memory only)"
                    vod.diskFraction = null
                } else if (total > 0 && dur > 0) {
                    growReadAhead(ra, total, dur)
                    // Ahead = what the player already holds in memory + what is on disk after the point it reads from.
                    val readByte = ra.readPos
                    val onDisk = ra.contiguousFrom(readByte)
                    val memoryMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
                    val aheadMs = memoryMs + (onDisk.toDouble() / total * dur).toLong()
                    vod.diskStatus = "⬇ Buffered ahead: " + formatDuration(aheadMs) + "  (" + (onDisk / (1024 * 1024)) + " MB on disk)"
                    vod.diskFraction = ((readByte + onDisk).toDouble() / total).toFloat()
                } else {
                    vod.diskStatus = "⬇ Filling the read-ahead…"
                    vod.diskFraction = null
                }
                vod.seekLimitMs = null
            }
            id != null -> {
                val d = DownloadCenter.get(id)
                if (d == null) {
                    vod.diskStatus = null; vod.seekLimitMs = null; vod.diskFraction = null
                } else if (d.status == com.nova.iptv.data.DownloadStatus.DONE) {
                    vod.diskStatus = "✓ Downloaded — saved in Downloads"
                    vod.seekLimitMs = null
                    vod.diskFraction = 1f
                } else {
                    val frac = when {
                        d.totalBytes > 0 -> (d.doneBytes.toDouble() / d.totalBytes).coerceIn(0.0, 1.0)
                        d.parts > 0 -> (d.partsDone.toDouble() / d.parts).coerceIn(0.0, 1.0)
                        else -> 0.0
                    }
                    vod.diskFraction = frac.toFloat()
                    if (item.url.startsWith("nova-dl://")) {
                        // Streaming download: the bar is the saved part itself; jumping stops just before its end.
                        vod.diskFraction = 1f
                        vod.seekLimitMs = if (dur > 0) maxOf(0L, dur - 4_000) else null
                        val conn = if (d.connections > 1) " · ${d.connections} connections" else ""
                        val speed = if (d.speed > 0) " · %.1f MB/s".format(d.speed / 1048576.0) else ""
                        val state = when (d.status) {
                            com.nova.iptv.data.DownloadStatus.PAUSED -> "paused"
                            com.nova.iptv.data.DownloadStatus.QUEUED -> DownloadCenter.waitingNote(d)
                            com.nova.iptv.data.DownloadStatus.FAILED -> "failed"
                            else -> "downloading"
                        }
                        vod.diskStatus = "⬇ Downloaded ${(frac * 100).toInt()} % ($state)$conn$speed" +
                            (if (dur > 0) " · you can jump up to " + formatDuration(dur) else "")
                        return
                    }
                    // Pieces (several connections): jumping anywhere moves the download there.
                    val pieceMap = com.nova.iptv.download.PieceMap.of(d)
                    val inPieces = pieceMap != null
                    val endMissing = pieceMap != null && pieceMap.count > 1 && !pieceMap.done[pieceMap.count - 1]
                    vod.seekLimitMs = if (inPieces) null else if (dur > 0) maxOf(0L, (frac * dur).toLong() - 5_000) else null
                    val state = when {
                        endMissing && d.status == com.nova.iptv.data.DownloadStatus.DOWNLOADING -> "getting the film ready: its index at the end of the file comes first"
                        else -> when (d.status) {
                        com.nova.iptv.data.DownloadStatus.PAUSED -> "paused"
                        com.nova.iptv.data.DownloadStatus.QUEUED -> DownloadCenter.waitingNote(d)
                        com.nova.iptv.data.DownloadStatus.FAILED -> "failed"
                        else -> "downloading"
                    }
                    }
                    val conn = if (d.connections > 1) " · ${d.connections} connections" else ""
                    val speed = if (d.speed > 0) " · %.1f MB/s".format(d.speed / 1048576.0) else ""
                    vod.diskStatus = "⬇ Downloaded ${(frac * 100).toInt()} % ($state)$conn$speed" +
                        (if (dur > 0 && !inPieces) " · ready up to " + formatDuration((frac * dur).toLong()) else "")
                }
            }
            else -> { vod.diskStatus = null; vod.seekLimitMs = null; vod.diskFraction = null }
        }
    }

    private fun handleBack(item: PlayItem?) {
        when {
            item?.isLive == true && playerView.isControllerFullyVisible -> playerView.hideController()
            item != null && !item.isLive && vod.controlsVisible -> { vod.hide(); keySink.requestFocus() }
            item?.isLive == true && app.storage.doubleBackLive -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) finish()
                else {
                    lastBackAt = now
                    Toast.makeText(this, "Press Back again to return to the list", Toast.LENGTH_SHORT).show()
                }
            }
            item != null && !item.isLive && app.storage.doubleBackVod -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) finish()
                else {
                    lastBackAt = now
                    Toast.makeText(this, "Press Back again to stop the video", Toast.LENGTH_SHORT).show()
                }
            }
            else -> finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val item = current
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        // A key whose press Nova used: its release must not click the button under the focus.
        if (event.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) longPressHandled = false
        if (event.action == KeyEvent.ACTION_UP && swallowUp.remove(code)) return true

        // "≈ Similar" list: arrows / OK on its rows, Back closes it.
        if (similarOpen) {
            armSimilarClose(false) // you are choosing: it no longer closes by itself
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) { swallowUp += code; closeSimilar() }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // "Open in another app" offer: arrows / OK on its buttons, Back = Stay in Nova.
        if (otherAppOpen) {
            if (down && code != KeyEvent.KEYCODE_BACK) stopOtherAppCountdown()
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) {
                    swallowUp += code
                    val cur = current
                    closeOtherApp()
                    skipOtherApp = true
                    if (cur != null && cur.isLive) channelFailed("not working")
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Menus opened on purpose keep the arrows and OK for moving inside them.
        if (channelPanelOpen) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) { closeChannelPanelToInfo(); swallowUp += code }
                return true
            }
            // OK on a channel: short press plays it, holding OK adds it to (or removes it from) Favorites.
            val okKey = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (okKey && panelColumn == 1) {
                if (down) {
                    if (event.repeatCount == 0) { overlayOkDown = true; overlayOkLong = false }
                    else if (event.isLongPress) overlayOkLong = true
                    return true
                }
                if (!overlayOkDown) return true
                overlayOkDown = false
                if (overlayOkLong || event.eventTime - event.downTime >= 550) {
                    overlayChannels.getOrNull(channelCursor)?.takeIf { !it.temporary }?.let { ch ->
                        val added = app.storage.toggleFavorite(ch.saved)
                        Log.i("NovaTest", "FAVORITE ${if (added) "added" else "removed"}: ${ch.title}")
                        Toast.makeText(this, if (added) "★ Added to Favorites: ${ch.title}" else "Removed from Favorites: ${ch.title}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    pickOverlayChannel(channelCursor)
                }
                return true
            }
            if (down) {
                if (overlayKey(code)) { swallowUp += code; return true }
            } else if (swallowUp.remove(code)) return true
            return super.dispatchKeyEvent(event)
        }
        if (recentBarOpen) {
            val toggle = item != null && RemoteKeys.actionFor(app.storage, RemoteKeys.Area.LIVE, code) == "live_recent"
            if (code == KeyEvent.KEYCODE_BACK || toggle) {
                if (down) { hideRecentBar(); swallowUp += code }
                return true
            }
            bumpRecentBar()
            return super.dispatchKeyEvent(event)
        }
        if (::vod.isInitialized && vod.isPanelOpen) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) { vod.hidePanel(); keySink.requestFocus(); swallowUp += code }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (::vod.isInitialized && vod.enabled) vod.keepAlive()

        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        // "Next episode" card: OK plays it now, Back cancels it.
        if (nextCardShown && down && event.repeatCount == 0 && (isOk || code == KeyEvent.KEYCODE_BACK)) {
            swallowUp += code
            if (isOk) playNextEpisodeNow()
            else {
                nextCancelled = true
                hideNextCard()
                Log.i("NovaTest", "NEXT episode cancelled")
            }
            return true
        }
        // Long-press OK: record (live TV) or download (movie / episode).
        if (isOk && item != null && down && event.repeatCount > 0) {
            if (!longPressHandled && event.isLongPress) {
                longPressHandled = true
                swallowUp += code
                okSinglePending?.let { handler.removeCallbacks(it) }; okSinglePending = null
                when {
                    item.isLive -> toggleRecording()
                    !item.isLocal && item.downloadExt != null -> downloadCurrent()
                }
            }
            if (longPressHandled) return true
        }
        if (event.action == KeyEvent.ACTION_UP && isOk) longPressHandled = false

        // Error message on screen: OK opens the external player / retries.
        if (down && isOk && refilling) {
            finishRefill("OK pressed: playing now")
            swallowUp += code
            return true
        }
        if (down && isOk && statusView.visibility == View.VISIBLE) {
            if (externalSuggested) { openExternal(); swallowUp += code; return true }
            if (retryCount > 0) { autoSkips = 0; startPlayback(PlaybackQueue.index); swallowUp += code; return true }
        }

        if (code == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) handleBack(item)
            return true
        }

        // ⬛ Hide picture: ☰ Menu only (press again to show). OK: one press = channel list (live) / player buttons (films),
        // OK twice = info & buttons, hold = Rec / Download (above).
        val hideKey = item != null && RemoteKeys.actionFor(app.storage, RemoteKeys.Area.PLAYER, code) == "play_hide"
        if (pictureHidden && item != null) {
            if (hideKey) {
                if (down && event.repeatCount == 0) showPicture()
                if (down) swallowUp += code
                return true
            }
            if (isOk) { if (down) swallowUp += code; return true }
            if (!item.isLive && (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
                if (down && event.repeatCount == 0) vod.seekBy(30_000)
                if (down) swallowUp += code
                return true
            }
            // Other buttons work as usual (channel ▲▼, volume…) but never open the menus over the cover.
            val a = RemoteKeys.actionFor(app.storage, if (item.isLive) RemoteKeys.Area.LIVE else RemoteKeys.Area.VOD, code)
            if (a == "live_info" || a == "vod_controls" || a == "live_list" || a == "live_recent" || a == "vod_back" || a == "vod_forward") {
                if (down) swallowUp += code
                return true
            }
        } else if (hideKey && item != null) {
            if (down && event.repeatCount == 0) {
                if (item.isLive) playerView.hideController() else vod.hide()
                hidePicture()
            }
            if (down) swallowUp += code
            return true
        } else if (isOk && item != null && RemoteKeys.actionFor(app.storage, if (item.isLive) RemoteKeys.Area.LIVE else RemoteKeys.Area.VOD, code) == null) {
            val controlsShown = if (item.isLive) playerView.isControllerFullyVisible else vod.controlsVisible
            val menusOpen = vod.isPanelOpen || channelPanelOpen || recentBarOpen
            if (!controlsShown && !menusOpen) {
                if (down && event.repeatCount == 0) {
                    val waiting = okSinglePending
                    if (waiting != null) {
                        // OK twice: the info & buttons.
                        handler.removeCallbacks(waiting); okSinglePending = null
                        swallowUp += code
                        showControlsNow(item)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    okSinglePending = Runnable {
                        okSinglePending = null
                        if (pictureHidden) return@Runnable
                        if (item.isLive && PlaybackQueue.items.size > 1) showChannelPanel() else showControlsNow(item)
                    }.also { handler.postDelayed(it, 350) }
                }
                return true
            }
            if (item.isLive && controlsShown && !infoBar.hasFocus()) {
                // Info on screen (not in the buttons): OK hides it, as before.
                if (down && event.repeatCount == 0) playerView.hideController()
                if (down) swallowUp += code
                return true
            }
            // Buttons on screen: OK presses the highlighted one.
            return super.dispatchKeyEvent(event)
        }

        if (item != null && !RemoteKeys.isReserved(code)) {
            // Number buttons: type a channel number (live TV).
            if (item.isLive && app.storage.numberKeys && RemoteKeys.isDigit(code) && PlaybackQueue.items.size > 1) {
                if (down && event.repeatCount == 0) typeDigit(RemoteKeys.digitOf(code))
                if (down) swallowUp += code
                return true
            }
            val area = if (item.isLive) RemoteKeys.Area.LIVE else RemoteKeys.Area.VOD
            val action = RemoteKeys.actionFor(app.storage, area, code) ?: RemoteKeys.actionFor(app.storage, RemoteKeys.Area.PLAYER, code)
            if (action != null) {
                val controlsShown = if (item.isLive) playerView.isControllerFullyVisible else vod.controlsVisible
                val isShowAction = action == "live_info" || action == "vod_controls"
                val isLeftRight = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                val inButtonRow = if (item.isLive) infoBar.hasFocus() else vod.buttonRowFocused
                // With the menu on screen every button acts straight away, except:
                //  - OK presses the highlighted button, and "show menu" keys move around it;
                //  - Left / Right move between buttons once you are in a row of buttons.
                val menuFocused = currentFocus != null && currentFocus !== keySink
                val letMenuHaveIt = menuFocused && if (item.isLive) {
                    // Live TV: the button row is only used once you step into it (Right, or the mouse).
                    controlsShown && inButtonRow && (isOk || isShowAction || isLeftRight)
                } else {
                    controlsShown && (isOk || isShowAction || (isLeftRight && inButtonRow))
                }
                if (item.isLive && controlsShown && !inButtonRow && action == "live_info") {
                    // Info already showing: Right steps into the buttons, OK / Menu hides the info.
                    if (down && event.repeatCount == 0) {
                        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) focusFirstPill() else playerView.hideController()
                    }
                    if (down) swallowUp += code
                    return true
                }
                if (!letMenuHaveIt) {
                    if (down) {
                        val repeatable = action in setOf("vod_back", "vod_forward", "vod_back_long", "vod_forward_long")
                        if (event.repeatCount == 0 || repeatable) {
                            if (!runAction(action)) return super.dispatchKeyEvent(event)
                        }
                        swallowUp += code
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ================================================================== lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        val p = player ?: return
        // With the memory buffer, live TV jumps back to live after a pause.
        // With the disk buffer it continues where you paused (timeshift).
        if (playerMode == SourceMode.LIVE_DIRECT || playerMode == SourceMode.LIVE_HLS_DISK) {
            p.seekToDefaultPosition()
            p.prepare()
        }
        p.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        saveResumePosition()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        orientationListener?.disable()
        if (castFrom != null && com.nova.iptv.data.Cast.generation.value == castGen) com.nova.iptv.data.Cast.restoreQueue()
        StreamCheck.playing(null)
        StreamCheck.playerClosed()
        endSession()
        handler.removeCallbacksAndMessages(null)
        epgJob?.cancel()
        if (Recorder.isRecording) Recorder.stop()
        player?.release()
        player = null
        relay?.close()
        relay = null
        readAhead?.close()
        readAhead = null
        hlsAhead?.close()
        hlsAhead = null
        GainProcessor.gain = 1f
        GainProcessor.night = false
        PictureBrightness.gain = 1f
        networkCallback?.let { cb -> runCatching { (getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.unregisterNetworkCallback(cb) } }
        super.onDestroy()
    }
}
