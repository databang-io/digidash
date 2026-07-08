package io.databang.digidash.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    "lambda_signal" to GaugeRange(0.0, 100.0),
    "ignition_advance" to GaugeRange(-10.0, 50.0),
    "rpm_000" to GaugeRange(0.0, 7000.0),
)

@Composable
fun DashboardScreen(
    cards: List<DashboardCardState>,
    connected: Boolean,
    peaks: Map<String, io.databang.digidash.core.history.PeakHold> = emptyMap(),
    editMode: Boolean = false,
    onReorder: (List<String>) -> Unit = {},
    onCardClick: (String) -> Unit = {},
    onCycleSize: (String) -> Unit = {},
    onEnterEditMode: () -> Unit = {},
    onExitEditMode: () -> Unit = {},
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
                    "Editing — drag to move · tap a card to resize",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onExitEditMode) { Text("Done") }
            }
        }
        // Adaptive grid; long-press a card to enter edit mode + drag to reorder.
        ReorderableGaugeGrid(
            cards = cards,
            onReorder = onReorder,
            onEnterEditMode = onEnterEditMode,
            modifier = Modifier.weight(1f),
            cell = { card, isDragged ->
                DashboardCard(card, peaks[card.key], isDragged, editMode) {
                    if (editMode) onCycleSize(card.key) else onCardClick(card.key)
                }
            },
        )
    }
}

@Composable
private fun DashboardCard(
    card: DashboardCardState,
    peak: io.databang.digidash.core.history.PeakHold? = null,
    isDragged: Boolean = false,
    editMode: Boolean = false,
    onClick: () -> Unit = {},
) {
    val gaugeHeight = when (card.size) {
        io.databang.digidash.domain.model.CardSize.SMALL -> null
        io.databang.digidash.domain.model.CardSize.WIDE -> 120.dp
        io.databang.digidash.domain.model.CardSize.LARGE -> 190.dp
    }
    val statusColor = card.statusColor()
    val elevation = if (isDragged) 12.dp else 0.dp
    val scale = if (isDragged) 1.06f else 1f
    Card(
        onClick = onClick,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .zIndex(if (isDragged) 1f else 0f),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            if (editMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Resize",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(16.dp),
                    )
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    Text(
                        text = "tap: ${card.size.name.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
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
