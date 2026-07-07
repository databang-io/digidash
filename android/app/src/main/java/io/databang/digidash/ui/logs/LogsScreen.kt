package io.databang.digidash.ui.logs

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.databang.digidash.AppUiState
import io.databang.digidash.core.logging.LogFile
import io.databang.digidash.ui.theme.StatusColors
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun LogsScreen(
    state: AppUiState,
    onToggleRecording: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (LogFile) -> Unit,
    onOpenGraph: (LogFile) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<LogFile?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Logs", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "CSV logs keep raw and interpreted values. Stored locally only, never uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onToggleRecording,
            enabled = state.connected || state.recording,
            colors = if (state.recording)
                ButtonDefaults.buttonColors(containerColor = StatusColors.critical)
            else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (state.recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (state.recording) "Stop recording" else "Start recording")
        }
        state.currentLogFile?.let {
            Spacer(Modifier.height(4.dp))
            Text("Recording: $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.critical)
        }
        if (!state.connected && !state.recording) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect first to record live data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Saved logs (${state.logs.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.logs, key = { it.path }) { log ->
                Card {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(log.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "%.1f kB · %s".format(
                                    log.sizeBytes / 1024.0,
                                    DateFormat.getDateTimeInstance().format(Date(log.modifiedMillis)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onOpenGraph(log) }) {
                            Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = "Graph")
                        }
                        IconButton(onClick = { shareLog(context, log) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { pendingDelete = log }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { log ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete log?") },
            text = { Text("Delete ${log.name}? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(log)
                    pendingDelete = null
                }) { Text("Delete", color = StatusColors.critical) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun shareLog(context: android.content.Context, log: LogFile) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(log.path),
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share log"))
}
