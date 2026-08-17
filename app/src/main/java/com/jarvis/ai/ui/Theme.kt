package com.jarvis.ai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val JarvisBg = Color(0xFF070B14)
val JarvisSurface = Color(0xFF0E1524)
val JarvisSurfaceHigh = Color(0xFF121C2E)
val JarvisCyan = Color(0xFF22D3EE)
val JarvisBlue = Color(0xFF3B82F6)
val JarvisText = Color(0xFFE6EEF8)
val JarvisMuted = Color(0xFF8AA0BE)
val JarvisGreen = Color(0xFF34D399)
val JarvisRed = Color(0xFFF87171)

private val JarvisColors = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = Color(0xFF04121A),
    secondary = JarvisBlue,
    background = JarvisBg,
    onBackground = JarvisText,
    surface = JarvisSurface,
    onSurface = JarvisText,
    surfaceVariant = JarvisSurfaceHigh,
    onSurfaceVariant = JarvisMuted,
    error = JarvisRed
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    // Toujours en thème sombre (identité visuelle Jarvis)
    MaterialTheme(
        colorScheme = JarvisColors,
        typography = Typography(),
        content = content
    )
}
