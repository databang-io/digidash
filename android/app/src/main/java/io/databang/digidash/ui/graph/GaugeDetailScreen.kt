package io.databang.digidash.ui.graph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.databang.digidash.core.history.PeakHold
import io.databang.digidash.core.history.Sample
import io.databang.digidash.domain.model.DashboardCardState
import io.databang.digidash.ui.theme.StatusColors

/** Live line graph + peaks for one dashboard value (opened by tapping a card). */
@Composable
fun GaugeDetailScreen(
    card: DashboardCardState?,
    history: List<Sample>,
    peak: PeakHold?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                card?.title ?: "Value",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Spacer(Modifier.height(8.dp))

        val unit = card?.unit.orEmpty()
        Text(
            text = (card?.valueText ?: "N/A") + if (unit.isNotBlank()) " $unit" else "",
            style = MaterialTheme.typography.displaySmall,
            color = StatusColors.normal,
        )
        peak?.let {
            Text(
                "min ${fmt(it.min)} · max ${fmt(it.max)} $unit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("History (${history.size} samples)", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                if (history.size < 2) {
                    Text(
                        "Collecting… keep this value updating for a few seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LineChart(
                        points = history.map { ChartPoint(it.timeMillis.toDouble(), it.value) },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                }
            }
        }
    }
}

private fun fmt(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)
