package com.example.realtalkenglishwithAI.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import com.example.realtalkenglishwithAI.ui.screens.HomeScreen
import com.example.realtalkenglishwithAI.ui.screens.PracticeScreen
import com.example.realtalkenglishwithAI.ui.screens.ProgressScreen
import com.example.realtalkenglishwithAI.ui.screens.ProfileScreen

// Screen và bottomNavScreens được định nghĩa trong AppScreens.kt cùng package này

@OptIn(ExperimentalMaterial3Api::class) // Scaffold là experimental trong M3
@Composable
fun MainAppNavigation() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavScreens.forEach { screen ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen(/* Có thể truyền navController nếu HomeScreen cần */) }
            composable(Screen.Practice.route) { PracticeScreen(/* ... */) }
            composable(Screen.Progress.route) { ProgressScreen(/* ... */) }
            composable(Screen.Profile.route) { ProfileScreen(/* ... */) }
            // Các composable route khác có thể được định nghĩa ở đây
        }
    }
}
