package com.jarvis2.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = JarvisBackground,
    secondary = JarvisGold,
    onSecondary = JarvisBackground,
    background = JarvisBackground,
    onBackground = JarvisTextPrimary,
    surface = JarvisSurface,
    onSurface = JarvisTextPrimary,
    surfaceVariant = JarvisSurfaceRaised,
    onSurfaceVariant = JarvisTextSecondary,
    outline = JarvisOutline,
    error = JarvisRed,
)

/** Jarvis is always dark-themed — the whole point is the HUD look. */
@Composable
fun Jarvis2Theme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // dynamicColor intentionally ignored: a Jarvis HUD should not be re-skinned
    // by the user's wallpaper palette. `isSystemInDarkTheme()` referenced only
    // to avoid an unused-import warning if a future light variant is added.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        typography = JarvisTypography,
        content = content,
    )
}
