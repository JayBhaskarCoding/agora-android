package net.agora.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.agora.app.report.ReportReasons
import net.agora.app.ui.theme.rememberAgoraColors

/**
 * ✦ REPORT POST — the one dialog, used by the feed, a profile grid and the
 * single-post page.
 *
 * ## Why this is a shared component
 *
 * This dialog previously existed as three hand-copied `AlertDialog`s (feed,
 * profile, single post) that were identical except for how they submitted. They
 * had already drifted from the spec in the one field the backend reads —
 * `"Hate Speech"` instead of `"Hate speech"` — which is exactly what three copies
 * of anything eventually do. Adding a conditional text field, a validation rule
 * and a legal notice to all three would have made three places to get the
 * explanation rule wrong, so the UI now lives here and the three call sites are
 * a few lines each.
 *
 * ## State ownership
 *
 * The selection and the explanation are `remember`ed *inside* this composable
 * rather than hoisted into the screens. The dialog only exists while a report is
 * in flight, so it leaves composition on dismiss and the next open starts from
 * "Spam" with an empty field. Before, `reportReason` lived on the screen and
 * survived dismissal, so reopening the dialog silently restored the previous
 * choice — including "Other" with no explanation, which would have opened the
 * dialog in an unsubmittable state.
 *
 * @param onDismiss invoked for Cancel, the back button and a scrim tap. The
 *   caller clears its `postToReport`, which is what actually dismisses the dialog.
 * @param onSubmit receives the **final** `reports.reason` string, already
 *   formatted by [ReportReasons.payloadFor] (`"Spam"`, `"Other: <text>"`, …).
 *   Callers must not re-format it; the wire format has exactly one definition.
 */
@Composable
fun ReportPostDialog(
    onDismiss: () -> Unit,
    onSubmit: (reason: String) -> Unit
) {
    val colors = rememberAgoraColors()

    var selectedReason by remember { mutableStateOf(ReportReasons.SPAM) }
    var explanation by remember { mutableStateOf("") }

    val needsExplanation = ReportReasons.requiresExplanation(selectedReason)
    // One source of truth for "can this be submitted": the button's enabled
    // state and the payload it would send can never disagree, because they are
    // the same call.
    val payload = ReportReasons.payloadFor(selectedReason, explanation)

    /**
     * Selecting a predefined reason clears the explanation.
     *
     * Not cosmetic: the field is hidden for the first four, so leaving the text
     * behind would hide a value that still exists in state — and switching back
     * to "Other" would then resurface stale text the user thought they had left.
     * Clearing on the way out means what you see is what will be submitted.
     */
    fun select(reason: String) {
        selectedReason = reason
        if (!ReportReasons.requiresExplanation(reason)) explanation = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = colors.cardSurface,
        title = {
            Text(
                text = "Report Post",
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
        },
        text = {
            // Scrollable because the explanation field and the notice can push
            // this past the dialog's height budget on a short device; without it
            // the Submit button would be pushed off-screen exactly when "Other"
            // is selected.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Why are you reporting this post?",
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                ReportReasons.ALL.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { select(reason) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedReason == reason,
                            onClick = { select(reason) },
                            colors = RadioButtonDefaults.colors(selectedColor = colors.danger)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(reason, fontSize = 15.sp, color = colors.textPrimary)
                    }
                }

                /* Only "Other" asks for words. The field grows in rather than
                   appearing, so the option list never jumps under the user's
                   finger between the tap and the release. */
                AnimatedVisibility(
                    visible = needsExplanation,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = explanation,
                        onValueChange = { explanation = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        label = { Text("Tell us what's wrong") },
                        placeholder = {
                            Text(
                                "Please describe the issue...",
                                color = colors.textTertiary
                            )
                        },
                        // Multi-line on purpose: a report is a sentence or three,
                        // and a single line hides most of what the user typed.
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            cursorColor = colors.accent,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardBorder,
                            focusedLabelColor = colors.accent,
                            unfocusedLabelColor = colors.textTertiary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                ContactDisclaimer()
            }
        },
        confirmButton = {
            AgoraPrimaryButton(
                onClick = { payload?.let(onSubmit) },
                enabled = payload != null
            ) {
                Text("Submit Report", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textTertiary)
            }
        }
    )
}

/**
 * The moderation-contact notice.
 *
 * A quiet inset panel rather than a line of body text: it is a legal-adjacent
 * statement the user should be able to find again, not part of the question, and
 * styling it like the option labels would make it read as a sixth choice. Low
 * contrast on a separate surface is the hierarchy — legible, never competing
 * with "Submit Report".
 */
@Composable
private fun ContactDisclaimer() {
    val colors = rememberAgoraColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.insetSurface)
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.height(16.dp).width(16.dp)
        )
        Text(
            text = ReportReasons.CONTACT_DISCLAIMER,
            color = colors.textTertiary,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
    }
}
