package io.databang.digidash.ui.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.databang.digidash.domain.model.CardSize
import io.databang.digidash.domain.model.DashboardCardState
import io.databang.digidash.domain.model.MeasurementStatus
import io.databang.digidash.ui.theme.StatusColors

/** Dial ranges for known dashboard roles so gauges have sensible sweeps. */
private data class GaugeRange(val min: Double, val max: Double)

private val gaugeRanges = mapOf(
    "rpm" to GaugeRange(0.0, 7000.0),
    "gps_speed" to GaugeRange(0.0, 160.0),
    "coolant_temp" to GaugeRange(-20.0, 130.0),
    "coolant_temp_000" to GaugeRange(-20.0, 130.0),
    "battery_voltage" to GaugeRange(8.0, 16.0),
    "intake_air_temp" to GaugeRange(-20.0, 90.0),
    "injection_time" to GaugeRange(0.0, 20.0),
    "engine_load" to GaugeRange(0.0, 100.0),
    "throttle_angle" to GaugeRange(0.0, 90.0),
    "lambda_signal" to GaugeRange(0.0, 1.3), // volts (wire formula 0x85/128)
    "ignition_advance" to GaugeRange(-10.0, 50.0),
    "rpm_000" to GaugeRange(0.0, 7000.0),
)

@Composable
fun DashboardScreen(
    cards: List<DashboardCardState>,
    connected: Boolean,
    columns: Int = 2,
    peaks: Map<String, io.databang.digidash.core.history.PeakHold> = emptyMap(),
    editMode: Boolean = false,
    selectedKey: String? = null,
    onReorder: (List<String>) -> Unit = {},
    onCardClick: (String) -> Unit = {},
    onSelect: (String?) -> Unit = {},
    onSetSize: (String, CardSize) -> Unit = { _, _ -> },
    onEnterEditMode: () -> Unit = {},
    onExitEditMode: () -> Unit = {},
    onResetLayout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty() || !connected) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (connected) "Waiting for data…"
                else "Not connected.\nConnect from the Home tab.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (editMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Editing — drag to move · tap to select & resize",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onResetLayout) { Text("Reset") }
                Spacer(Modifier.padding(horizontal = 2.dp))
                Button(onClick = onExitEditMode) { Text("Done") }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (editMode) GridGuides(columns)
            ReorderableGaugeGrid(
                cards = cards,
                columns = columns,
                editMode = editMode,
                onReorder = onReorder,
                cell = { card, isDragging, dragHandle ->
                    DashboardCard(
                        card = card,
                        peak = peaks[card.key],
                        isDragging = isDragging,
                        editMode = editMode,
                        selected = editMode && card.key == selectedKey,
                        dragHandle = dragHandle,
                        onTap = { if (editMode) onSelect(card.key) else onCardClick(card.key) },
                        onLongPress = onEnterEditMode,
                        onSetSize = { onSetSize(card.key, it) },
                    )
                },
            )
        }
    }
}

/** Faint vertical column guides behind the grid, for alignment while editing. */
@Composable
private fun GridGuides(columns: Int) {
    val color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    Canvas(Modifier.fillMaxSize()) {
        if (columns <= 1) return@Canvas
        val step = size.width / columns
        for (i in 1 until columns) {
            val x = step * i
            drawLine(color, androidx.compose.ui.geometry.Offset(x, 0f),
                androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2f)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardCard(
    card: DashboardCardState,
    peak: io.databang.digidash.core.history.PeakHold? = null,
    isDragging: Boolean = false,
    editMode: Boolean = false,
    selected: Boolean = false,
    dragHandle: Modifier = Modifier,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onSetSize: (CardSize) -> Unit = {},
) {
    val gaugeHeight = when (card.size) {
        CardSize.SMALL -> null
        CardSize.WIDE -> 120.dp
        CardSize.LARGE -> 190.dp
    }
    val statusColor = card.statusColor()
    val elevation = if (isDragging) 12.dp else 0.dp
    val scale = if (isDragging) 1.04f else 1f

    // Subtle jiggle while editing (iOS-style), still in run mode.
    val jiggle = if (editMode && !isDragging) {
        val t = rememberInfiniteTransition(label = "jiggle")
        t.animateFloat(
            initialValue = -0.7f, targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(170), RepeatMode.Reverse),
            label = "rot",
        ).value
    } else 0f

    val border = when {
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else -> null
    }

    Card(
        modifier = dragHandle
            .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = jiggle }
            .zIndex(if (isDragging) 1f else 0f)
            .combinedClickable(
                onClick = onTap,
                onLongClick = if (editMode) null else onLongPress,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            val range = gaugeRanges[card.key]
            val numeric = card.valueText.toDoubleOrNull()
            if (range != null) {
                CircularGauge(
                    label = card.title,
                    valueText = card.valueText,
                    unit = card.unit,
                    fraction = numeric?.let {
                        ((it - range.min) / (range.max - range.min)).toFloat()
                    },
                    color = statusColor,
                    gaugeHeight = gaugeHeight,
                )
            } else {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = card.valueText + if (card.unit.isNotBlank()) " ${card.unit}" else "",
                    style = MaterialTheme.typography.headlineMedium,
                    color = statusColor,
                )
            }
            // Size control on the selected card (replaces tap-to-cycle).
            if (selected) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardSize.entries.forEach { s ->
                        FilterChip(
                            selected = card.size == s,
                            onClick = { onSetSize(s) },
                            label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.statusLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
                if (card.stale) {
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(
                        text = "· STALE",
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusColors.warning,
                    )
                }
                if (peak != null && card.valueText.toDoubleOrNull() != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "↓${fmt(peak.min)} ↑${fmt(peak.max)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.lowConfidence) {
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Low confidence mapping",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(14.dp),
                    )
                }
            }
        }
    }
}

private fun fmt(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

private fun DashboardCardState.statusColor(): Color = when (status) {
    MeasurementStatus.NORMAL -> StatusColors.normal
    MeasurementStatus.WARNING -> StatusColors.warning
    MeasurementStatus.CRITICAL -> StatusColors.critical
    MeasurementStatus.UNAVAILABLE -> StatusColors.unavailable
    MeasurementStatus.UNKNOWN -> StatusColors.unavailable
}

private fun DashboardCardState.statusLabel(): String = when (status) {
    MeasurementStatus.NORMAL -> "Normal"
    MeasurementStatus.WARNING -> "Warning"
    MeasurementStatus.CRITICAL -> "CRITICAL"
    MeasurementStatus.UNAVAILABLE -> "N/A"
    MeasurementStatus.UNKNOWN -> "—"
}
