package com.kakei.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode {
    DARK, LIGHT, SYSTEM
}

val LocalIsDarkTheme = staticCompositionLocalOf { true }

private val KakeiMatteDarkColorScheme = darkColorScheme(
    primary = EmeraldBrand,
    onPrimary = Color.White,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = Color(0xFF6EE7B7),
    secondary = EmeraldLight,
    onSecondary = Color(0xFF062E1E),
    secondaryContainer = Color(0xFF183B2B),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = CyanTransfer,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF164E63),
    onTertiaryContainer = Color(0xFFCFFAFE),
    background = MatteDarkBackground,
    onBackground = MatteDarkTextPrimary,
    surface = MatteDarkSurface,
    onSurface = MatteDarkTextPrimary,
    surfaceVariant = MatteDarkSurfaceElevated,
    onSurfaceVariant = MatteDarkTextSecondary,
    surfaceContainerLowest = Color(0xFF0D0F12),
    surfaceContainerLow = Color(0xFF161920),
    surfaceContainer = MatteDarkSurface,
    surfaceContainerHigh = MatteDarkSurfaceElevated,
    surfaceContainerHighest = Color(0xFF2B303C),
    outline = MatteDarkBorder,
    outlineVariant = Color(0xFF384152),
    error = CoralExpense,
    onError = Color.White,
    errorContainer = CoralContainerDark,
    onErrorContainer = Color(0xFFFCA5A5)
)

private val KakeiLightColorScheme = lightColorScheme(
    primary = EmeraldBrand,
    onPrimary = Color.White,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = Color(0xFF065F46),
    secondary = EmeraldBrand,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = CyanTransfer,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFFAFE),
    onTertiaryContainer = Color(0xFF155E75),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceElevated,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    outline = LightBorder,
    outlineVariant = Color(0xFFCBD5E1),
    error = CoralExpense,
    onError = Color.White,
    errorContainer = CoralContainerLight,
    onErrorContainer = Color(0xFF991B1B)
)

@Composable
fun KakeiTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) KakeiMatteDarkColorScheme else KakeiLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            // When isDark is true, status/navigation bar icons MUST be light (white), so isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides isDark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.background,
                contentColor = colorScheme.onSurface
            ) {
                CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface) {
                    content()
                }
            }
        }
    }
}

/**
 * Returns a high-contrast emerald color readable in both Dark and Light themes.
 */
@Composable
@ReadOnlyComposable
fun emeraldText(): Color = if (LocalIsDarkTheme.current) EmeraldLight else Color(0xFF059669)

/**
 * Applies a frosted glassmorphism surface card modifier with a subtle haze aesthetic.
 */
@Composable
fun Modifier.frostedGlassCard(
    cornerRadius: Dp = 20.dp,
    accentColor: Color = MaterialTheme.colorScheme.primary
): Modifier {
    val isDark = LocalIsDarkTheme.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(cornerRadius)
    val topColor = lerp(surfaceColor, accentColor, if (isDark) 0.05f else 0.03f)
    val bottomColor = lerp(surfaceColor, accentColor, if (isDark) 0.10f else 0.06f)
    val topBorder = lerp(borderColor, accentColor, if (isDark) 0.45f else 0.35f)

    return this
        .clip(shape)
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(topColor, bottomColor)
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(topBorder, borderColor)
            ),
            shape = shape
        )
}