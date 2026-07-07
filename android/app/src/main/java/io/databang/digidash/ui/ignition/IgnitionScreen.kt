package io.databang.digidash.ui.ignition

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.databang.digidash.AppUiState
import io.databang.digidash.IgnitionState
import io.databang.digidash.ui.theme.StatusColors

private const val MANDATORY_WARNING =
    "This tool does not set ignition timing electronically. On the VW 2E Digifant engine, " +
        "base timing is adjusted mechanically by rotating the distributor. Because this T3 " +
        "conversion uses JX flywheel/clutch/bellhousing/gearbox, use only a TDC/timing mark that " +
        "you have confirmed manually. Do not rely on Golf 3 flywheel marks unless independently " +
        "validated."

private val guidance = listOf(
    "Warm the engine.",
    "Confirm stable idle.",
    "Enter Basic Settings if supported.",
    "Use timing light on your confirmed pulley mark.",
    "Rotate the distributor mechanically if adjustment is needed.",
    "Tighten distributor clamp.",
    "Exit Basic Settings.",
    "Re-check idle and DTCs.",
)

@Composable
fun IgnitionScreen(
    state: AppUiState,
    onNote: (String) -> Unit,
    onEnterBasicSettings: () -> Unit,
    onExitBasicSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ign = state.ignition
    val manualChecks = remember { mutableStateMapOf<String, Boolean>() }

    // Safety: always leave Basic Settings when the screen is disposed.
    // Capture live values so the closure doesn't read the initial (false) state.
    val activeNow by rememberUpdatedState(ign.basicSettingsActive)
    val exitNow by rememberUpdatedState(onExitBasicSettings)
    DisposableEffect(Unit) {
        onDispose { if (activeNow) exitNow() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { WarningCard() }
        item { ValuesCard(state) }
        item { ChecklistCard(ign, manualChecks) }
        item { GuidanceCard() }
        item {
            BasicSettingsCard(
                ign = ign,
                connected = state.connected,
                onEnter = onEnterBasicSettings,
                onExit = onExitBasicSettings,
            )
        }
    }
}

@Composable
private fun WarningCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = StatusColors.warning.copy(alpha = 0.18f),
        ),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusColors.warning)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Read before adjusting timing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(MANDATORY_WARNING, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ValuesCard(state: AppUiState) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Live values", style = MaterialTheme.typography.titleMedium)
            val byKey = state.cards.associateBy { it.key }
            ValueLine(byKey, "Coolant", "coolant_temp", "coolant_temp_000")
            ValueLine(byKey, "RPM", "rpm", "rpm_000")
            ValueLine(byKey, "Battery", "battery_voltage")
            ValueLine(byKey, "Ignition advance (ECU)", "ignition_advance")
            ValueLine(byKey, "Throttle", "throttle_angle")
            ValueLine(byKey, "DTC count", "dtc_count")
            Text(
                "Target timing not configured — the app does not invent a value. " +
                    "2E spec is typically 6° (4–8°) BTDC at ~2250 rpm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ValueLine(
    byKey: Map<String, io.databang.digidash.domain.model.DashboardCardState>,
    label: String,
    vararg keys: String,
) {
    val card = keys.firstNotNullOfOrNull { byKey[it] }
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            card?.let { it.valueText + if (it.unit.isNotBlank()) " ${it.unit}" else "" } ?: "N/A",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChecklistCard(ign: IgnitionState, manual: MutableMap<String, Boolean>) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Checklist", style = MaterialTheme.typography.titleMedium)
            AutoItem("Engine at operating temperature", ign.coolantOk)
            AutoItem("Idle stable", ign.idleStable)
            AutoItem("Battery voltage healthy", ign.batteryOk)
            AutoItem("No active Hall sensor fault", ign.noHallFault)
            AutoItem("No active coolant sensor fault", ign.noCoolantFault)
            AutoItem("No active throttle/idle switch fault", ign.noThrottleFault)
            listOf(
                "Real TDC cylinder 1 confirmed",
                "Timing mark created/validated on crank pulley",
                "Timing light connected to cylinder 1",
            ).forEach { item ->
                ManualItem(item, manual[item] == true) { manual[item] = it }
            }
        }
    }
}

@Composable
private fun AutoItem(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (ok) StatusColors.normal else StatusColors.unavailable,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            if (ok) "OK" else "—",
            style = MaterialTheme.typography.labelSmall,
            color = if (ok) StatusColors.normal else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManualItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GuidanceCard() {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Procedure", style = MaterialTheme.typography.titleMedium)
            guidance.forEachIndexed { i, step ->
                Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BasicSettingsCard(
    ign: IgnitionState,
    connected: Boolean,
    onEnter: () -> Unit,
    onExit: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Basic Settings", style = MaterialTheme.typography.titleMedium)
            if (!ign.basicSettingsSupported) {
                Text(
                    "Basic Settings unsupported by current backend " +
                        "(available in demo mode; real adapter pending vehicle validation).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ign.basicSettingsActive) "ACTIVE" else "Inactive",
                    color = if (ign.basicSettingsActive) StatusColors.normal
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                "In Basic Settings the ECU holds a fixed condition (~2250 rpm) so you can " +
                    "read the ignition advance while rotating the distributor. Leaving this " +
                    "screen exits Basic Settings automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ign.basicSettingsActive) {
                Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                    Text("Exit Basic Settings")
                }
            } else {
                Button(
                    onClick = { showConfirm = true },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enter Basic Settings") }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Enter Basic Settings?") },
            text = {
                Text(
                    "This puts the ECU into its adjustment condition. Make sure the engine is " +
                        "warm, the handbrake is on and the vehicle is in neutral. Timing is still " +
                        "adjusted mechanically by rotating the distributor — the app only shows " +
                        "the ECU-reported advance."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onEnter()
                }) { Text("Enter") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
