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
    secondary = Color(0xFFB5C5A5),
    onSecondary = Color(0xFF202C19),
    tertiary = Color(0xFFE1BE84),
    surfaceContainer = Color(0xFF282019),
    surfaceContainerHigh = Color(0xFF332920),
    surfaceContainerHighest = Color(0xFF3B3026),
    background = Color(0xFF15110C),
    onBackground = Color(0xFFEFE7DA),
    surface = Color(0xFF1E1811),
    onSurface = Color(0xFFEFE7DA),
    surfaceVariant = Color(0xFF2A2118),
    onSurfaceVariant = Color(0xFFB7A48B),
    outline = Color(0xFF4A3A29),
    secondaryContainer = Color(0xFF3A2D20),
    onSecondaryContainer = Color(0xFFEFE7DA),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFA3262E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DED8),
    onPrimaryContainer = Color(0xFF651B22),
    secondary = Color(0xFF53654A),
    tertiary = Color(0xFF805A25),
    surfaceContainer = Color(0xFFF0E7D9),
    surfaceContainerHigh = Color(0xFFEDE2D2),
    surfaceContainerHighest = Color(0xFFE7DAC8),
    background = Color(0xFFF6EFE2),
    onBackground = Color(0xFF271C11),
    surface = Color(0xFFFFFBF3),
    onSurface = Color(0xFF271C11),
    surfaceVariant = Color(0xFFECE2D2),
    onSurfaceVariant = Color(0xFF7C6A53),
    outline = Color(0xFFD9CBB4),
    secondaryContainer = Color(0xFFE8D8BC),
    onSecondaryContainer = Color(0xFF4A301A),
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
