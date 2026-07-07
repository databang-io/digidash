package io.databang.digidash.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.databang.digidash.AppUiState
import io.databang.digidash.CaptureSnapshot
import io.databang.digidash.ui.theme.StatusColors

private val steps = listOf(
    "Warm idle" to "Engine warm, stable idle. Then capture.",
    "Revving (~2500 rpm)" to "Hold ~2500 rpm steady. Then capture.",
    "Back to idle" to "Let it settle back to idle. Then capture.",
)

/**
 * Guides the user through capturing group 000's 10 raw values at known engine
 * states, so the field layout can be identified by correlation (which number
 * tracks RPM, which tracks coolant…). No VCDS/Deep OBD needed.
 */
@Composable
fun CaptureWizardScreen(
    state: AppUiState,
    onCapture: (String) -> Unit,
    onClear: () -> Unit,
    onExport: ((String) -> Unit) -> Unit,
    onShareFile: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Guided group-000 capture", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Group 000 sends 10 raw numbers. Capture them at each engine state below; " +
                "by comparing them I can tell which number is RPM, coolant, etc. — no other " +
                "app needed. Then export and share the file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.connected) {
            Text(
                "Not connected — connect first (demo works too, to try the flow).",
                color = StatusColors.warning,
            )
        }

        steps.forEach { (label, hint) ->
            val done = state.captureSnapshots.any { it.label == label }
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(hint, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = { onCapture(label) },
                        enabled = state.connected,
                    ) { Text(if (done) "Capture again" else "Capture") }
                }
            }
        }

        if (state.captureSnapshots.isNotEmpty()) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Captured", style = MaterialTheme.typography.titleMedium)
                    state.captureSnapshots.forEach { snap -> SnapshotRow(snap) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onExport(onShareFile) }) { Text("Export & share") }
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun SnapshotRow(snap: CaptureSnapshot) {
    Column {
        Text(snap.label, style = MaterialTheme.typography.labelLarge)
        Text(
            snap.rawValues.joinToString("  "),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
