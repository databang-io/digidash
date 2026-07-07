package io.databang.digidash.ui.graph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.databang.digidash.core.logging.ReplayData

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplayScreen(
    fileName: String,
    data: ReplayData,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedKey by remember(data) { mutableStateOf(data.keys.firstOrNull()) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(fileName, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))

        if (data.isEmpty) {
            Text(
                "No sample rows to graph in this log.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            data.keys.forEach { key ->
                FilterChip(
                    selected = key == selectedKey,
                    onClick = { selectedKey = key },
                    label = { Text(key) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        val key = selectedKey
        val series = key?.let { data.series[it] }.orEmpty()
        val unit = key?.let { data.unitByKey[it] }.orEmpty()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${key ?: ""} ${if (unit.isNotBlank()) "($unit)" else ""} — ${series.size} points",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (series.size < 2) {
                    Text("Not enough points.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LineChart(
                        points = series.map { ChartPoint(it.first.toDouble(), it.second) },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    )
                    val ys = series.map { it.second }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "min ${fmt(ys.min())} · max ${fmt(ys.max())} · avg ${fmt(ys.average())} $unit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun fmt(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)
