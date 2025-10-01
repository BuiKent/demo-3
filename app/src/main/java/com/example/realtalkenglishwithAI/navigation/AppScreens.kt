package com.example.realtalkenglishwithai.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings // Placeholder, ideally use correct icons
import androidx.compose.ui.graphics.vector.ImageVector

// It'''s better practice to use string resources for titles, but using direct strings for now.
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home_screen", "Home", Icons.Filled.Home)
    // TODO: Replace with actual drawable resources or appropriate Material Icons
    object Practice : Screen("practice_screen", "Practice", Icons.Filled.List)
    object Progress : Screen("progress_screen", "Progress", Icons.Filled.Settings) // Example: Bar chart icon might be Icons.Filled.BarChart
    object Profile : Screen("profile_screen", "Profile", Icons.Filled.AccountCircle)
}

// List of screens for easy iteration in BottomNavigation
val bottomNavScreens = listOf(
    Screen.Home,
    Screen.Practice,
    Screen.Progress,
    Screen.Profile
)
