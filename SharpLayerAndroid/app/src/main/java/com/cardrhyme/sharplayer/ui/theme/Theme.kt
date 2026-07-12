package com.cardrhyme.sharplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF7FE8C1),
    onPrimary = Color(0xFF042018),
    secondary = Color(0xFF8DB4FF),
    background = Color(0xFF070A10),
    onBackground = Color(0xFFEAF2FF),
    surface = Color(0xFF101722),
    onSurface = Color(0xFFEAF2FF),
    surfaceVariant = Color(0xFF172130),
    onSurfaceVariant = Color(0xFF9AA9C0),
    outline = Color(0xFF2B374B),
    error = Color(0xFFFF96A5)
)

@Composable
fun SharpLayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
