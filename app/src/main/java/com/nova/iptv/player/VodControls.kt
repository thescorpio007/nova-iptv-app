package com.nova.iptv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.nova.iptv.data.Storage
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GREEN = 0xFF2EE66B.toInt()
private const val GREY = 0xFF59606A.toInt()

/** A vertical level bar (brightness / volume) that works with touch and the remote's Up/Down keys. */
@SuppressLint("ViewConstructor")
class VerticalLevel(context: Context, private val onChange: (Float) -> Unit) : View(context) {
    var value = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN; style = Paint.Style.STROKE; strokeWidth = 2 * density
    }

    init {
        isFocusable = true
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = 8 * density
        val left = (width - w) / 2
        val r = w / 2
        canvas.drawRoundRect(RectF(left, 0f, left + w, height.toFloat()), r, r, track)
        val top = height * (1 - value)
        canvas.drawRoundRect(RectF(left, top, left + w, height.toFloat()), r, r, fill)
        if (isFocused) canvas.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f), 10 * density, 10 * density, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = 1f - event.y / height
                onChange(value)
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = 0.07f
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { value += step; onChange(value); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { value -= step; onChange(value); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }
}

/**
 * Player controls for films, series, catch-up and downloads: back, title, lock and settings
 * at the top; brightness and volume at the sides; −10 s / play / +10 s; a progress bar; and
 * aspect ratio, speed and more at the bottom. The settings panel lists video, audio and
 * subtitle tracks and the subtitle style.
 */
