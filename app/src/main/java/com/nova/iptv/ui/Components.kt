package com.nova.iptv.ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.collectIsHoveredAsState

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nova.iptv.data.Category

/** Clear green used for the active / selected item. */
val Accent = Color(0xFF2EE66B)

/** Light grey background of the active (selected) group, channel or film. */
val SelectedGrey = Color(0xFF59606A)

/** Background of the item under the remote's focus. */
val FocusGrey = Color(0xFF3D434B)

private val NovaColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00210B),
    background = Color(0xFF0B0E12),
    onBackground = Color.White,
    surface = Color(0xFF15191F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF262B33),
    onSurfaceVariant = Color(0xFFC9D1D9),
    error = Color(0xFFFF7B72),
)

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NovaColors) {
        // White text by default everywhere, on the dark background.
        androidx.compose.material3.Surface(color = NovaColors.background, contentColor = Color.White) {
            content()
        }
    }
}

/**
 * A clickable box that shows a clear highlight when focused with a TV remote,
 * and supports long-press on touch screens.
 */
/**
 * One highlight at a time: the air mouse's (while you use the mouse) or the remote's (once you press a button).
 * The remote always continues from where the remote was.
 */
object NovaInput {
    /** True while the mouse was used last (only its own highlight shows then). */
    var mouse by mutableStateOf(false)

    // Kept for the call sites; Nova no longer remembers where the mouse was.
    @Suppress("UNUSED_PARAMETER") fun hoverStarted(owner: Any, focus: () -> Unit) = Unit
    @Suppress("UNUSED_PARAMETER") fun hoverEnded(owner: Any) = Unit

    /** The mouse moved. */
    fun mouseUsed() { if (!mouse) mouse = true }

    /**
     * A remote button was pressed: the mouse highlight goes away and the remote continues from where the
     * remote was (never from the mouse's position). Returns false: the key is never swallowed.
     */
    fun remoteUsed(): Boolean {
        if (mouse) mouse = false
        return false
    }
}

/** How long OK must be held on the remote to count as a long press. */
const val LONG_OK_MS = 550L

/** Two clicks (or two OK presses) closer than this are a double click. */
const val DOUBLE_CLICK_MS = 350L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    selected: Boolean = false,
    scaleOnFocus: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    // Double click / double OK: the single click waits a moment to see whether a second one follows.
    val clickScope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingClick by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val latestClick by androidx.compose.runtime.rememberUpdatedState(onClick)
    val latestDouble by androidx.compose.runtime.rememberUpdatedState(onDoubleClick)
    val click: () -> Unit = if (onDoubleClick == null) onClick else ({
        val waiting = pendingClick
        if (waiting != null && waiting.isActive) {
            waiting.cancel()
            pendingClick = null
            latestDouble?.invoke()
        } else {
            pendingClick = clickScope.launch {
                kotlinx.coroutines.delay(DOUBLE_CLICK_MS)
                pendingClick = null
                latestClick()
            }
        }
    })
    var hasFocus by remember { mutableStateOf(false) }
    var okSeenDown by remember { mutableStateOf(false) }
    var okLong by remember { mutableStateOf(false) }
    // The air mouse pointer over an item highlights it like the remote's focus.
    val hoverSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val selfFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val owner = remember { Any() }
    androidx.compose.runtime.DisposableEffect(hovered) {
        if (hovered) {
            NovaInput.mouseUsed()
            NovaInput.hoverStarted(owner) { runCatching { selfFocus.requestFocus() } }
        }
        onDispose { NovaInput.hoverEnded(owner) }
    }
    // Only one highlight: the mouse's while the mouse is in use, otherwise the remote's.
    val focused = if (NovaInput.mouse) hovered else hasFocus
    val scale by animateFloatAsState(if (focused && scaleOnFocus) 1.06f else 1f, label = "focusScale")
    val colors = MaterialTheme.colorScheme
    val bg = when {
        selected -> SelectedGrey
        focused -> FocusGrey
        else -> Color.Transparent
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bg)
            .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .hoverable(hoverSource)
            .focusRequester(selfFocus)
            // Remote: holding OK (about half a second) is a long press, also on boxes whose remote
            // doesn't repeat the key; a short press is a normal click.
            .then(
                if (onLongClick == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                    val ne = e.nativeKeyEvent
                    val code = ne.keyCode
                    val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
                        code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (!ok) return@onPreviewKeyEvent false
                    when (e.type) {
                        androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                            if (ne.repeatCount == 0) { okSeenDown = true; okLong = false }
                            else if (ne.isLongPress) okLong = true
                            true
                        }
                        androidx.compose.ui.input.key.KeyEventType.KeyUp -> {
                            if (!okSeenDown) return@onPreviewKeyEvent false
                            okSeenDown = false
                            if (okLong || ne.eventTime - ne.downTime >= LONG_OK_MS) onLongClick() else click()
                            true
                        }
                        else -> false
                    }
                }
            )
            .combinedClickable(onClick = click, onLongClick = onLongClick)
    ) {
        content(focused)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false) {
    FocusBox(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), selected = selected) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = if (big) 20.dp else 14.dp, vertical = if (big) 10.dp else 8.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || big) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (big) 17.sp else 14.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun Logo(url: String?, name: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(2).uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun FavButton(isFavorite: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = CircleShape) {
        Icon(
            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            tint = if (isFavorite) Color(0xFFFF6B81) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(10.dp).size(22.dp),
        )
    }
}

