package io.databang.digidash.ui.tech

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.databang.digidash.AppContainer
import io.databang.digidash.AppUiState
import io.databang.digidash.TechGroup
import io.databang.digidash.core.diagnostics.fake.FakeScenario
import io.databang.digidash.core.ecumodel.EcuModel

/**
 * Tech mode: raw measuring blocks (raw + interpreted side by side),
 * fake-backend scenario tools and the community ECU-model repository setting.
 */
@Composable
fun TechScreen(
    state: AppUiState,
    onScenario: (FakeScenario) -> Unit,
    onRemoteRepo: (url: String, enabled: Boolean) -> Unit,
    onReadGroup: (Int) -> Unit,
    onToggleRealBackend: (Boolean) -> Unit,
    onExportCapture: ((String) -> Unit) -> Unit,
    onToggleAlerts: (Boolean) -> Unit,
    onResetPeaks: () -> Unit,
    onToggleCaptureRaw: (Boolean) -> Unit,
    onToggleReadOnly: (Boolean) -> Unit,
    onOpenCaptureWizard: () -> Unit,
    onTogglePin: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { BackendCard(state, onToggleRealBackend) }
        if (state.connected) {
            item {
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenCaptureWizard,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Guided group-000 capture (identify the layout)") }
            }
        }
        if (state.useRealBackend) {
            item { LiveSessionCard(state, onToggleCaptureRaw, onToggleReadOnly) }
        }
        item { AlertsCard(state, onToggleAlerts, onResetPeaks) }
        item { OverlayCard() }
        if (!state.useRealBackend) {
            item { ScenarioCard(state.scenario, onScenario) }
        }
        if (state.connected && state.availableGroups.isNotEmpty()) {
            item { GroupPickerCard(state.availableGroups, onReadGroup) }
            item { ExportCaptureCard(onExportCapture) }
        }
        item { RemoteRepoCard(state, onRemoteRepo) }
        items(state.techGroups, key = { it.group }) { group ->
            RawGroupCard(group, state.pinnedCards, onTogglePin)
        }
        if (state.techGroups.isEmpty()) {
            item {
                Text(
                    "No measuring blocks yet. Connect first (Home tab).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LiveSessionCard(
    state: AppUiState,
    onToggleCaptureRaw: (Boolean) -> Unit,
    onToggleReadOnly: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Live KWP1281 session (vehicle testing)", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Capture raw traffic", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.captureRawTraffic, onCheckedChange = onToggleCaptureRaw)
            }
            Text(
                "Logs every adapter byte (TX/RX hex) to logs/raw_*.log so the KWP1281 framing " +
                    "can be debugged from the capture. Share it after a session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Read-only safe mode", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.readOnlyMode, onCheckedChange = onToggleReadOnly)
            }
            Text(
                "When on, clear-DTC and Basic Settings are refused — zero writes to the ECU " +
                    "while validating reads. Turn off to test clear/Basic Settings live.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlertsCard(state: AppUiState, onToggle: (Boolean) -> Unit, onResetPeaks: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Threshold alerts (buzz + beep)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.alertsEnabled, onCheckedChange = onToggle)
            }
            Text(
                "Vibrates and beeps when a value crosses into warning or critical — " +
                    "e.g. coolant overheating — even when you're on another screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedButton(onClick = onResetPeaks) {
                Text("Reset min/max peaks")
            }
        }
    }
}

