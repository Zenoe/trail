package com.trail.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun TrailTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF91D5B4),
        secondary = Color(0xFFB3CBBB), secondaryContainer = Color(0xFF304A3C), onSecondaryContainer = Color(0xFFD5EBDD),
        surfaceContainer = Color(0xFF203228), background = Color(0xFF142019), surface = Color(0xFF1B2921))
    else lightColorScheme(primary = Color(0xFF17634B), background = Color(0xFFF5F6F2),
        secondary = Color(0xFF516858), secondaryContainer = Color(0xFFE1EBDD), onSecondaryContainer = Color(0xFF203C2C),
        surfaceContainer = Color(0xFFEDF1E8), surface = Color(0xFFF5F6F2), onSurface = Color(0xFF182B25))
    MaterialTheme(colorScheme = colors, content = content)
}
