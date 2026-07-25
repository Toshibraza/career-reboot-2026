package com.nova.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Sky = Color(0xFF38BDF8)
private val SkyLight = Color(0xFF7DD3FC)
private val Midnight = Color(0xFF0B1020)
private val Slate = Color(0xFF151B2E)

private val DarkColors = darkColorScheme(
    primary = SkyLight,
    onPrimary = Midnight,
    secondary = Sky,
    background = Midnight,
    onBackground = Color(0xFFE2E8F0),
    surface = Slate,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E2740),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFFCA5A5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0369A1),
    secondary = Sky,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
)

@Composable
fun NovaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
