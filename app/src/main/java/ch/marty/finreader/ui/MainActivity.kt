package ch.marty.finreader.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.marty.finreader.ui.screens.AppsScreen
import ch.marty.finreader.ui.screens.InboxScreen
import ch.marty.finreader.ui.screens.RuleEditorScreen
import ch.marty.finreader.ui.screens.RulesScreen
import ch.marty.finreader.ui.screens.SettingsScreen
import ch.marty.finreader.ui.theme.FinReaderTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            FinReaderTheme {
                FinReaderNavigation(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshNotificationAccess()
    }
}

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("inbox", "Inbox", Icons.Filled.Inbox),
    Destination("rules", "Rules", Icons.AutoMirrored.Filled.Rule),
    Destination("apps", "Apps", Icons.Filled.Apps),
    Destination("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun FinReaderNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsState()
    val failedCount by viewModel.failedCount.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (destination.route == "inbox" && failedCount > 0) {
                                BadgedBox(badge = { Badge { Text("$failedCount") } }) {
                                    Icon(destination.icon, contentDescription = destination.label)
                                }
                            } else {
                                Icon(destination.icon, contentDescription = destination.label)
                            }
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "inbox",
            modifier = Modifier.padding(padding),
        ) {
            composable("inbox") {
                InboxScreen(
                    viewModel = viewModel,
                    onCreateRule = { captureId -> navController.navigate("rule?captureId=$captureId") },
                )
            }
            composable("rules") {
                RulesScreen(
                    viewModel = viewModel,
                    onEdit = { ruleId -> navController.navigate("rule?ruleId=$ruleId") },
                    onCreate = { navController.navigate("rule") },
                )
            }
            composable("apps") { AppsScreen(viewModel) }
            composable("settings") { SettingsScreen(viewModel) }
            composable(
                route = "rule?ruleId={ruleId}&captureId={captureId}",
                arguments = listOf(
                    navArgument("ruleId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("captureId") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { entry ->
                RuleEditorScreen(
                    viewModel = viewModel,
                    ruleId = entry.arguments?.getString("ruleId"),
                    captureId = entry.arguments?.getLong("captureId") ?: -1L,
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}
