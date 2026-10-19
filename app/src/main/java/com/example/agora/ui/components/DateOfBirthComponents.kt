package com.example.agora.ui.components

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agora.utils.DateOfBirth
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

private val MinorBannerAmber = Color(0xFFF59E0B)
private val MinorBannerSurface = Color(0xF21A1710)
private val MinorBannerTitle = Color(0xFFFFD48A)
private val MinorBannerBody = Color(0xFFF7EDDC)

/**
 * 🌟 Under-18 notice shown directly below the Date of Birth field.
 *
 * Informational only: it never blocks registration. Styled as a dark,
 * translucent glass card with an amber hairline + warning icon so it reads as
 * a caution without competing with the form's primary action.
 *
 * [visible] MUST already be false for blank/invalid DOBs — every caller derives
 * it from [DateOfBirth.shouldShowMinorNotice], which requires a parseable date.
 */
@Composable
fun MinorAccountWarningBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
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
            color = MinorBannerSurface,
            border = BorderStroke(1.dp, MinorBannerAmber.copy(alpha = 0.55f)),
            shadowElevation = 8.dp
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
                        .background(MinorBannerAmber.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = MinorBannerAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(11.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Minor account notice (Under 18)",
                        color = MinorBannerTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.1.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Because you are under 18, certain features, content, " +
                            "and social interactions may be restricted to help keep " +
                            "your experience safe.",
                        color = MinorBannerBody,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}
