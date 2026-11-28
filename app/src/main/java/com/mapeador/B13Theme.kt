package com.mapeador

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object B13Theme {
    val BACKGROUND_ARGB = 0xFF0C0C12.toInt()
    val PANEL_ARGB = 0xFF191923.toInt()
    val ACCENT_ARGB = 0xFF914BFF.toInt()
    val PANEL_DARK_ARGB = 0xFF12121B.toInt()
    val PANEL_ELEVATED_ARGB = 0xFF222230.toInt()
    val ACCENT_SECONDARY_ARGB = 0xFFB58AFF.toInt()
    val SUCCESS_ARGB = 0xFF36D979.toInt()
    val ERROR_ARGB = 0xFFF05B68.toInt()
    val TEXT_PRIMARY_ARGB = 0xFFEDEDF5.toInt()
    val MUTED_ARGB = 0xFFA5A5B4.toInt()
    val CURSOR_OUTLINE_ARGB = PANEL_ELEVATED_ARGB

    const val BASE_SPACING_DP = 8
    const val CARD_CORNER_DP = 16
    const val CONTROL_CORNER_DP = 12
    const val LARGE_CORNER_DP = 22
    const val MIN_TOUCH_TARGET_DP = 48
    const val CURSOR_SIZE_DP = 24

    val Background = Color(BACKGROUND_ARGB)
    val Panel = Color(PANEL_ARGB)
    val PanelElevated = Color(PANEL_ELEVATED_ARGB)
    val Accent = Color(ACCENT_ARGB)
    val AccentSecondary = Color(ACCENT_SECONDARY_ARGB)
    val Success = Color(SUCCESS_ARGB)
    val Error = Color(ERROR_ARGB)
    val PanelDark = Color(PANEL_DARK_ARGB)
    val TextPrimary = Color(TEXT_PRIMARY_ARGB)
    val Muted = Color(MUTED_ARGB)

    val Colors = darkColorScheme(
        primary = Accent,
        onPrimary = Color.White,
        primaryContainer = PanelElevated,
        onPrimaryContainer = TextPrimary,
        secondary = AccentSecondary,
        onSecondary = Color(0xFF21005D),
        background = Background,
        onBackground = TextPrimary,
        surface = Panel,
        onSurface = TextPrimary,
        surfaceVariant = PanelDark,
        onSurfaceVariant = Muted,
        error = Error,
        onError = Color.White,
    )

    val ComponentShapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )
}

@Composable
fun B13MaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = B13Theme.Colors,
        shapes = B13Theme.ComponentShapes,
        content = content,
    )
}
