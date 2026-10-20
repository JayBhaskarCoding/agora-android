package com.example.agora.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.agora.ui.theme.LocalDarkTheme

/**
 * ✦ CRISP WORDMARK
 *
 * The auth screens used to paint `agora` with a gradient going all the way down
 * to 66% alpha `onBackground`. On dark surfaces that reads as a soft glow; on
 * light surfaces the tail of the word dissolved into the pearl background.
 *
 * Light mode now renders a solid, crisp ink wordmark (only a barely-there 0.88
 * tail for depth); dark mode keeps the original glow.
 */
fun agoraWordmark(
    isDark: Boolean,
    text: String = "agora",
    accent: Color
) = buildAnnotatedString {
    val ink = if (isDark) Color(0xFFF4F5FA) else Color(0xFF14151C)
    withStyle(
        SpanStyle(
            brush = Brush.linearGradient(
                colors = listOf(
                    ink,
                    ink.copy(alpha = if (isDark) 0.66f else 0.88f)
                )
            ),
            fontWeight = FontWeight.Black
        )
    ) {
        append(text)
    }
    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Black)) {
        append(".")
    }
}

/** Convenience overload resolving the palette from the active theme. */
@Composable
fun rememberAgoraWordmark(text: String = "agora") = agoraWordmark(
    isDark = LocalDarkTheme.current,
    text = text,
    accent = MaterialTheme.colorScheme.primary
)

/**
 * 🌟 AUTH FIELD COLOURS.
 *
 * In Light Mode the previous transparent containers + `outline @ 40%` borders
 * made inputs read as floating, washed-out labels on the card. Light mode now
 * gets its own inset fill, a solid 1dp boundary at rest and a legible label;
 * Dark Mode keeps the transparent glass look.
 */
@Composable
fun agoraAuthFieldColors(isDark: Boolean): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    // 🌟 OutlinedTextFieldDefaults.colors() is itself @Composable, so it cannot be
    //    invoked inside remember {} — build the (already cheap) value directly and
    //    let Compose skip recomposition when the inputs are unchanged.
    return if (isDark) {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline.copy(alpha = 0.55f),
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            unfocusedLeadingIconColor = scheme.onSurfaceVariant,
            unfocusedTrailingIconColor = scheme.onSurfaceVariant,
            focusedTextColor = scheme.onSurface,
            unfocusedTextColor = scheme.onSurface
        )
    } else {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline,
            focusedContainerColor = scheme.surface,
            unfocusedContainerColor = Color(0xFFF4F5F8),
            unfocusedLabelColor = Color(0xFF474C57),
            focusedLabelColor = scheme.primary,
            unfocusedLeadingIconColor = Color(0xFF474C57),
            focusedLeadingIconColor = scheme.primary,
            unfocusedTrailingIconColor = Color(0xFF474C57),
            focusedTrailingIconColor = scheme.primary,
            unfocusedTextColor = Color(0xFF14151C),
            focusedTextColor = Color(0xFF14151C),
            unfocusedPlaceholderColor = Color(0xFF6B7079),
            cursorColor = scheme.primary
        )
    }
}

/** Shared subtitle colour for the auth marketing lines. */
@Composable
fun authSubtitleColor(isDark: Boolean): Color =
    if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF474C57)

/** Sizes used by both auth wordmarks. */
object AuthWordmarkSizes {
    val Login: TextUnit = 44.sp
    val Register: TextUnit = 40.sp
}