@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    badge: String? = null,
    active: Boolean = false,
    showTitle: Boolean = true,
    favorite: Boolean = false,
    modifier: Modifier = Modifier,
    onDoubleClick: (() -> Unit)? = null,
) {
    FocusBox(modifier = modifier, onClick = onClick, onLongClick = onLongClick, onDoubleClick = onDoubleClick, scaleOnFocus = true, shape = RoundedCornerShape(12.dp), selected = active) { focused ->
        Column(Modifier.fillMaxWidth().padding(4.dp)) {
            Box {
                Logo(
                    image, title,
                    Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
                if (favorite) {
                    Text(
                        "★",
                        fontSize = 14.sp,
                        color = Accent,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (badge != null) {
                    Text(
                        badge,
                        fontSize = 11.sp,
                        color = Color.Black,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Accent, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (showTitle) Text(
                title,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                color = if (focused || active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun CategoryColumn(
    cats: ListState<Category>,
    selected: String?,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        when {
            cats.loading -> Loading()
            cats.error != null -> ErrorBox(cats.error, onRetry)
            else -> LazyColumn(contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(cats.items) { _, c ->
                    FocusBox(
                        onClick = { onSelect(c.id) },
                        selected = c.id == selected,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text(
                            c.name,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (c.id == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (c.id == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryChips(cats: ListState<Category>, selected: String?, onSelect: (String) -> Unit, onRetry: () -> Unit) {
    when {
        cats.loading -> Box(Modifier.fillMaxWidth().height(56.dp)) { Loading() }
        cats.error != null -> ErrorBox(cats.error, onRetry)
        else -> LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(cats.items) { _, c ->
                Chip(c.name, c.id == selected, { onSelect(c.id) })
            }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun EmptyText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun RowItem(
    title: String,
    subtitle: String?,
    image: String?,
    onClick: () -> Unit,
    leading: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    /** "compact", "normal" or "large" (Settings → Display → Live TV rows). */
    size: String = "normal",
    showImage: Boolean = true,
    /** Extra line under the subtitle, e.g. the programme on air now. */
    extra: String? = null,
) {
    val padV = when (size) { "compact" -> 3.dp; "large" -> 13.dp; else -> 8.dp }
    val logoW = when (size) { "compact" -> 44.dp; "large" -> 76.dp; else -> 56.dp }
    val logoH = when (size) { "compact" -> 28.dp; "large" -> 48.dp; else -> 36.dp }
    val titleSize = when (size) { "compact" -> 14.sp; "large" -> 18.sp; else -> 16.sp }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (size == "compact") 1.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FocusBox(
            onClick = onClick,
            onFocused = onFocused,
            onLongClick = onLongClick,
            modifier = Modifier.weight(1f),
            selected = active,
        ) { focused ->
            Row(Modifier.padding(horizontal = 10.dp, vertical = padV), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    Text(
                        leading,
                        modifier = Modifier.width(34.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                if (showImage) Logo(image, title, Modifier.size(width = logoW, height = logoH).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.padding(start = if (showImage) 10.dp else 0.dp).weight(1f)) {
                    Text(
                        title,
                        maxLines = 2,
                        fontSize = titleSize,
                        lineHeight = titleSize * 1.15f,
                        overflow = TextOverflow.Ellipsis,
                        color = if (focused || active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!extra.isNullOrBlank()) {
                        Text(
                            extra,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (trailing != null) trailing()
    }
}

/** Up / down / hide buttons shown in edit mode. */
@Composable
fun EditControls(
    hidden: Boolean,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onToggleHidden: (() -> Unit)?,
    onTop: (() -> Unit)? = null,
    onBottom: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onTop != null) SmallButton("⤒", onTop)
        if (onUp != null) RepeatButton("▲", onUp)
        if (onDown != null) RepeatButton("▼", onDown)
        if (onBottom != null) SmallButton("⤓", onBottom)
        if (onToggleHidden != null) SmallButton(if (hidden) "Show" else "Hide", onToggleHidden, warn = !hidden)
    }
}

/** Yellow outline of the group / item being moved (different from the green "open" highlight). */
val MovingYellow = Color(0xFFFFD233)

/**
 * A button that repeats while held: keep a finger on it, or hold OK on the remote,
 * and the item keeps moving.
 */
@Composable
fun RepeatButton(label: String, onStep: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val step by androidx.compose.runtime.rememberUpdatedState(onStep)
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(shape)
            .background(if (focused) FocusGrey else MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
                    code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!ok) return@onKeyEvent false
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    val r = e.nativeKeyEvent.repeatCount
                    if (r == 0 || r % 2 == 0) step()
                }
                true
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        step()
                        kotlinx.coroutines.delay(450)
                        while (true) {
                            step()
                            kotlinx.coroutines.delay(120)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                })
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SmallButton(label: String, onClick: () -> Unit, warn: Boolean = false) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(
            label,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 14.sp,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Small clock shown in a corner of the screen (Settings → Display → Clock). */
@Composable
fun ClockOverlay(position: String) {
    if (position != "tl" && position != "tr") return
    val context = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000L * (60 - (now / 1000) % 60))
        }
    }
    val text = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)) }
    val alignment = when (position) {
        "tl" -> Alignment.TopStart
        "bl" -> Alignment.BottomStart
        "br" -> Alignment.BottomEnd
        else -> Alignment.TopEnd
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = alignment) {
        Text(
            text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier
                .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}


/** The designer's signature, shown everywhere in the same words. */
const val SIGNATURE = "Designed by A.Hamrouni"

/**
 * Thin strip along the bottom of every page: the clock (when it is set to a bottom corner) and the
 * signature on the opposite side. Pages end above it, so neither ever covers a button or a list row.
 */
@Composable
fun FooterStrip(clockPosition: String, showClock: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000L * (60 - (now / 1000) % 60))
        }
    }
    val clockText = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)) }
    val clock: @Composable () -> Unit = {
        Text(
            clockText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
    val signature: @Composable () -> Unit = {
        Text(SIGNATURE, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
    }
    val clockLeft = showClock && clockPosition == "bl"
    val clockRight = showClock && clockPosition == "br"
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (clockLeft) clock()
        if (clockRight) signature()
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        if (clockRight) clock() else signature()
    }
}


/**
 * "Hide …?" after a double click on a poster: No (where the remote starts) / Yes, hide. No code asked.
 * Drawn over the posters in the same screen (not a separate window), so the remote always reaches it.
 */
@Composable
fun HideAskDialog(name: String, image: String?, onYes: () -> Unit, onDismiss: () -> Unit) {
    val no = remember { androidx.compose.ui.focus.FocusRequester() }
    var noFocused by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { onDismiss() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        android.util.Log.i("NovaTest", "HIDE ask $name")
        repeat(40) {
            kotlinx.coroutines.delay(50)
            if (noFocused) {
                android.util.Log.i("NovaTest", "HIDE ask: remote on No")
                return@LaunchedEffect
            }
            runCatching { no.requestFocus() }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(Unit) { androidx.compose.foundation.gestures.detectTapGestures { onDismiss() } }
            .focusProperties { exit = { androidx.compose.ui.focus.FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .widthIn(max = 540.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
                .pointerInput(Unit) { androidx.compose.foundation.gestures.detectTapGestures { } }
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(image, name, Modifier.width(54.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Hide “$name”?", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "It disappears from Films, Series, Search and Recent. You can show it again in Settings → Hidden items.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusBox(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(no).onFocusChanged { noFocused = it.isFocused },
                    shape = RoundedCornerShape(20.dp),
                ) { Text("No", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) }
                FocusBox(onClick = { onDismiss(); onYes() }, shape = RoundedCornerShape(20.dp)) {
                    Text("Yes, hide", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                }
            }
        }
    }
}
