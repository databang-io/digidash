package io.databang.digidash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.databang.digidash.ui.dashboard.DashboardScreen
import io.databang.digidash.ui.dtc.DtcScreen
import io.databang.digidash.ui.graph.GaugeDetailScreen
import io.databang.digidash.ui.graph.ReplayScreen
import io.databang.digidash.ui.home.HomeScreen
import io.databang.digidash.ui.ignition.IgnitionScreen
import io.databang.digidash.ui.logs.LogsScreen
import io.databang.digidash.ui.tech.TechScreen
import io.databang.digidash.ui.theme.DigiDashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as DigiDashApplication
        setContent {
            DigiDashTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DigiDashApp(app.container, app.sessionHolder)
                }
            }
        }
    }
}

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "Home", Icons.Default.Home),
    DASHBOARD("dashboard", "Dash", Icons.Default.Speed),
    DTC("dtc", "Faults", Icons.Default.Warning),
    IGNITION("ignition", "Timing", Icons.Default.Tune),
    LOGS("logs", "Logs", Icons.AutoMirrored.Filled.List),
    TECH("tech", "Tech", Icons.Default.Build),
}

@Composable
fun DigiDashApp(container: AppContainer, sessionHolder: SessionHolder) {
    val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(container, sessionHolder))
    val state by viewModel.ui.collectAsState()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Destination.HOME.route

    // Responsive navigation: bottom bar in portrait/narrow, rail in landscape/wide.
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val navigate: (Destination) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = modifier,
        ) {
            composable(Destination.HOME.route) {
                HomeScreen(
                    state = state,
                    bluetoothPermissions = viewModel.bluetoothPermissions(),
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onRefreshDongles = viewModel::refreshDongles,
                    onSelectDongle = viewModel::selectDongle,
                    onOpenTech = { navigate(Destination.TECH) },
                )
            }
            composable(Destination.DASHBOARD.route) {
                DashboardScreen(
                    cards = state.cards,
                    connected = state.connected,
                    peaks = state.peaks,
                    onReorder = viewModel::saveCardOrder,
                    onCardClick = { key -> navController.navigate("detail/$key") },
                )
            }
            composable("detail/{key}") { entry ->
                val key = entry.arguments?.getString("key").orEmpty()
                GaugeDetailScreen(
                    card = viewModel.cardFor(key),
                    history = viewModel.historyOf(key),
                    peak = viewModel.peakFor(key),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("replay/{name}") { entry ->
                val name = entry.arguments?.getString("name").orEmpty()
                val path = state.logs.find { it.name == name }?.path
                val data = remember(path) {
                    path?.let { viewModel.parseLog(it) }
                        ?: io.databang.digidash.core.logging.ReplayData(emptyList(), emptyMap(), emptyMap())
                }
                ReplayScreen(fileName = name, data = data, onBack = { navController.popBackStack() })
            }
            composable(Destination.DTC.route) {
                DtcScreen(
                    state = state,
                    onRefresh = viewModel::refreshDtcs,
                    onClearConfirmed = viewModel::clearDtcsConfirmed,
                )
            }
            composable(Destination.IGNITION.route) {
                IgnitionScreen(
                    state = state,
                    lambdaHistory = viewModel.historyOf("lambda_signal"),
                    onNote = viewModel::addLogNote,
                    onEnterBasicSettings = viewModel::enterBasicSettingsConfirmed,
                    onExitBasicSettings = viewModel::exitBasicSettings,
                )
            }
            composable(Destination.LOGS.route) {
                LogsScreen(
                    state = state,
                    onToggleRecording = viewModel::toggleRecording,
                    onRefresh = viewModel::refreshLogs,
                    onDelete = viewModel::deleteLog,
                    onOpenGraph = { log -> navController.navigate("replay/${log.name}") },
                )
            }
            composable(Destination.TECH.route) {
                TechScreen(
                    state = state,
                    onScenario = viewModel::setScenario,
                    onRemoteRepo = viewModel::setRemoteRepo,
                    onReadGroup = viewModel::readGroup,
                    onToggleRealBackend = viewModel::setUseRealBackend,
                    onExportCapture = viewModel::exportCapture,
                    onToggleAlerts = viewModel::setAlertsEnabled,
                    onResetPeaks = viewModel::resetPeaks,
                )
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Destination.entries.forEach { dest ->
                    NavigationRailItem(
                        selected = currentRoute == dest.route,
                        onClick = { navigate(dest) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
            content(Modifier.fillMaxSize())
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    // Tech lives on the Home screen to keep the bar to 5 primary tabs.
                    Destination.entries.filter { it != Destination.TECH }.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = { navigate(dest) },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            },
        ) { padding ->
            content(Modifier.fillMaxSize().padding(padding))
        }
    }
}
