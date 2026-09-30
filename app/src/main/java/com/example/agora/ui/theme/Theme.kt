package com.example.agora.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.agora.viewmodel.ThemePreference

val LocalDarkTheme = staticCompositionLocalOf { true }

// ✦ Noir corner-radius scale (shared by Shapes + explicit screen styling)
private val Shape8 = RoundedCornerShape(8.dp)
private val Shape12 = RoundedCornerShape(12.dp)
private val Shape16 = RoundedCornerShape(16.dp)
private val Shape22 = RoundedCornerShape(22.dp)
private val Shape28 = RoundedCornerShape(28.dp)

// ✦ AGORA NOIR — the global MaterialTheme now speaks the same language as the
//   feed design system: obsidian canvases, solid graphite surfaces, cool pearl
//   light mode, hairline outlines and the brand indigo accent.
private val LightColorScheme = lightColorScheme(
    primary = AgoraIndigo,
    onPrimary = White,
    primaryContainer = Color(0xFFEEF2FF),
    onPrimaryContainer = Color(0xFF3730A3),
    secondary = NoirSecondaryLight,
    onSecondary = White,
    secondaryContainer = NoirSecondaryContainerLight,
    onSecondaryContainer = NoirOnSecondaryContainerLight,
    tertiary = NoirTertiaryLight,
    onTertiary = White,
    tertiaryContainer = NoirTertiaryContainerLight,
    onTertiaryContainer = NoirOnTertiaryContainerLight,
    background = NoirCanvasLight,
    onBackground = NoirInkLight,
    surface = White,
    onSurface = NoirInkLight,
    surfaceVariant = NoirInsetLight,
    onSurfaceVariant = NoirTextSecondaryLight,
    surfaceContainerLowest = White,
    surfaceContainerLow = NoirContainerLowLight,
    surfaceContainer = NoirCanvasLight,
    surfaceContainerHigh = NoirContainerHighLight,
    surfaceContainerHighest = NoirContainerHighestLight,
    error = NoirDangerLight,
    onError = White,
    outline = NoirOutlineLight,
    outlineVariant = NoirHairlineLight
)

private val DarkColorScheme = darkColorScheme(
    primary = AgoraIndigoLight,
    onPrimary = White,
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = NoirSecondaryDark,
    onSecondary = Color(0xFF1B1430),
    secondaryContainer = NoirSecondaryContainerDark,
    onSecondaryContainer = NoirOnSecondaryContainerDark,
    tertiary = NoirTertiaryDark,
    onTertiary = Color(0xFF062830),
    tertiaryContainer = NoirTertiaryContainerDark,
    onTertiaryContainer = NoirOnTertiaryContainerDark,
    background = NoirCanvasDark,
    onBackground = NoirOnSurfaceDark,
    surface = NoirSurfaceDark,
    onSurface = NoirOnSurfaceDark,
    surfaceVariant = NoirInsetDark,
    onSurfaceVariant = NoirTextSecondaryDark,
    surfaceContainerLowest = NoirCanvasDarkDeep,
    surfaceContainerLow = NoirContainerLowDark,
    surfaceContainer = NoirSurfaceDark,
    surfaceContainerHigh = NoirContainerHighDark,
    surfaceContainerHighest = NoirContainerHighestDark,
    error = AgoraRed,
    onError = White,
    outline = NoirOutlineDark,
    outlineVariant = NoirHairlineDark
)

// ✦ Noir corner language: matches the feed's card/plate/chip radii so every
//   M3 component (dialogs, sheets, date pickers) inherits the same silhouettes.
private val AgoraShapes = Shapes(
    extraSmall = Shape8,
    small = Shape12,
    medium = Shape16,
    large = Shape22,
    extraLarge = Shape28
)

@Composable
fun AgoraTheme(
    themePreference: ThemePreference,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    AgoraTheme(darkTheme = darkTheme, content = content)
}

@Composable
fun AgoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    // 🌟 System Bar Insets Controller: Sets dark status bar icons in Light Mode, white in Dark Mode
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AgoraShapes,
            content = content
        )
    }
}
