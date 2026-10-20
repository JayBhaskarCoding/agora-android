package net.in.agora.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.in.agora.ui.theme.LocalDarkTheme
import net.in.agora.utils.DateOfBirth
import java.time.LocalDate

/**
 * 🌟 Material 3 date-picker guard for Date of Birth.
 *
 * The picker's calendar and year list consult this object, so dates outside
 * `[today - 120 years, today]` are rendered as non-selectable. The screen also
 * passes the same bounds as `yearRange`, which stops the calendar from paging
 * into years that contain no valid date at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Immutable
class DobSelectableDates(
    private val oldestAllowed: LocalDate = DateOfBirth.oldestAllowedDate(),
    private val newestAllowed: LocalDate = DateOfBirth.newestAllowedDate()
) : SelectableDates {

    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val date = DateOfBirth.fromUtcMillis(utcTimeMillis)
        return !date.isBefore(oldestAllowed) && !date.isAfter(newestAllowed)
    }

    override fun isSelectableYear(year: Int): Boolean =
        year in oldestAllowed.year..newestAllowed.year
}

/**
 * 🌟 CAUTION PALETTE — resolved per theme.
 *
 * The banner used to be a fixed dark card (`#1A1710` surface, cream text) that
 * rendered identically in both modes. On the pearl canvas that is a black slab
 * with a glowing amber halo: legible, but it is the only element on the screen
 * that belongs to another theme, so it reads as a bug next to the light cards,
 * fields and hairlines around it.
 *
 * Amber is not a Material 3 colour role in this app (tertiary is cyan), so the
 * two ramps are declared here, mirroring how `authSubtitleColor(isDark)` and
 * `agoraAuthFieldColors(isDark)` already handle the light/dark split:
 *
 * | token  | dark      | light     | notes                                   |
 * |--------|-----------|-----------|-----------------------------------------|
 * | surface| `#1A1710` | `#FFF7E6` | 95% black glass → warm cream wash       |
 * | border | amber     | amber-700 | hairline at 55% / 45%                   |
 * | title  | `#FFD48A` | `#92400E` | amber-800 → 6.7:1 on the cream          |
 * | body   | `#F7EDDC` | `#5A4A32` | warm ink → 8.1:1 on the cream           |
 * | shadow | 8dp       | 2dp       | a cream block must not cast a black box |
 */
@Immutable
private data class MinorBannerPalette(
    val surface: Color,
    val accent: Color,
    val title: Color,
    val body: Color,
    val shadowElevation: Dp
)

private val MinorBannerPaletteDark = MinorBannerPalette(
    surface = Color(0xF21A1710),              // translucent obsidian glass
    accent = Color(0xFFF59E0B),               // amber-500
    title = Color(0xFFFFD48A),
    body = Color(0xFFF7EDDC),
    shadowElevation = 8.dp
)

private val MinorBannerPaletteLight = MinorBannerPalette(
    surface = Color(0xFFFFF7E6),              // warm cream, sits under pearl
    accent = Color(0xFFB45309),               // amber-700
    title = Color(0xFF92400E),                // amber-800
    body = Color(0xFF5A4A32),                 // warm ink
    shadowElevation = 2.dp
)

/** Picks the palette for the active theme; [isDark] can be forced in previews. */
@Composable
private fun minorBannerPalette(isDark: Boolean = LocalDarkTheme.current): MinorBannerPalette =
    if (isDark) MinorBannerPaletteDark else MinorBannerPaletteLight

/**
 * 🌟 Under-18 notice shown directly below the Date of Birth field.
 *
 * Informational only: it never blocks registration. Reads as a caution without
 * competing with the form's primary action — a tinted card with an amber
 * hairline and warning icon, warm cream on light canvases and translucent glass
 * on dark ones (see [MinorBannerPalette]).
 *
 * [visible] MUST already be false for blank/invalid DOBs — every caller derives
 * it from [DateOfBirth.shouldShowMinorNotice], which requires a parseable date.
 */
@Composable
fun MinorAccountWarningBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
    isDark: Boolean = LocalDarkTheme.current
) {
    val palette = minorBannerPalette(isDark)

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = palette.surface,
            border = BorderStroke(
                1.dp,
                palette.accent.copy(alpha = if (isDark) 0.55f else 0.45f)
            ),
            shadowElevation = palette.shadowElevation
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        // Light mode keeps a LOWER alpha plate so the cream
                        // stays warm instead of going orange; amber-700 on the
                        // resulting #F4E0C7 still clears 3:1 for the icon.
                        .background(palette.accent.copy(alpha = if (isDark) 0.16f else 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(11.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Minor account notice (Under 18)",
                        color = palette.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.1.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Because you are under 18, certain features, content, " +
                            "and social interactions may be restricted to help keep " +
                            "your experience safe.",
                        color = palette.body,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}
