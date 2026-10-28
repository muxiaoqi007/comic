package com.example.comic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B6B),
    onPrimary = Color(0xFF1A0B0B),
    primaryContainer = Color(0xFF5C1F1F),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF4ECDC4),
    onSecondary = Color(0xFF06201E),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = Color(0xFFB7BEC9),
    outline = Color(0xFF3A4150),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB3262E),
    onPrimary = Color.White,
    secondary = Color(0xFF0F766E),
    background = Color(0xFFF7F7F9),
    surface = Color.White,
)

@Composable
fun ComicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
