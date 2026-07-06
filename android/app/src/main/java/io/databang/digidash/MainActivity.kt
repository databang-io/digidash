package io.databang.digidash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.ui.dashboard.DashboardScreen
import io.databang.digidash.ui.home.HomeScreen
import io.databang.digidash.ui.tech.TechScreen
import io.databang.digidash.ui.theme.DigiDashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer(applicationContext)
        setContent {
            DigiDashTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DigiDashApp(container)
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
    DASHBOARD("dashboard", "Dashboard", Icons.Default.Speed),
    TECH("tech", "Tech", Icons.Default.Build),
}

@Composable
fun DigiDashApp(container: AppContainer) {
    val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(container))
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
                )
            }
            composable(Destination.DASHBOARD.route) {
                DashboardScreen(
                    cards = state.cards,
                    connected = state.connection == ConnectionState.CONNECTED,
                )
            }
            composable(Destination.TECH.route) {
                TechScreen(
                    state = state,
                    onScenario = viewModel::setScenario,
                    onRemoteRepo = viewModel::setRemoteRepo,
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
                    Destination.entries.forEach { dest ->
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
