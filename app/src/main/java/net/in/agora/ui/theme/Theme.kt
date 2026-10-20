package net.in.agora.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import net.in.agora.data.ThemePreference

val LocalDarkTheme = staticCompositionLocalOf { false }

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
    // 🌟 Outline is the border token for inputs/dividers. The old #D9DAE0 was
    //    almost invisible on the pearl canvas; NoirOutlineLight keeps a ~3:1 boundary.
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

/**
 * Theme entry point for a stored [ThemePreference].
 *
 * [ThemePreference.SYSTEM] follows the device; LIGHT/DARK are explicit
 * overrides. This is the overload every caller should prefer.
 */
@Composable
fun AgoraTheme(
    themePreference: ThemePreference,
    content: @Composable () -> Unit
) {
    AgoraTheme(
        darkTheme = themePreference.resolveIsDark(isSystemInDarkTheme()),
        content = content
    )
}

/**
 * Theme entry point for an already-resolved dark flag.
 *
 * Also keeps the system bars in sync: the status/navigation bar ICON colour is
 * re-applied on every change of [darkTheme] (including OS theme changes while
 * the app is in the foreground) so icons never clash with the background.
 */
@Composable
fun AgoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        // Keyed on [darkTheme] so the window is touched only when the resolved
        // theme actually changes, not on every recomposition.
        LaunchedEffect(darkTheme) {
            // Dialogs/previews can wrap the view context — walk the chain instead
            // of assuming LocalView.context is the Activity itself.
            val window = view.context.findActivity()?.window ?: return@LaunchedEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme

            // Keep the window background in lockstep with the resolved theme so
            // rotation/recreation never flashes the opposite canvas colour.
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(
                    (if (darkTheme) 0xFF0B0C13 else 0xFFF8F9FA).toInt()
                )
            )
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

/** Unwraps [ContextWrapper]s until the hosting [Activity] is found. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
