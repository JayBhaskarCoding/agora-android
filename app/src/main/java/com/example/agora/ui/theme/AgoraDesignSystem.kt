package com.example.agora.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ✦ AGORA NOIR — flagship design system for the home feed.
 *
 * A move away from translucent "glass everywhere" toward an editorial-luxury
 * language: deep obsidian canvases, solid sculpted cards with hairline borders
 * and a top specular sheen, cool pearl tones in light mode, and an
 * indigo → violet → fuchsia accent used sparingly.
 *
 * These tokens are consumed ONLY by the reimagined feed layer
 * (MainScreen / FeedScreen / PostMediaCarousel) — the global MaterialTheme is
 * intentionally left untouched so every other screen keeps rendering as before.
 */
@Immutable
data class AgoraColors(
    val isDark: Boolean,
    /** Full-screen canvas gradient (top → bottom). */
    val canvasTop: Color,
    val canvasBottom: Color,
    /** Ambient aurora washes floating behind the feed. */
    val auroraPrimary: Color,
    val auroraSecondary: Color,
    val auroraTertiary: Color,
    /** Solid, opaque card surface with hairline border + sheen. */
    val cardSurface: Color,
    val cardSheen: Color,
    val cardBorder: Color,
    val cardElevation: Dp,
    /** Hairline separators (action bar, dividers). */
    val hairline: Color,
    /** Inset chips: option buttons, search button, avatar placeholder. */
    val insetSurface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    /** Backing plate behind media while it loads. */
    val mediaPlate: Color,
    /** Scrim chips that float ON TOP of media (always dark). */
    val scrim: Color,
    val success: Color,
    val danger: Color
)

private val DarkAgoraColors = AgoraColors(
    isDark = true,
    canvasTop = Color(0xFF0B0C13),
    canvasBottom = Color(0xFF06070C),
    auroraPrimary = Color(0xFF6366F1),
    auroraSecondary = Color(0xFF8B5CF6),
    auroraTertiary = Color(0xFF22D3EE),
    cardSurface = Color(0xFF12141D),
    cardSheen = Color.White.copy(alpha = 0.045f),
    cardBorder = Color.White.copy(alpha = 0.09f),
    cardElevation = 0.dp,
    hairline = Color.White.copy(alpha = 0.07f),
    insetSurface = Color(0xFF1C1F2B),
    textPrimary = Color(0xFFF4F5FA),
    textSecondary = Color(0xFFA9AEC0),
    textTertiary = Color(0xFF6E7488),
    accent = Color(0xFF818CF8),
    accentSoft = Color(0xFF818CF8).copy(alpha = 0.14f),
    onAccent = Color(0xFFFFFFFF),
    mediaPlate = Color(0xFF0D0F17),
    scrim = Color.Black.copy(alpha = 0.45f),
    success = Color(0xFF34D399),
    danger = Color(0xFFF87171)
)

private val LightAgoraColors = AgoraColors(
    isDark = false,
    canvasTop = Color(0xFFF8F9FA),
    canvasBottom = Color(0xFFF1F2F4),
    auroraPrimary = Color(0xFFA5B4FC),
    auroraSecondary = Color(0xFF7DD3FC),
    auroraTertiary = Color(0xFFF0ABFC),
    cardSurface = Color(0xFFFFFFFF),
    cardSheen = Color.White.copy(alpha = 0.6f),
    cardBorder = Color(0xFFE5E5EA),
    cardElevation = 12.dp,
    hairline = Color(0xFFEBEBEF),
    insetSurface = Color(0xFFF1F2F4),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF5B5F66),
    textTertiary = Color(0xFF9A9DA6),
    accent = Color(0xFF4F46E5),
    accentSoft = Color(0xFF4F46E5).copy(alpha = 0.10f),
    onAccent = Color(0xFFFFFFFF),
    mediaPlate = Color(0xFFEDEEF1),
    scrim = Color(0xFF111318).copy(alpha = 0.42f),
    success = Color(0xFF059669),
    danger = Color(0xFFDC2626)
)

/**
 * Resolves the Noir palette for the active light/dark mode. Cheap and
 * remember-backed — safe to call in every component that needs it.
 */
@Composable
fun rememberAgoraColors(): AgoraColors {
    val isDark = LocalDarkTheme.current
    return remember(isDark) { if (isDark) DarkAgoraColors else LightAgoraColors }
}

/** The brand accent gradient — used for the create FAB and progress highlights. */
val AgoraAccentGradient: Brush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF6366F1),
        Color(0xFF8B5CF6),
        Color(0xFFD946EF)
    )
)

/**
 * Sweep-gradient ring reserved for the current user's avatar — a subtle
 * "this is you" signature that never shouts.
 */
val AgoraRingGradient: Brush = Brush.sweepGradient(
    colors = listOf(
        Color(0xFF6366F1),
        Color(0xFF22D3EE),
        Color(0xFFA855F7),
        Color(0xFFEC4899),
        Color(0xFF6366F1)
    )
)

/** Typography tokens — tight, high-contrast, editorial. */
object AgoraType {
    /** Screen wordmark ("agora."). */
    val Wordmark = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.8).sp
    )

    /** Post author display name. */
    val AuthorName = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    )

    /** @handle · timestamp sub-line. */
    val Meta = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp
    )

    /** Tiny wide-tracked uppercase labels ("AGORA MEMBER", "UPLOADING"…). */
    val MicroLabel = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp
    )

    /** Post caption body — fed to ExpandablePostText via LocalTextStyle. */
    val Body = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 23.sp,
        letterSpacing = 0.1.sp
    )

    /** Engagement counts beside action icons. */
    val Count = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp
    )

    /** Selected bottom-nav labels. */
    val NavLabel = TextStyle(
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp
    )
}
