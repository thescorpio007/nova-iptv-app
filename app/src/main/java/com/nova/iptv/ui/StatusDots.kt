package com.nova.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.StreamCheck
import com.nova.iptv.data.StreamCheck.State

val DotGreen = Color(0xFF2EE66B)
val DotRed = Color(0xFFFF4D4D)
val DotOrange = Color(0xFFFFA31A)
val DotGrey = Color(0xFF8A9099)

fun dotColor(s: State): Color = when (s) {
    State.OK -> DotGreen
    State.BAD -> DotRed
    State.DEVICE -> DotOrange
    else -> DotGrey
}

/** Redraws the caller whenever a dot changes; returns false when the dots are switched off. */
@Composable
fun dotsOn(): Boolean {
    val v by StreamCheck.version.collectAsStateWithLifecycle()
    if (v < 0) return false
    return StreamCheck.enabled
}

/** Asks the checker to look at this list (only what is on screen is checked). */
@Composable
fun CheckList(key: Any, targets: () -> List<StreamCheck.Target>) {
    LaunchedEffect(key) { if (StreamCheck.enabled) StreamCheck.want(targets()) }
}

@Composable
fun StatusDot(status: StreamCheck.Status, size: Dp = 11.dp, modifier: Modifier = Modifier) {
    // Only items you opened have a dot (green / red / orange); nothing for the others.
    if (status.state == State.UNKNOWN || status.state == State.CHECKING || status.state == State.WAITING) return
    if (status.state == State.CHECKING) {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            strokeWidth = 2.dp,
            color = Color.White,
            trackColor = DotGrey,
        )
    } else {
        Box(
            modifier
                .size(size)
                .border(1.5.dp, Color(0x99000000), CircleShape)
                .background(dotColor(status.state), CircleShape),
        )
    }
}

/** Short reason shown next to a red / orange / waiting dot. */
fun dotNote(status: StreamCheck.Status, deviceWord: String = "this device"): String? = when (status.state) {
    State.BAD -> status.note
    State.DEVICE -> "$deviceWord can't play its format"
    State.WAITING -> status.note
    else -> null
}

@Composable
fun DotNoteText(status: StreamCheck.Status, modifier: Modifier = Modifier) {
    val note = dotNote(status) ?: return
    Text(
        note,
        fontSize = 11.sp,
        maxLines = 1,
        color = when (status.state) {
            State.BAD -> Color(0xFFFFB3B3)
            State.DEVICE -> Color(0xFFFFD08A)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier,
    )
}

/** Was the "⟳ Checking… 7 / 14" line: nothing is checked automatically any more, so it shows nothing. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun DotsHeader(keys: List<String>, onRetest: () -> Unit, modifier: Modifier = Modifier) {
}
