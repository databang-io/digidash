package io.databang.digidash.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.databang.digidash.domain.model.DashboardCardState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * Fixed-column grid of dashboard cards with reliable, iOS-18-style editing:
 * cards keep streaming live values, and in [editMode] a long-press-drag on a
 * card (via the drag handle) reorders it with proper hit-testing + reflow
 * animation (Calvin-LL Reorderable). Card VALUES stay live during a drag.
 *
 * @param columns fixed column count (spans resolve against this stable grid)
 * @param cell renders a card; receives the drag-handle [Modifier] to apply
 */
@Composable
fun ReorderableGaugeGrid(
    cards: List<DashboardCardState>,
    columns: Int,
    editMode: Boolean,
    onReorder: (List<String>) -> Unit,
    cell: @Composable (card: DashboardCardState, isDragging: Boolean, dragHandle: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    // Local key order for smooth dragging; resets when the SET of cards changes.
    val keySet = cards.map { it.key }.toSet()
    var order by remember(keySet) { mutableStateOf(cards.map { it.key }) }
    val byKey = cards.associateBy { it.key }
    val ordered = order.mapNotNull { byKey[it] }

    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
        onReorder(order)
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns.coerceAtLeast(1)),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            ordered,
            key = { _, c -> c.key },
            span = { _, c -> GridItemSpan(minOf(c.size.cols, maxLineSpan)) },
        ) { _, card ->
            ReorderableItem(reorderState, key = card.key) { isDragging ->
                val handle = if (editMode) Modifier.longPressDraggableHandle() else Modifier
                cell(card, isDragging, handle)
            }
        }
    }
}
