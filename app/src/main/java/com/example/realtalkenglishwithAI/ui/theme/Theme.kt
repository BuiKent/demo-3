package com.example.realtalkenglishwithai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Using colors defined in Color.kt, reflecting the old themes.xml
private val LightColorScheme = lightColorScheme(
    primary = XmlColorPrimaryOriginal, // Black from old colorPrimary
    onPrimary = AppWhite,              // White for text/icons on Black
    secondary = XmlColorAccentOriginal, // Pink from old colorAccent
    onSecondary = AppWhite,             // White for text/icons on Pink
    tertiary = Pink40,                  // Default Pink40, can be customized
    // You can further customize other colors here if needed:
    // background = AppWhite,
    // surface = AppWhite,
    // error = Red500,
    // onBackground = XmlBlack,
    // onSurface = XmlBlack,
    // onError = AppWhite,
    // etc.
)

// If you want to support Dark Theme, define DarkColorScheme similarly
// reflecting your app's desired dark theme colors.
// For now, it uses Material Design defaults.
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
    // Consider defining onPrimary, onSecondary etc. for dark theme too
    // e.g., onPrimary = XmlBlack (if Purple80 is light enough)
)

@Composable
fun RealTalkEnglishWithAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Dynamic color is available on Android 12+
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Set status bar color - you might want to customize this further
            // For a black primary, a black status bar is consistent.
            window.statusBarColor = colorScheme.primary.toArgb()
            // For a black status bar, icons should be light.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography, // From Type.kt
        content = content
    )
}
