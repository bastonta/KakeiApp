package com.kakei.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Kakei 2.0 design tokens (design/Kakei-2.0-redesign.figcraft, "Theme" variable collection).
 * Dark is the default theme; light is an equal peer with the same roles.
 */
@Immutable
data class KakeiColors(
    val isDark: Boolean,
    /** Screen background. */
    val bg: Color,
    /** Cards, sheets and the bottom bar. */
    val surf: Color,
    /** Nested surfaces: tiles, keypad keys, segmented tracks, inputs. */
    val surf2: Color,
    /** Hairline borders and dividers. */
    val line: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    /** Brand, income and positive states. */
    val mint: Color,
    /** Content drawn on top of [mint]. */
    val mintInk: Color,
    /** Expense, overspend and destructive actions. */
    val coral: Color,
    /** Attention: pending review, warnings. */
    val amber: Color,
    /** Transfers. */
    val cyan: Color,
    /** Goals. */
    val violet: Color,
    val pink: Color,
    val blue: Color,
    /** Net-worth hero gradient, top-left to bottom-right. */
    val heroA: Color,
    val heroB: Color,
    val scrim: Color
)

val DarkKakeiColors = KakeiColors(
    isDark = true,
    bg = Color(0xFF0A0F0D),
    surf = Color(0xFF121A17),
    surf2 = Color(0xFF1A2420),
    line = Color(0xFF23302B),
    text = Color(0xFFEEF3F0),
    muted = Color(0xFF8C9A94),
    faint = Color(0xFF5C6A64),
    mint = Color(0xFF3DD68C),
    mintInk = Color(0xFF06281A),
    coral = Color(0xFFFF6B5E),
    amber = Color(0xFFF5B544),
    cyan = Color(0xFF4CC9F0),
    violet = Color(0xFFA78BFA),
    pink = Color(0xFFF472B6),
    blue = Color(0xFF60A5FA),
    heroA = Color(0xFF1F8A5B),
    heroB = Color(0xFF0D3324),
    scrim = Color(0xFF000000)
)

val LightKakeiColors = KakeiColors(
    isDark = false,
    bg = Color(0xFFF3F5F2),
    surf = Color(0xFFFFFFFF),
    surf2 = Color(0xFFEDF1EE),
    line = Color(0xFFE1E7E2),
    text = Color(0xFF0D1512),
    muted = Color(0xFF5F6D67),
    faint = Color(0xFF9AA6A0),
    mint = Color(0xFF1FA466),
    mintInk = Color(0xFFFFFFFF),
    coral = Color(0xFFE5484D),
    amber = Color(0xFFD98A00),
    cyan = Color(0xFF0E9BC4),
    violet = Color(0xFF7C5CE0),
    pink = Color(0xFFD9468F),
    blue = Color(0xFF2F7BE0),
    heroA = Color(0xFF22B06E),
    heroB = Color(0xFF0F6B41),
    scrim = Color(0xFF0D1512)
)

val LocalKakeiColors = staticCompositionLocalOf { DarkKakeiColors }
