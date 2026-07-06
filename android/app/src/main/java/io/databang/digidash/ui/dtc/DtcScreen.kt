package io.databang.digidash.ui.dtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.databang.digidash.AppUiState
import io.databang.digidash.domain.model.DtcSeverity
import io.databang.digidash.domain.model.InterpretedDtc
import io.databang.digidash.ui.theme.StatusColors

@Composable
fun DtcScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onClearConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fault codes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.dtcBusy) {
                CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = onRefresh, enabled = state.connected && !state.dtcBusy) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Read")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!state.connected) {
            EmptyHint("Not connected. Connect from the Home tab.")
        } else if (state.dtcs.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusColors.normal)
                Spacer(Modifier.width(8.dp))
                Text("No fault codes stored.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.dtcs, key = { it.code }) { dtc -> DtcRow(dtc) }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showConfirm = true },
                enabled = !state.dtcBusy,
                colors = ButtonDefaults.buttonColors(containerColor = StatusColors.critical),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear fault codes") }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Clear ECU fault codes?") },
            text = {
                Text(
                    "This may erase useful diagnostic evidence. Only continue if you have " +
                        "recorded or reviewed the current faults. The current codes are written " +
                        "to the log before clearing."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onClearConfirmed()
                }) { Text("Clear", color = StatusColors.critical) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DtcRow(dtc: InterpretedDtc) {
    val color = when (dtc.severity) {
        DtcSeverity.CRITICAL -> StatusColors.critical
        DtcSeverity.WARNING -> StatusColors.warning
        DtcSeverity.INFO -> StatusColors.unavailable
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = dtc.code,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                )
                Text(
                    text = dtc.title ?: "Unknown code (no catalog entry)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                dtc.statusRaw?.let {
                    Text(
                        "status $it",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = dtc.severity.name,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
