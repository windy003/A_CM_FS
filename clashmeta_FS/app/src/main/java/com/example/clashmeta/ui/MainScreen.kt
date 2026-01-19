package com.example.clashmeta.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.clashmeta.ui.apps.AppsScreen
import com.example.clashmeta.ui.home.HomeScreen
import com.example.clashmeta.ui.profile.ProfileScreen
import com.example.clashmeta.ui.proxy.ProxyScreen
import com.example.clashmeta.ui.settings.SettingsScreen

sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    object Home : Screen("home", "首页", { Icon(Icons.Default.Home, contentDescription = null) })
    object Proxy : Screen("proxy", "代理", { Icon(Icons.Default.Public, contentDescription = null) })
    object Profile : Screen("profile", "配置", { Icon(Icons.Default.Description, contentDescription = null) })
    object Settings : Screen("settings", "设置", { Icon(Icons.Default.Settings, contentDescription = null) })
    object Apps : Screen("apps", "分应用", { Icon(Icons.Default.Apps, contentDescription = null) })
}

val screens = listOf(Screen.Home, Screen.Proxy, Screen.Profile, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit
) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            // 只在主页面显示底部导航栏
            if (currentDestination?.route in screens.map { it.route }) {
                NavigationBar {
                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = screen.icon,
                            label = { Text(screen.title) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onStartVpn = onStartVpn,
                    onStopVpn = onStopVpn
                )
            }
            composable(Screen.Proxy.route) {
                ProxyScreen()
            }
            composable(Screen.Profile.route) {
                ProfileScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToApps = {
                        navController.navigate(Screen.Apps.route)
                    }
                )
            }
            composable(Screen.Apps.route) {
                AppsScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