@OptIn(UnstableApi::class)
class VodControls(
    private val activity: ComponentActivity,
    private val playerView: PlayerView,
    private val storage: Storage,
    private val player: () -> ExoPlayer?,
    private val onBack: () -> Unit,
    private val onAspect: () -> String,
    private val onLoadSubtitle: () -> Unit,
    /** ☀ bar moved: 0 darker … 0.5 normal … 1 brighter (done on the picture, also on a TV box). */
    private val onBrightness: (Float) -> Unit,
    private val extraButtons: List<Pair<String, () -> Unit>>,
    /** Sound settings shown in the ⚙ panel (Night mode / Boost live in the player screen). */
    private val sound: SoundHooks? = null,
) {
    /** What the ⚙ panel needs from the player for Night mode and Boost. */
    interface SoundHooks {
        fun nightOn(): Boolean
        fun setNightOn(on: Boolean)
        /** Night mode settings changed in the panel: apply them now. */
        fun nightChanged()
        fun boost(): Int
        fun setBoost(percent: Int)
    }

    private val ctx: Context = activity
    /** Phone: smaller buttons with short labels so the whole row fits (see [PlayerUi]). */
    private val small = PlayerUi.compact(activity)
    /** The extra buttons by their first label (to change a label later, e.g. "🌙 Night mode: On"). Declared before the views are built. */
    val extraViews = HashMap<String, TextView>()
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** Touch layer + controls; add to the player screen above the video. */
    val view = FrameLayout(ctx)
    /** Settings panel (tracks / subtitles) at the right; also usable for live TV. */
    val panel = FrameLayout(ctx)

    private val controls = FrameLayout(ctx)
    private val unlockButton: TextView
    private lateinit var titleView: TextView
    private lateinit var badgeRow: VideoBadgeRow

    /** Shows the real quality of the video under the title. */
    fun setBadge(b: VideoBadge.Badge?) { if (::badgeRow.isInitialized) badgeRow.show(b) }
    private lateinit var playButton: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private lateinit var speedButton: TextView
    private lateinit var aspectButton: TextView
    private lateinit var brightness: VerticalLevel
    private lateinit var volume: VerticalLevel
    private val panelContent = LinearLayout(ctx)

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2
    private var locked = false
    private var lastInteraction = 0L
    private var userSeeking = false

    var enabled = false
        set(v) {
            field = v
            view.visibility = if (v) View.VISIBLE else View.GONE
            if (!v) hidePanel()
        }

    val isShowing: Boolean get() = enabled && controls.visibility == View.VISIBLE
    val isPanelOpen: Boolean get() = panel.visibility == View.VISIBLE

    init {
        view.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setUpGestures()

        controls.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        controls.setBackgroundColor(0x77000000)
        controls.addView(buildTopBar())
        controls.addView(buildSideLevels(left = true))
        controls.addView(buildSideLevels(left = false))
        controls.addView(buildCenter())
        controls.addView(buildBottom())
        view.addView(controls)

        unlockButton = circle("🔓", 22f) { setLocked(false) }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(24), 0) }
            visibility = View.GONE
        }
        view.addView(unlockButton)

        panel.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        panel.visibility = View.GONE
        panel.isClickable = true
        panel.setOnClickListener { hidePanel() }
        val sheet = ScrollView(ctx).apply {
            setBackgroundColor(0xEE111418.toInt())
            isClickable = true
            val w = (ctx.resources.displayMetrics.widthPixels * 0.42f).toInt().coerceAtLeast(dp(320))
            layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        }
        panelContent.orientation = LinearLayout.VERTICAL
        panelContent.setPadding(dp(20), dp(16), dp(20), dp(24))
        sheet.addView(panelContent)
        panel.addView(sheet)

        enabled = false
        applySubtitleStyle()
    }

    // ------------------------------------------------------------------ building blocks

    private fun pillBackground(round: Int) = StateListDrawable().apply {
        fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = dp(round).toFloat(); setColor(color); if (stroke != 0) setStroke(dp(2), stroke)
        }
        addState(intArrayOf(android.R.attr.state_focused), shape(GREY, GREEN))
        addState(intArrayOf(android.R.attr.state_pressed), shape(GREY, 0))
        addState(intArrayOf(), shape(0x33FFFFFF, 0))
    }

    private fun text(label: String, sizeSp: Float, bold: Boolean = false) = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = PillText(ctx, small).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 15f)
        background = pillBackground(20)
        if (small) setPadding(dp(9), dp(6), dp(9), dp(6)) else setPadding(dp(16), dp(9), dp(16), dp(9))
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick(); touched() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(if (small) 5 else 10) }
    }

    private fun circle(label: String, sizeSp: Float, onClick: () -> Unit) = text(label, sizeSp, bold = true).apply {
        gravity = Gravity.CENTER
        background = pillBackground(60)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick(); touched() }
    }

    private fun buildTopBar(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        row.addView(circle("←", 22f) { onBack() }.apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) })
        titleView = text("", 19f, bold = true).apply {
            maxLines = 1
        }
        // Title, and under it the real quality of the video ("4K · DOLBY VISION · HEVC · 3840×1600 · 24 fps").
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(titleView)
        badgeRow = VideoBadgeRow.make(ctx, ::dp)
        titleCol.addView(badgeRow.root)
        row.addView(titleCol)
        row.addView(circle("🔒", 20f) { setLocked(true) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) }
        })
        row.addView(circle("⚙", 22f) { openPanel() }.apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) })
        return row
    }

    private fun buildSideLevels(left: Boolean): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                dp(56), dp(230), (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL,
            ).apply { if (left) marginStart = dp(30) else marginEnd = dp(30) }
        }
        col.addView(text(if (left) "☀" else "🔊", 20f).apply { gravity = Gravity.CENTER })
        val level = VerticalLevel(ctx) { v -> if (left) setBrightness(v) else setVolume(v); touched() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), 0, 1f).apply { topMargin = dp(10) }
        }
        if (left) brightness = level else volume = level
        col.addView(level)
        return col
    }

    private fun buildCenter(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        row.addView(circle("−10s", 16f) { seekBy(-10_000) }.apply { layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)) })
        playButton = circle("❚❚", 26f) { togglePlay() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)).apply { setMargins(dp(48), 0, dp(48), 0) }
        }
        row.addView(playButton)
        row.addView(circle("+10s", 16f) { seekBy(10_000) }.apply { layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)) })
        return row
    }

    private var bottomCol: LinearLayout? = null

    /** Adds a line under the buttons (above the signature). */
    fun addBottomView(v: View) { bottomCol?.let { c -> c.addView(v, (c.childCount - 1).coerceAtLeast(0)) } }

    private fun buildBottom(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(14))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        val timeline = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        timeNow = text("0:00", 15f)
        timeTotal = text("0:00", 15f)
        seekBar = SeekBar(ctx).apply {
            max = 1000
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { setMargins(dp(12), 0, dp(12), 0) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val p = player() ?: return
                    val d = p.duration
                    if (d > 0) {
                        val target = limitSeek(d * progress / 1000)
                        timeNow.text = format(target)
                        if (!userSeeking) p.seekTo(target) // remote keys: seek right away
                    }
                    touched()
                }

                override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    userSeeking = false
                    val p = player() ?: return
                    if (p.duration > 0) p.seekTo(limitSeek(p.duration * sb.progress / 1000))
                }
            })
        }
        timeline.addView(timeNow)
        timeline.addView(seekBar)
        timeline.addView(timeTotal)
        col.addView(timeline)
        diskStatusView = text("", 13f).apply {
            setTextColor(0xFF2EE66B.toInt())
            setPadding(dp(4), 0, 0, dp(2))
            visibility = View.GONE
        }
        col.addView(diskStatusView)

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttonRow = buttons
        aspectButton = button("▭ Aspect: Fit") { aspectButton.text = "▭ Aspect: " + onAspect() }
        speedButton = button("◔ Speed (1x)") { cycleSpeed() }
        buttons.addView(aspectButton)
        buttons.addView(speedButton)
        buttons.addView(button("CC Audio & subtitles") { openPanel() })
        extraButtons.forEach { (label, action) -> buttons.addView(button(label, action).also { extraViews[label] = it }) }
        col.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(6), 0, 0)
            addView(buttons)
            // Option A: the row always fits the screen width (smaller text → short labels → icons).
            RowFit.attach(this, buttons, 15f, 12f)
        })
        bottomCol = col
        col.addView(text(com.nova.iptv.ui.SIGNATURE, 10f).apply {
            alpha = 0.55f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(4), 0, 0)
        })
        return col
    }

    // ------------------------------------------------------------------ gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpGestures() {
        var downX = 0f
        var downY = 0f
        var startLevel = 0f
        var adjusting = 0 // 0 none, 1 brightness, 2 volume
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; adjusting = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = downY - e.y
                    if (!locked && adjusting == 0 && abs(dy) > dp(24)) {
                        adjusting = if (downX < view.width / 2f) 1 else 2
                        startLevel = if (adjusting == 1) brightness.value else volume.value
                        show()
                    }
                    if (adjusting != 0) {
                        val v = (startLevel + dy / (view.height * 0.7f)).coerceIn(0f, 1f)
                        if (adjusting == 1) { brightness.value = v; setBrightness(v) } else { volume.value = v; setVolume(v) }
                        touched()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (adjusting == 0 && abs(e.x - downX) < dp(12) && abs(e.y - downY) < dp(12)) {
                        if (locked) showUnlock() else toggle()
                    }
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------ actions

    private fun setBrightness(v: Float) = onBrightness(v)

    private fun setVolume(v: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0)
    }

    private fun syncLevels() {
        brightness.value = (PictureBrightness.gain - 0.5f).coerceIn(0f, 1f)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volume.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    /** Line under the seek bar: "⬇ Buffered ahead: 2:35" / "⬇ Downloaded 45 %". Null hides it. */
    var diskStatus: String? = null

    /** Download & watch: how far you can jump (the saved part). Null = no limit. */
    var seekLimitMs: Long? = null

    /** Part of the film on disk (0..1), shown in the seek bar's lighter colour. */
    var diskFraction: Float? = null

    private var diskStatusView: TextView? = null

    private fun limitSeek(target: Long): Long {
        val limit = seekLimitMs ?: return target
        if (target > limit) {
            android.widget.Toast.makeText(ctx, "Not downloaded that far yet", android.widget.Toast.LENGTH_SHORT).show()
            return maxOf(limit, 0L)
        }
        return target
    }

    fun seekBy(ms: Long) {
        val p = player() ?: return
        var target = (p.currentPosition + ms).coerceAtLeast(0)
        if (p.duration > 0) target = target.coerceAtMost(p.duration)
        if (ms > 0) target = maxOf(limitSeek(target), minOf(p.currentPosition, target))
        p.seekTo(target)
        update()
    }

    fun togglePlay() {
        val p = player() ?: return
        p.playWhenReady = !p.playWhenReady
        update()
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        player()?.setPlaybackSpeed(speeds[speedIndex])
        val s = speeds[speedIndex]
        speedButton.text = "◔ Speed (" + (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "x)"
    }

    fun resetForNewItem(title: String, aspectLabel: String) {
        titleView.text = title
        aspectButton.text = "▭ Aspect: $aspectLabel"
        speedIndex = 2
        speedButton.text = "◔ Speed (1x)"
        setLocked(false)
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        controls.visibility = if (lock) View.GONE else controls.visibility
        unlockButton.visibility = View.GONE
        if (!lock) show()
    }

    private fun showUnlock() {
        unlockButton.visibility = View.VISIBLE
        unlockButton.postDelayed({ unlockButton.visibility = View.GONE }, 3000)
    }

    fun show() {
        if (!enabled || locked) return
        syncLevels()
        controls.visibility = View.VISIBLE
        update()
        touched()
    }

    fun hide() {
        if (pinned) return
        controls.visibility = View.GONE
    }

    fun toggle() = if (controls.visibility == View.VISIBLE && !pinned) hide() else show()

    /** Phone upright (YouTube style): controls stay on screen, under the video ([videoHeight] at the top). */
    var pinned = false
        private set
    private val landscapeParams = HashMap<View, ViewGroup.LayoutParams>()

    fun setPortrait(portrait: Boolean, videoHeight: Int) {
        val kids = (0 until controls.childCount).map { controls.getChildAt(it) }
        if (kids.size < 5) return
        if (landscapeParams.isEmpty()) kids.forEach { landscapeParams[it] = it.layoutParams }
        val sides = listOf(kids[1], kids[2])
        val center = kids[3]
        val bottom = kids[4]
        pinned = portrait
        if (portrait) {
            sides.forEach { it.visibility = View.GONE }
            center.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = (videoHeight / 2 - dp(46)).coerceAtLeast(0) }
            bottom.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                .apply { topMargin = videoHeight }
            if (enabled) show()
        } else {
            sides.forEach { it.visibility = View.VISIBLE }
            landscapeParams[center]?.let { center.layoutParams = it }
            landscapeParams[bottom]?.let { bottom.layoutParams = it }
        }
    }

    /** Shows the controls and puts the remote's focus on Play/Pause. */
    fun showAndFocus() {
        show()
        playButton.requestFocus()
    }

    private fun touched() {
        lastInteraction = System.currentTimeMillis()
    }

    private var buttonRow: LinearLayout? = null

    /** The controls are on screen. */
    val controlsVisible: Boolean get() = enabled && controls.visibility == View.VISIBLE

    /** The remote's focus is in the bottom row of buttons (Left / Right then move between them). */
    val buttonRowFocused: Boolean get() = controlsVisible && buttonRow?.hasFocus() == true

    /** A remote button was pressed: keep the controls up a little longer. */
    fun keepAlive() = touched()

    /** Updates the Aspect button label after a remote shortcut changed it. */
    fun setAspectLabel(label: String) {
        aspectButton.text = "▭ Aspect: $label"
    }

    /** Called about every second: progress, times, play icon, auto-hide. */
    fun update() {
        if (!enabled) return
        val p = player() ?: return
        playButton.text = if (p.playWhenReady) "❚❚" else "▶"
        val d = p.duration
        val pos = p.currentPosition
        if (!userSeeking && !seekBar.isFocused) seekBar.progress = if (d > 0) (pos * 1000 / d).toInt() else 0
        if (d > 0) {
            val buffered = (p.bufferedPosition * 1000 / d).toInt()
            val onDisk = diskFraction?.let { (it * 1000).toInt() } ?: 0
            seekBar.secondaryProgress = maxOf(buffered, onDisk)
        }
        diskStatusView?.let { v ->
            val t = diskStatus
            v.text = t.orEmpty()
            v.visibility = if (t.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        if (!userSeeking) timeNow.text = format(pos)
        timeTotal.text = if (d > 0) format(d) else "--:--"
        seekBar.keyProgressIncrement = if (d > 0) (10_000L * 1000 / d).toInt().coerceAtLeast(1) else 10
        if (controls.visibility == View.VISIBLE && !isPanelOpen && p.isPlaying && !pinned &&
            System.currentTimeMillis() - lastInteraction > 5000
        ) {
            hide()
        }
    }

    /** Remote keys for films/series. Returns true when used. */
    fun handleKey(event: KeyEvent): Boolean {
        if (!enabled || event.action != KeyEvent.ACTION_DOWN) return false
        touched()
        if (isPanelOpen) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { hidePanel(); return true }
            return false
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlay(); show(); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); show(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); show(); return true }
        }
        if (controls.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { showAndFocus(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); show(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000); show(); return true }
            }
            return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) { hide(); return true }
        return false
    }

    // ------------------------------------------------------------------ settings panel (tracks)

    fun openPanel() {
        rebuildPanel()
        panel.visibility = View.VISIBLE
        panelContent.post { panelContent.getChildAt(1)?.requestFocus() }
    }

    fun hidePanel() {
        panel.visibility = View.GONE
    }

    private fun rebuildPanel() {
        panelContent.removeAllViews()
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(circle("←", 20f) { hidePanel() }.apply { layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)) })
        header.addView(text("Settings", 22f, bold = true).apply { setPadding(dp(16), 0, 0, 0) })
        panelContent.addView(header)
        // Order: voice first, then subtitles, night mode, boost, hide picture, subtitle look, video quality.
        trackSection("♫  Audio (voice / language)", C.TRACK_TYPE_AUDIO, "No audio track found")
        trackSection("CC  Subtitles", C.TRACK_TYPE_TEXT, "No subtitle found for this video")
        panelContent.addView(option("＋ Load subtitle file (.srt, .vtt, .ass)", false) { hidePanel(); onLoadSubtitle() })
        soundSettings()
        hideSettings()
        subtitleSettings()
        trackSection("🎞  Video quality", C.TRACK_TYPE_VIDEO, "No video track")
    }

    private fun sectionTitle(t: String) {
        panelContent.addView(text(t, 18f, bold = true).apply { setPadding(0, dp(18), 0, dp(6)) })
    }

    private fun option(label: String, checked: Boolean, onClick: () -> Unit) = text((if (checked) "◉  " else "○  ") + label, 15f).apply {
        background = pillBackground(10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isFocusable = true
        isClickable = true
        if (checked) setTextColor(GREEN)
        setOnClickListener {
            val index = panelContent.indexOfChild(this)
            onClick()
            rebuildPanel()
            // keep the remote's focus on the same line
            panelContent.getChildAt(index)?.requestFocus()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    private fun trackSection(title: String, type: Int, emptyText: String) {
        sectionTitle(title)
        val p = player() ?: return
        val disabled = p.trackSelectionParameters.disabledTrackTypes.contains(type)
        val groups = p.currentTracks.groups.filter { it.type == type }
        if (groups.isEmpty()) {
            panelContent.addView(text(emptyText, 14f).apply { setPadding(dp(12), dp(6), 0, dp(6)) })
            return
        }
        panelContent.addView(option("Disable", disabled) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
        })
        var n = 0
        groups.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val label = describe(n++, type, f) + if (!g.isTrackSupported(i)) "  (not supported)" else ""
                panelContent.addView(option(label, !disabled && g.isTrackSelected(i)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                })
            }
        }
    }

    private fun describe(index: Int, type: Int, f: Format): String {
        val codec = f.codecs?.substringBefore('.')?.takeIf { it.isNotBlank() }
            ?: f.sampleMimeType?.substringAfter('/')?.removePrefix("x-") ?: "?"
        val kbps = if (f.bitrate > 0) "${f.bitrate / 1000} kb/s" else null
        val lang = f.language?.takeIf { it != "und" }
        return when (type) {
            C.TRACK_TYPE_VIDEO -> listOfNotNull("$index, VIDEO", codec, kbps,
                if (f.width > 0) "${f.width} x ${f.height}" else null,
                if (f.frameRate > 0) "${f.frameRate.roundToInt()} fps" else null).joinToString(", ")
            C.TRACK_TYPE_AUDIO -> listOfNotNull("$index, AUDIO", codec, kbps,
                if (f.sampleRate > 0) "${f.sampleRate} Hz" else null,
                if (f.channelCount > 0) "${f.channelCount} ch" else null,
                f.label ?: lang).joinToString(", ")
            else -> listOfNotNull("$index", f.label ?: lang ?: "Subtitle", codec).joinToString(", ")
        }
    }

    private fun label(t: String) {
        panelContent.addView(text(t, 13f).apply { setTextColor(0xFF9FB3E0.toInt()); setPadding(0, dp(10), 0, 0) })
    }

    private fun soundSettings() {
        val s = sound ?: return
        sectionTitle("🌙  Night mode (sound)")
        val on = s.nightOn()
        panelContent.addView(option("On", on) { s.setNightOn(true) })
        panelContent.addView(option("Off", !on) { s.setNightOn(false) })
        label("Strength")
        listOf("light" to "Light", "medium" to "Medium", "strong" to "Strong").forEach { (v, l) ->
            panelContent.addView(option(l, storage.nightStrength == v) { storage.nightStrength = v; s.nightChanged() })
        }
        label("Maximum loudness")
        listOf("high" to "High", "medium" to "Medium", "low" to "Low").forEach { (v, l) ->
            panelContent.addView(option(l, storage.nightCeiling == v) { storage.nightCeiling = v; s.nightChanged() })
        }
        label("Dialogue clarity")
        panelContent.addView(option("On", storage.nightDialogue) { storage.nightDialogue = true; s.nightChanged() })
        panelContent.addView(option("Off", !storage.nightDialogue) { storage.nightDialogue = false; s.nightChanged() })
        label("Reaction speed")
        listOf("smooth" to "Smooth", "fast" to "Fast").forEach { (v, l) ->
            panelContent.addView(option(l, storage.nightSpeed == v) { storage.nightSpeed = v; s.nightChanged() })
        }
        label("Automatic hours · applies to: Settings › Players")

        sectionTitle("🔊  Volume boost")
        val level = s.boost()
        listOf(100, 125, 150, 175, 200).forEach { l ->
            panelContent.addView(option(if (l == 100) "100 % (off)" else "$l %", level == l) { s.setBoost(l) })
        }
        label("Keep it for this film / channel / everything: 🔊 Boost button")
    }

    private fun hideSettings() {
        sectionTitle("⬛  Hide picture (one OK)")
        listOf("skip" to "Skip ahead (black 2 s, then jump)", "wait" to "Wait (black until the time is over)", "stay" to "Until I press OK").forEach { (v, l) ->
            panelContent.addView(option(l, storage.hideMode == v) { storage.hideMode = v })
        }
        if (storage.hideMode != "stay") {
            label("Hide time")
            listOf(15 to "15 s", 30 to "30 s", 60 to "1 min").forEach { (v, l) ->
                panelContent.addView(option(l, storage.hideSeconds == v) { storage.hideSeconds = v })
            }
        }
    }

    private fun subtitleSettings() {
        sectionTitle("⚙  Subtitle look")
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text("Font size  ${storage.subtitleSize}", 15f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(button("−") { storage.subtitleSize = (storage.subtitleSize - 2).coerceAtLeast(10); applySubtitleStyle(); rebuildPanel() })
        row.addView(button("+") { storage.subtitleSize = (storage.subtitleSize + 2).coerceAtMost(48); applySubtitleStyle(); rebuildPanel() })
        panelContent.addView(row)
        panelContent.addView(text("Colour", 15f).apply { setPadding(0, dp(10), 0, 0) })
        listOf("white" to "White", "yellow" to "Yellow").forEach { (v, l) ->
            panelContent.addView(option(l, storage.subtitleColor == v) { storage.subtitleColor = v; applySubtitleStyle() })
        }
        panelContent.addView(text("Background", 15f).apply { setPadding(0, dp(10), 0, 0) })
        listOf(false to "None (outlined text)", true to "Dark box").forEach { (v, l) ->
            panelContent.addView(option(l, storage.subtitleBackground == v) { storage.subtitleBackground = v; applySubtitleStyle() })
        }
    }

    fun applySubtitleStyle() {
        val sv = playerView.subtitleView ?: return
        sv.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, storage.subtitleSize.toFloat())
        val fg = if (storage.subtitleColor == "yellow") 0xFFFFEB3B.toInt() else Color.WHITE
        val bg = if (storage.subtitleBackground) 0xCC000000.toInt() else Color.TRANSPARENT
        sv.setStyle(
            CaptionStyleCompat(
                fg, bg, Color.TRANSPARENT,
                if (storage.subtitleBackground) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK, null,
            )
        )
    }

    private fun format(ms: Long): String {
        val t = ms / 1000
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
