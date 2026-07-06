package io.databang.digidash.ui.tech

import androidx.compose.foundation.layout.Arrangement
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { BackendCard(state, onToggleRealBackend) }
        if (!state.useRealBackend) {
            item { ScenarioCard(state.scenario, onScenario) }
        }
        if (state.connected && state.availableGroups.isNotEmpty()) {
            item { GroupPickerCard(state.availableGroups, onReadGroup) }
        }
        item { RemoteRepoCard(state, onRemoteRepo) }
        items(state.techGroups, key = { it.group }) { group ->
            RawGroupCard(group)
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
private fun BackendCard(state: AppUiState, onToggle: (Boolean) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Real dongle (Deep OBD adapter)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.useRealBackend, onCheckedChange = onToggle)
            }
            Text(
                if (state.useRealBackend)
                    "Connecting will use the selected Bluetooth dongle. KWP1281 needs an " +
                        "ELM327 with the Deep OBD replacement firmware. The ECU session is " +
                        "still being validated on the vehicle — connect + adapter probe work; " +
                        "live measuring blocks arrive after the first real-vehicle session."
                else
                    "Using the fake backend: full UI works with no dongle or vehicle.",
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

@Composable
private fun RawGroupCard(group: TechGroup) {
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
                                "raw: ${m.rawString ?: "—"}",
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
                }
            }
        }
    }
}
