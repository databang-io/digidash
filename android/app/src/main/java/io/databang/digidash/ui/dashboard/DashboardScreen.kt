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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.databang.digidash.domain.model.DashboardCardState
import io.databang.digidash.domain.model.MeasurementStatus
import io.databang.digidash.ui.theme.StatusColors

/** Dial ranges for known dashboard roles so gauges have sensible sweeps. */
private data class GaugeRange(val min: Double, val max: Double)

private val gaugeRanges = mapOf(
    "rpm" to GaugeRange(0.0, 7000.0),
    "coolant_temp" to GaugeRange(-20.0, 130.0),
    "battery_voltage" to GaugeRange(8.0, 16.0),
    "injection_time" to GaugeRange(0.0, 20.0),
    "ignition_advance" to GaugeRange(-10.0, 50.0),
    "engine_load" to GaugeRange(0.0, 100.0),
)

@Composable
fun DashboardScreen(
    cards: List<DashboardCardState>,
    connected: Boolean,
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

    // Adaptive grid: phones portrait ≈ 2 columns, landscape / tablets more.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { it.key }) { card ->
            DashboardCard(card)
        }
    }
}

@Composable
private fun DashboardCard(card: DashboardCardState) {
    val statusColor = card.statusColor()
    Card(
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
