package com.kakei.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode {
    DARK, LIGHT, SYSTEM
}

/** Design system corner radii: 12 / 16 / 20 / 24. */
val KakeiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Maps the Kakei tokens onto Material roles so stock components (dialogs, fields, switches) match. */
private fun KakeiColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = mint,
        onPrimary = mintInk,
        primaryContainer = mint.copy(alpha = 0.16f).compositeOver(surf),
        onPrimaryContainer = mint,
        secondary = mint,
        onSecondary = mintInk,
        secondaryContainer = surf2,
        onSecondaryContainer = text,
        tertiary = cyan,
        onTertiary = mintInk,
        tertiaryContainer = cyan.copy(alpha = 0.16f).compositeOver(surf),
        onTertiaryContainer = cyan,
        background = bg,
        onBackground = text,
        surface = surf,
        onSurface = text,
        surfaceVariant = surf2,
        onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surf,
        surfaceContainer = surf,
        surfaceContainerHigh = surf,
        surfaceContainerHighest = surf2,
        surfaceBright = surf2,
        surfaceDim = bg,
        outline = line,
        outlineVariant = line,
        error = coral,
        onError = Color.White,
        errorContainer = coral.copy(alpha = 0.16f).compositeOver(surf),
        onErrorContainer = coral,
        scrim = scrim
    )
}

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
    val colors = if (isDark) DarkKakeiColors else LightKakeiColors
    val colorScheme = colors.toColorScheme()

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

    CompositionLocalProvider(LocalKakeiColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = KakeiShapes
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.bg,
                contentColor = colors.text
            ) {
                CompositionLocalProvider(LocalContentColor provides colors.text) {
                    content()
                }
            }
        }
    }
}

/** Accessor for the Kakei design tokens, mirroring `MaterialTheme.colorScheme`. */
object KakeiTheme {
    val colors: KakeiColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKakeiColors.current
}

/**
 * Standard Kakei card: flat surface with a hairline border. Pass [borderColor] to highlight
 * (selected account, hero summaries) and [color] to tint the fill.
 */
@Composable
fun Modifier.kakeiCard(
    cornerRadius: Dp = 20.dp,
    color: Color = KakeiTheme.colors.surf,
    borderColor: Color = KakeiTheme.colors.line,
    borderWidth: Dp = 1.dp
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .background(color)
        .border(borderWidth, borderColor, shape)
}
