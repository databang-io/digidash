package io.databang.digidash.ui.dashboard

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.databang.digidash.domain.model.DashboardCardState

/**
 * A LazyVerticalGrid of dashboard cards that can be reordered by long-pressing a
 * card and dragging it (iPhone-style). Card VALUES stay live during the drag —
 * only the ORDER is held locally — and the new order is pushed via [onReorder]
 * when the finger lifts.
 *
 * @param cards current cards already in the persisted order, with fresh values
 * @param onReorder called with the new key order on drag end
 */
@Composable
fun ReorderableGaugeGrid(
    cards: List<DashboardCardState>,
    onReorder: (List<String>) -> Unit,
    cell: @Composable (DashboardCardState, isDragged: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onEnterEditMode: () -> Unit = {},
) {
    val gridState = rememberLazyGridState()

    // Local key order for smooth dragging; resets when the SET of cards changes.
    val keySet = cards.map { it.key }.toSet()
    var order by remember(keySet) { mutableStateOf(cards.map { it.key }) }

    // Resolve current order to live card values (drop keys that vanished).
    val byKey = cards.associateBy { it.key }
    val ordered = order.mapNotNull { byKey[it] }

    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // Distinguish a still long-press (→ enter edit mode) from a drag (→ reorder),
    // so entering edit mode never recomposes mid-drag and kills the gesture.
    var moved by remember { mutableStateOf(0f) }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = modifier
            .fillMaxSize()
            .pointerInput(ordered.map { it.key }) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        moved = 0f
                        val item = gridState.itemAt(pos)
                        draggingKey = item?.let { order.getOrNull(it) }
                        dragOffset = Offset.Zero
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val key = draggingKey ?: return@detectDragGesturesAfterLongPress
                        moved += amount.getDistance()
                        dragOffset += amount
                        val fromIndex = order.indexOf(key)
                        val pointer = change.position
                        val targetIndex = gridState.itemAt(pointer)
                        if (targetIndex != null && targetIndex != fromIndex &&
                            targetIndex in order.indices
                        ) {
                            order = order.toMutableList().apply {
                                add(targetIndex, removeAt(fromIndex))
                            }
                            // Keep the dragged card under the finger after the swap.
                            dragOffset = Offset.Zero
                        }
                    },
                    onDragEnd = {
                        // A still long-press (no real movement) enters edit mode;
                        // an actual drag commits the reorder.
                        if (moved > 24f) draggingKey?.let { onReorder(order) }
                        else onEnterEditMode()
                        draggingKey = null
                        dragOffset = Offset.Zero
                        moved = 0f
                    },
                    onDragCancel = {
                        draggingKey = null
                        dragOffset = Offset.Zero
                        moved = 0f
                    },
                )
            },
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            ordered,
            key = { _, c -> c.key },
            span = { _, card -> GridItemSpan(minOf(card.size.cols, maxLineSpan)) },
        ) { _, card ->
            val isDragged = card.key == draggingKey
            cell(
                card,
                isDragged,
            )
        }
    }
}

/** Index of the grid item under [position] (in the grid's own coordinates), or null. */
private fun LazyGridState.itemAt(position: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { info ->
        val x = position.x
        val y = position.y
        x >= info.offset.x && x <= info.offset.x + info.size.width &&
            y >= info.offset.y && y <= info.offset.y + info.size.height
    }?.index
