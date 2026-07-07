package io.databang.digidash.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.databang.digidash.AppUiState
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.core.diagnostics.DongleDevice
import io.databang.digidash.ui.theme.StatusColors

@Composable
fun HomeScreen(
    state: AppUiState,
    bluetoothPermissions: List<String>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshDongles: () -> Unit,
    onSelectDongle: (DongleDevice) -> Unit,
    onScanDongles: () -> Unit,
    onOpenTech: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefreshDongles() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionCard(state, onConnect, onDisconnect)
        EcuCard(state)
        DongleCard(
            state = state,
            onRequestPermission = {
                if (bluetoothPermissions.isNotEmpty()) {
                    permissionLauncher.launch(bluetoothPermissions.toTypedArray())
                } else {
                    onRefreshDongles()
                }
            },
            onRefresh = onRefreshDongles,
            onSelect = onSelectDongle,
            onScan = onScanDongles,
        )
        OutlinedButton(onClick = onOpenTech, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Build, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Tech mode — raw blocks & advanced tools")
        }
    }
}

@Composable
private fun ConnectionCard(
    state: AppUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, color) = when (state.connection) {
                    ConnectionState.CONNECTED -> "Connected" to StatusColors.normal
                    ConnectionState.CONNECTING -> "Connecting…" to StatusColors.warning
                    ConnectionState.ERROR -> "Error" to StatusColors.critical
                    ConnectionState.DISCONNECTED -> "Disconnected" to StatusColors.unavailable
                }
                AssistChip(onClick = {}, label = { Text(label) })
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Fake ECU backend (no vehicle needed)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.connecting) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                }
            }
            state.errorMessage?.let {
                Text(it, color = StatusColors.critical, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = state.connection != ConnectionState.CONNECTED && !state.connecting,
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = state.connection == ConnectionState.CONNECTED,
                ) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun EcuCard(state: AppUiState) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("ECU", style = MaterialTheme.typography.titleMedium)
            }
            val identity = state.identity
            if (identity == null) {
                Text(
                    "Not identified yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(identity.partNumberRaw, style = MaterialTheme.typography.titleLarge)
                Text(identity.component, style = MaterialTheme.typography.bodyMedium)
                identity.protocol?.let {
                    Text("Protocol: $it", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = "VIN: " + (identity.vin ?: "Not reported by this ECU"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (identity.vin != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            val model = state.model
            if (model == null) {
                Text(
                    "ECU Model: none loaded",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "ECU Model: ${model.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                model.source?.confidence?.let {
                    Text(
                        "Mapping confidence: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DongleCard(
    state: AppUiState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (DongleDevice) -> Unit,
    onScan: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Bluetooth dongle", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
            Text(
                "Deep OBD-style dongles don't need pairing — tap Scan to find the " +
                    "adapter and connect directly. (Paired devices also show here.) " +
                    "KWP1281 needs an ELM327 with the Deep OBD replacement firmware. " +
                    "Ignored in demo mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.bluetoothPermissionNeeded) {
                OutlinedButton(onClick = onRequestPermission) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Allow Bluetooth access")
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onScan, enabled = !state.scanning) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.scanning) "Scanning…" else "Scan for dongles")
                }
                if (state.scanning) {
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.CircularProgressIndicator(
                        Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp,
                    )
                }
            }

            if (state.dongles.isEmpty()) {
                Text(
                    "No devices yet. Make sure the dongle is powered (plugged into the OBD " +
                        "port) and tap Scan.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.dongles.forEach { dongle ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = state.selectedDongle?.address == dongle.address,
                            onClick = { onSelect(dongle) },
                        )
                        Column {
                            Text(
                                dongle.name + if (!dongle.paired) "  (found)" else "",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                dongle.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