@Composable
private fun BackendCard(state: AppUiState, onToggle: (Boolean) -> Unit) {
    // Demo mode ON == fake backend (no car needed). Turning it OFF uses the
    // real Bluetooth dongle.
    val demoMode = !state.useRealBackend
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Demo mode (no car needed)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = demoMode, onCheckedChange = { onToggle(!it) })
            }
            Text(
                if (demoMode)
                    "Demo mode is ON: the app runs on simulated ECU data so you can try every " +
                        "screen — dashboard, faults, ignition Basic Settings, logging — without a " +
                        "dongle or vehicle. Turn it off to use the real Bluetooth adapter."
                else
                    "Demo mode is OFF — connecting uses the selected Bluetooth dongle. KWP1281 " +
                        "needs an ELM327 with the Deep OBD replacement firmware. Connect + adapter " +
                        "probe work now; live ECU blocks arrive after the first vehicle session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScenarioCard(current: FakeScenario, onScenario: (FakeScenario) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fake backend scenario", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FakeScenario.entries.forEach { scenario ->
                    FilterChip(
                        selected = scenario == current,
                        onClick = { onScenario(scenario) },
                        label = { Text(scenario.name.lowercase().replace('_', ' ')) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportCaptureCard(onExportCapture: ((String) -> Unit) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Export debug capture", style = MaterialTheme.typography.titleMedium)
            Text(
                "Reads every group once and saves a JSON snapshot (identity + groups + DTCs) " +
                    "to share for ECU-model validation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    onExportCapture { path -> shareFile(context, path, "application/json") }
                },
            ) { Text("Export & share capture") }
        }
    }
}

private fun shareFile(context: android.content.Context, path: String, mime: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", java.io.File(path),
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share capture"))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupPickerCard(groups: List<Int>, onReadGroup: (Int) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Read measuring block on demand", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap a group to poll it once. Group 000 is the primary raw display on this ECU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { g ->
                    AssistChip(
                        onClick = { onReadGroup(g) },
                        label = { Text(EcuModel.groupKey(g)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteRepoCard(
    state: AppUiState,
    onRemoteRepo: (url: String, enabled: Boolean) -> Unit,
) {
    var url by rememberSaveable(state.remoteRepoUrl) { mutableStateOf(state.remoteRepoUrl) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ECU models from public git repo",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.remoteRepoEnabled,
                    onCheckedChange = { onRemoteRepo(url, it) },
                )
            }
            Text(
                "Raw HTTPS base URL of an ecu_models directory. Fetched models are " +
                    "cached for offline use; bundled models remain the fallback. " +
                    "Applied at next connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(AppContainer.DEFAULT_REMOTE_REPO_HINT) },
                label = { Text("Repository URL") },
            )
            if (url != state.remoteRepoUrl) {
                androidx.compose.material3.TextButton(
                    onClick = { onRemoteRepo(url, state.remoteRepoEnabled) },
                ) { Text("Save URL") }
            }
        }
    }
}

/** Floating gauge-bar (system overlay over Waze/any app) toggle + gauge picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("digidash", android.content.Context.MODE_PRIVATE) }
    val candidates = listOf(
        "rpm" to "Régime", "coolant_temp" to "Temp. eau", "battery_voltage" to "Batterie",
        "lambda_signal" to "Lambda", "injection_time" to "Injection", "throttle_angle" to "Papillon",
        "intake_air_temp" to "Air adm.", "engine_load" to "Charge", "dtc_count" to "Défauts",
        "gps_speed" to "Vitesse GPS",
    )
    fun read(pref: String, def: String) = (prefs.getString(pref, def) ?: "")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    var selected by remember {
        mutableStateOf(read("overlay_gauges",
            io.databang.digidash.core.overlay.GaugeOverlayService.DEFAULT_GAUGES))
    }
    var pillSelected by remember {
        mutableStateOf(read("overlay_pill_gauges",
            io.databang.digidash.core.overlay.GaugeOverlayService.DEFAULT_PILL_GAUGES))
    }
    var running by remember { mutableStateOf(io.databang.digidash.core.overlay.GaugeOverlayService.isRunning) }

    fun persist(pref: String, set: Set<String>) {
        val ordered = candidates.map { it.first }.filter { it in set }
        prefs.edit().putString(pref, ordered.joinToString(",")).apply()
        io.databang.digidash.core.overlay.GaugeOverlayService.refresh(ctx)
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Barre flottante (par-dessus Waze / autres apps)",
                style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = running, onCheckedChange = { on ->
                    if (on) {
                        if (!android.provider.Settings.canDrawOverlays(ctx)) {
                            ctx.startActivity(android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${ctx.packageName}"))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            io.databang.digidash.core.overlay.GaugeOverlayService.start(ctx)
                            running = true
                        }
                    } else {
                        io.databang.digidash.core.overlay.GaugeOverlayService.stop(ctx)
                        running = false
                    }
                })
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Barre affichée" else "Barre masquée",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text("Autorise « Afficher par-dessus les autres apps » si demandé. " +
                "Tap la barre = replier en pastille · appui long = haut/bas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Jauges (barre dépliée) :", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { (key, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = key in selected,
                        onClick = {
                            selected = if (key in selected) selected - key else selected + key
                            persist("overlay_gauges", selected)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text("Jauges prioritaires (visibles repliée) :", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { (key, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = key in pillSelected,
                        onClick = {
                            pillSelected = if (key in pillSelected) pillSelected - key else pillSelected + key
                            persist("overlay_pill_gauges", pillSelected)
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RawGroupCard(
    group: TechGroup,
    pinned: Set<String> = emptySet(),
    onTogglePin: (String) -> Unit = {},
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Group ${EcuModel.groupKey(group.group)} — ${group.label}",
                style = MaterialTheme.typography.titleMedium,
            )
            HorizontalDivider()
            group.measurements.forEach { m ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${m.fieldIndex}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(24.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyMedium)
                        Row {
                            Text(
                                "raw: ${m.rawString ?: "—"}" + (m.wireRaw?.let { " · wire: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (m.confidence == "low") {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "low confidence",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                    Text(
                        text = m.displayValue + if (m.unit.isNotBlank()) " ${m.unit}" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    // VCDS-style pin: check to show this zone as a Dash tile.
                    androidx.compose.material3.IconToggleButton(
                        checked = m.key in pinned,
                        onCheckedChange = { onTogglePin(m.key) },
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = if (m.key in pinned) Icons.Filled.Star
                            else Icons.Filled.StarBorder,
                            contentDescription = "Show on dashboard",
                            tint = if (m.key in pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
