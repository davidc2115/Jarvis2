package com.jarvis2.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jarvis2.app.ui.chat.ChatScreen
import com.jarvis2.app.ui.filetools.FileToolsScreen
import com.jarvis2.app.ui.graph.GraphScreen
import com.jarvis2.app.ui.integrations.IntegrationsScreen
import com.jarvis2.app.ui.settings.SettingsScreen
import com.jarvis2.app.ui.vault.VaultScreen

private sealed class JarvisDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Chat : JarvisDestination("chat", "Jarvis", Icons.Filled.Chat)
    data object Vault : JarvisDestination("vault", "Vault", Icons.Filled.Description)
    data object Graph : JarvisDestination("graph", "Toile", Icons.Filled.AccountTree)
    data object Integrations : JarvisDestination("integrations", "Téléphone", Icons.Filled.Tune)
    data object Settings : JarvisDestination("settings", "Réglages", Icons.Filled.Settings)
}

private val destinations = listOf(
    JarvisDestination.Chat,
    JarvisDestination.Vault,
    JarvisDestination.Graph,
    JarvisDestination.Integrations,
    JarvisDestination.Settings,
)

@Composable
fun Jarvis2NavHost() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                destinations.forEach { dest ->
                    NavigationBarItem(
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = JarvisDestination.Chat.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(JarvisDestination.Chat.route) { ChatScreen() }
            composable(JarvisDestination.Vault.route) { VaultScreen() }
            composable(JarvisDestination.Graph.route) { GraphScreen() }
            composable(JarvisDestination.Integrations.route) { IntegrationsScreen() }
            composable(JarvisDestination.Settings.route) { FileToolsScreenOrSettings() }
        }
    }
}

// Settings tab hosts both Réglages and the Files tools screen isn't ideal long-term;
// kept as one extra composable seam so adding a dedicated "Fichiers" tab later is a
// one-line change in `destinations` rather than a restructuring.
@Composable
private fun FileToolsScreenOrSettings() {
    SettingsScreen()
}
