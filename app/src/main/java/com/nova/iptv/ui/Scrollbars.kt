package com.nova.iptv.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * A clear scroll bar on the right side of a list: shows where you are, can be dragged with the mouse
 * (or a finger), and a tap above / below the thumb jumps there. While scrolling, a bubble shows "120 / 860".
 */
private class BarInfo(
    /** 0 = top, 1 = bottom. */
    val position: Float,
    /** Part of the list on screen (0..1); 1 = everything fits, no bar. */
    val visible: Float,
    val label: String?,
)

private fun Modifier.scrollBar(info: () -> BarInfo, scrolling: () -> Boolean, scrollTo: (Float) -> Unit): Modifier = composed {
    var dragging by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val track = Color(0x55000000)
    val thumb = Color(0xFF9FC3FF)
    this
        .drawWithContent {
            drawContent()
            val i = info()
            if (i.visible >= 0.999f) return@drawWithContent
            val w = 7.dp.toPx()
            val pad = 4.dp.toPx()
            val x = size.width - w - 3.dp.toPx()
            val h = size.height - pad * 2
            drawRoundRect(track, Offset(x, pad), Size(w, h), CornerRadius(w / 2))
            val th = (h * i.visible).coerceAtLeast(28.dp.toPx())
            val ty = pad + (h - th) * i.position.coerceIn(0f, 1f)
            drawRoundRect(thumb, Offset(x, ty), Size(w, th), CornerRadius(w / 2))
            val label = i.label
            if (label != null && (dragging || scrolling())) {
                val text = measurer.measure(label, TextStyle(color = Color.White, fontSize = 12.sp))
                val bw = text.size.width + 16.dp.toPx()
                val bh = text.size.height + 8.dp.toPx()
                val by = (ty + th / 2 - bh / 2).coerceIn(0f, size.height - bh)
                val bx = x - bw - 6.dp.toPx()
                drawRoundRect(Color(0xCC000000), Offset(bx, by), Size(bw, bh), CornerRadius(8.dp.toPx()))
                drawText(text, topLeft = Offset(bx + 8.dp.toPx(), by + 4.dp.toPx()))
            }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Only the bar's own strip on the right; the rest of the list scrolls as usual.
                if (down.position.x < size.width - 26.dp.toPx() || info().visible >= 0.999f) return@awaitEachGesture
                down.consume()
                dragging = true
                scrollTo(down.position.y / size.height)
                drag(down.id) { change ->
                    change.consume()
                    scrollTo(change.position.y / size.height)
                }
                dragging = false
            }
        }
}

fun Modifier.scrollBar(state: LazyListState): Modifier = composed {
    val scope = rememberCoroutineScope()
    scrollBar(
        info = {
            val li = state.layoutInfo
            val total = li.totalItemsCount
            val vis = li.visibleItemsInfo.size
            if (total == 0 || vis >= total) BarInfo(0f, 1f, null)
            else BarInfo(
                state.firstVisibleItemIndex.toFloat() / (total - vis).coerceAtLeast(1),
                vis.toFloat() / total,
                "${(state.firstVisibleItemIndex + 1).coerceAtMost(total)} / $total",
            )
        },
        scrolling = { state.isScrollInProgress },
        scrollTo = { f ->
            val li = state.layoutInfo
            val target = (f.coerceIn(0f, 1f) * (li.totalItemsCount - li.visibleItemsInfo.size + 1)).toInt()
            scope.launch { state.scrollToItem(target.coerceIn(0, (li.totalItemsCount - 1).coerceAtLeast(0))) }
        },
    )
}

fun Modifier.scrollBar(state: LazyGridState): Modifier = composed {
    val scope = rememberCoroutineScope()
    scrollBar(
        info = {
            val li = state.layoutInfo
            val total = li.totalItemsCount
            val vis = li.visibleItemsInfo.size
            if (total == 0 || vis >= total) BarInfo(0f, 1f, null)
            else BarInfo(
                state.firstVisibleItemIndex.toFloat() / (total - vis).coerceAtLeast(1),
                vis.toFloat() / total,
                "${(state.firstVisibleItemIndex + 1).coerceAtMost(total)} / $total",
            )
        },
        scrolling = { state.isScrollInProgress },
        scrollTo = { f ->
            val li = state.layoutInfo
            val target = (f.coerceIn(0f, 1f) * (li.totalItemsCount - li.visibleItemsInfo.size + 1)).toInt()
            scope.launch { state.scrollToItem(target.coerceIn(0, (li.totalItemsCount - 1).coerceAtLeast(0))) }
        },
    )
}

fun Modifier.scrollBar(state: ScrollState): Modifier = composed {
    val scope = rememberCoroutineScope()
    scrollBar(
        info = {
            val max = state.maxValue
            val view = state.viewportSize
            if (max <= 0 || max == Int.MAX_VALUE || view <= 0) BarInfo(0f, 1f, null)
            else BarInfo(state.value.toFloat() / max, view.toFloat() / (max + view), null)
        },
        scrolling = { state.isScrollInProgress },
        scrollTo = { f -> scope.launch { state.scrollTo((f.coerceIn(0f, 1f) * state.maxValue).toInt()) } },
    )
}
