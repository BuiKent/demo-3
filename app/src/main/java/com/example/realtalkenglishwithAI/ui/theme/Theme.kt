package com.example.realtalkenglishwithAI.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CustomLightColorScheme = lightColorScheme(
    primary = AppWhite,
    onPrimary = XmlBlack,
    secondary = XmlColorAccentOriginal,
    onSecondary = AppWhite,
    background = AppWhite,
    surface = AppWhite,
    error = Red500,
    onError = AppWhite
)

private val CustomDarkColorScheme = darkColorScheme(
    primary = XmlBlack,
    onPrimary = AppWhite,
    secondary = Pink80,
    onSecondary = XmlBlack,
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    error = Red500,
    onError = AppWhite
)

@Composable
fun RealTalkEnglishWithAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        CustomDarkColorScheme
    } else {
        CustomLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
