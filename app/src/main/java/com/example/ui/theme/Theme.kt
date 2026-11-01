package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = GeminiCyan,
    onPrimary = Color.Black,
    secondary = GeminiPurple,
    onSecondary = Color.White,
    tertiary = GeminiBlue,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = GeminiRed
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
