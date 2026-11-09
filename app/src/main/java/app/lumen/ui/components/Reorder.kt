// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Long-press-and-drag reordering for rows of a LazyColumn. Each row carries its own gesture (so the
 * list still scrolls normally until a row is held), the held row follows the finger, the others slide
 * out of its way, and the list scrolls by itself near the top and bottom edges.
 *
 * Works on item keys, not positions, so header items above the rows don't matter.
 */
@Stable
class ReorderState internal constructor(private val list: LazyListState) {
    /** Key of the row being dragged, or null. */
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    private var dragged by mutableFloatStateOf(0f)
    private var startOffset by mutableIntStateOf(0)

    /** Which keys may be swapped with (rows of songs, not headers). */
    internal var canMoveTo: (Any) -> Boolean = { false }
    /** Moves [from] to where [to] is; returns false when either is unknown. */
    internal var onMove: (from: Any, to: Any) -> Boolean = { _, _ -> false }
    internal var onEnd: () -> Unit = {}

    /** Edges where auto-scroll starts, in pixels from the visible top and bottom. */
    internal var topEdge = 0f
    internal var bottomEdge = 0f

    private fun info(key: Any?) = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** How far the dragged row is drawn from its slot. */
    val offset: Float get() = info(draggingKey)?.let { startOffset + dragged - it.offset } ?: 0f

    internal fun start(key: Any) {
        val item = info(key) ?: return
        draggingKey = key
        startOffset = item.offset
        dragged = 0f
    }

    internal fun drag(dy: Float) {
        dragged += dy
        swapIfNeeded()
    }

    private fun swapIfNeeded() {
        val key = draggingKey ?: return
        val item = info(key) ?: return
        val top = startOffset + dragged
        val middle = top + item.size / 2f
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != key && canMoveTo(it.key) && middle >= it.offset && middle <= it.offset + it.size
        } ?: return
        // Keep the list from jumping when the first visible row is part of the swap.
        if (item.index == list.firstVisibleItemIndex || target.index == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        onMove(key, target.key)
    }

    /** Pixels to scroll this frame to follow a row held near an edge (0 when not needed). */
    internal fun autoScrollSpeed(): Float {
        val item = info(draggingKey) ?: return 0f
        val top = startOffset + dragged
        val bottom = top + item.size
        val viewTop = topEdge
        val viewBottom = list.layoutInfo.viewportEndOffset - list.layoutInfo.afterContentPadding - bottomEdge
        return when {
            top < viewTop -> -((viewTop - top) / 6f).coerceIn(2f, 28f)
            bottom > viewBottom -> ((bottom - viewBottom) / 6f).coerceIn(2f, 28f)
            else -> 0f
        }
    }

    internal suspend fun autoScroll() {
        while (draggingKey != null) {
            withFrameNanos { }
            val speed = autoScrollSpeed()
            if (speed != 0f) {
                list.scrollBy(speed)
                swapIfNeeded()
            }
        }
    }

    internal fun end() {
        if (draggingKey == null) return
        draggingKey = null
        dragged = 0f
        onEnd()
    }
}

@Composable
fun rememberReorderState(list: LazyListState, topEdge: androidx.compose.ui.unit.Dp = 110.dp, bottomEdge: androidx.compose.ui.unit.Dp = 24.dp): ReorderState {
    val state = remember(list) { ReorderState(list) }
    val density = LocalDensity.current
    state.topEdge = with(density) { topEdge.toPx() }
    state.bottomEdge = with(density) { bottomEdge.toPx() }
    LaunchedEffect(state.draggingKey != null) { if (state.draggingKey != null) state.autoScroll() }
    return state
}

/** The gesture for one row: hold briefly, then drag up or down. */
@Composable
fun Modifier.reorderable(state: ReorderState, key: Any, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    // The row's own long-click already gives the haptic tick when the hold is recognised.
    return pointerInput(state, key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(key) },
            onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
        )
    }
}
