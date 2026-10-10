package com.odiousapps.z2mdash.ui.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Width reserved for [TvListScrollbar] - lists using it pad their end edge by this much. */
val TvScrollbarWidth = 28.dp

/**
 * Where the thumb sits, as fractions of the track, plus the estimated full content height (px)
 * needed to turn a drag on the track back into a scroll distance.
 *
 * LazyColumn only knows the sizes of the items currently laid out, so the content height is an
 * estimate - the visible items' average size times the item count. Good enough for a position
 * indicator and for drag-to-scroll; exact page jumps don't depend on it (they scroll by the
 * viewport's own height).
 */
private data class ScrollMetrics(val thumbStart: Float, val thumbSize: Float, val contentPx: Float)

private fun scrollMetrics(info: LazyListLayoutInfo, atStart: Boolean, atEnd: Boolean): ScrollMetrics? {
    val visible = info.visibleItemsInfo
    if (visible.isEmpty() || (atStart && atEnd)) return null
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val averageItem = visible.sumOf { it.size }.toFloat() / visible.size
    val contentPx = (averageItem * info.totalItemsCount + info.beforeContentPadding + info.afterContentPadding)
        .coerceAtLeast(viewport + 1f)
    val thumbSize = (viewport / contentPx).coerceIn(0.05f, 1f)
    val first = visible.first()
    val scrolled = first.index * averageItem - first.offset
    // Snap to the true ends - the average-size estimate alone can leave the thumb a little short of
    // the top/bottom when the list is really there.
    val progress = when {
        atStart -> 0f
        atEnd -> 1f
        else -> (scrolled / (contentPx - viewport)).coerceIn(0f, 1f)
    }
    return ScrollMetrics(thumbStart = progress * (1f - thumbSize), thumbSize = thumbSize, contentPx = contentPx)
}

/**
 * A D-pad-operable scrollbar for a long [LazyListState]-backed list on TV, where moving focus
 * tile by tile is the only other way through - slow on a big dashboard.
 *
 * Reached with Right from the list's right-most items (it spans the full height, so it's always a
 * focus candidate there); while focused, each Up/Down press jumps a whole screen, and holding the
 * button keeps paging via the remote's own key repeat. At either end the key is left unconsumed,
 * so Down at the bottom still moves on to whatever sits below (the FABs). Left returns to the
 * list. Also responds to pointer input (tap above/below the thumb to page, drag the thumb), for
 * air-mouse style remotes.
 *
 * Draws nothing - and isn't focusable - while the whole list fits on screen.
 */
@Composable
fun TvListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val metrics by remember(state) {
        derivedStateOf { scrollMetrics(state.layoutInfo, !state.canScrollBackward, !state.canScrollForward) }
    }
    val current = metrics ?: return
    val latestMetrics by rememberUpdatedState(current)
    var focused by remember { mutableStateOf(false) }
    val viewportPx = { (state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset).toFloat() }
    // Most of a screen rather than all of it, so the item that was at the bottom stays in view at
    // the top after paging down - a little overlap keeps the user's place.
    val pagePx = { viewportPx() * 0.9f }

    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val idleThumb = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Box(
        modifier
            .tvFocusIndicator(RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionDown, Key.PageDown -> state.canScrollForward.also { if (it) state.dispatchRawDelta(pagePx()) }
                    Key.DirectionUp, Key.PageUp -> state.canScrollBackward.also { if (it) state.dispatchRawDelta(-pagePx()) }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(state) {
                detectTapGestures { tap ->
                    val m = latestMetrics
                    val thumbTop = m.thumbStart * size.height
                    val thumbBottom = thumbTop + m.thumbSize * size.height
                    when {
                        tap.y < thumbTop -> state.dispatchRawDelta(-pagePx())
                        tap.y > thumbBottom -> state.dispatchRawDelta(pagePx())
                    }
                }
            }
            .pointerInput(state) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    // One track-height of drag = the whole content, so the thumb follows the finger.
                    state.dispatchRawDelta(dragAmount * latestMetrics.contentPx / size.height)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
            val trackWidth = 6.dp.toPx()
            val thumbWidth = if (focused) 12.dp.toPx() else 8.dp.toPx()
            drawRoundRect(
                color = track,
                topLeft = Offset((size.width - trackWidth) / 2, 0f),
                size = Size(trackWidth, size.height),
                cornerRadius = CornerRadius(trackWidth / 2)
            )
            val thumbHeight = (current.thumbSize * size.height).coerceAtLeast(24.dp.toPx()).coerceAtMost(size.height)
            val thumbTop = current.thumbStart / (1f - current.thumbSize).coerceAtLeast(0.0001f) * (size.height - thumbHeight)
            drawRoundRect(
                color = if (focused) primary else idleThumb,
                topLeft = Offset((size.width - thumbWidth) / 2, thumbTop.coerceIn(0f, size.height - thumbHeight)),
                size = Size(thumbWidth, thumbHeight),
                cornerRadius = CornerRadius(thumbWidth / 2)
            )
        }
    }
}
