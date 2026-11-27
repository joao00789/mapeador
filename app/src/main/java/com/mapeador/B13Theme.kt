package com.mapeador

import androidx.compose.ui.graphics.Color

/** Shared B13 visual tokens; adjust these values to reskin the UI without changing behavior. */
object B13Theme {
    val BACKGROUND_ARGB = 0xFF14060A.toInt()
    val PANEL_ARGB = 0xFFB71C2B.toInt()
    val ACCENT_ARGB = 0xFFE5484D.toInt()
    val PANEL_DARK_ARGB = 0xFF2A1014.toInt()
    val CURSOR_OUTLINE_ARGB = 0xFF221216.toInt()

    const val BASE_SPACING_DP = 8
    const val CARD_CORNER_DP = 16
    const val CONTROL_CORNER_DP = 12
    const val LARGE_CORNER_DP = 22
    const val MIN_TOUCH_TARGET_DP = 48
    const val CURSOR_SIZE_DP = 24

    val Background = Color(BACKGROUND_ARGB)
    val Panel = Color(PANEL_ARGB)
    val Accent = Color(ACCENT_ARGB)
    val PanelDark = Color(PANEL_DARK_ARGB)
    val TextPrimary = Color(0xFFFFE9EA)
    val Muted = Color(0xFFF3CDD0)
}
