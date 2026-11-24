package dev.videotool.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Color(0xFF3DDCFF),
    onPrimary = Color(0xFF00232C),
    secondary = Color(0xFFFF4FD8),
    tertiary = Color(0xFFFF4FD8),
    background = Color(0xFF2B1B6B),
    onBackground = Color(0xFFEDE9FF),
    surface = Color(0xFF2B1B6B),
    onSurface = Color(0xFFEDE9FF),
    surfaceVariant = Color(0xFF3A2886),
    onSurfaceVariant = Color(0xFFCFC6F5),
    secondaryContainer = Color(0xFF14707F),
    onSecondaryContainer = Color(0xFFFFFFFF),
    error = Color(0xFFFF8A9B),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
