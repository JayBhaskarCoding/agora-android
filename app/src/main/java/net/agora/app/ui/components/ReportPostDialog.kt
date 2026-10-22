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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.agora.app.report.ReportReasons
import net.agora.app.ui.theme.rememberAgoraColors

/**
 * Height cap for the scrolling dialog body.
 *
 * Upper bound only: `AlertDialog` measures its text slot with
 * `weight(1f, fill = false)` (verified in Material3's `AlertDialogContent`), so
 * the card still shrinks to fit a short screen or a raised keyboard. What this
 * does is stop the card from growing without limit as content is added.
 */
private val DialogBodyMaxHeight = 420.dp

/**
 * Fixed height of the explanation field — deliberately *not* `minLines`/`maxLines`.
 *
 * This one line is the fix for the reported jitter. A `minLines = 3, maxLines = 6`
 * field grows one line at a time as the text wraps; because `AlertDialog` sizes the
 * card to its content, every wrap re-measured the card, and a centred dialog grows
 * in *both* directions — the page appeared to jump up and down as the user typed.
 * A constant height means text is added without the field ever changing size: the
 * content scrolls inside it instead.
 */
private val ExplanationFieldHeight = 132.dp

/**
 * ✦ REPORT POST — the one dialog, used by the feed, a profile grid and the
 * single-post page.
 *
 * ## Why this is a shared component
 *
 * This dialog previously existed as three hand-copied `AlertDialog`s that were
 * identical except for how they submitted. They had already drifted from the spec
 * in the one field the backend reads (`"Hate Speech"` instead of `"Hate speech"`),
 * which is what three copies of anything eventually do, so the UI lives here and
 * the three call sites are a few lines each.
 *
 * ## Why typing no longer moves the dialog
 *
 * Two independent causes, both fixed:
 *
 *  1. **The card resized as the field grew.** `AlertDialog` has no fixed height;
 *     its text slot is `weight(1f, fill = false)`, so the card takes the height of
 *     its content. A `minLines`-based field therefore re-measured the entire card
 *     every time a line wrapped, and because the dialog is centred it grew up and
 *     down. The field now has a constant [ExplanationFieldHeight] and the body a
 *     [DialogBodyMaxHeight] cap, so adding text cannot change the card's size.
 *
 *  2. **Every keystroke recomposed the whole dialog.** The reason, the text and
 *     the derived payload were all read in one composable scope, so a single
 *     character re-ran the option rows, the disclaimer and the buttons. State now
 *     lives in [ReportFormState] and is read *only* by the leaf that displays it:
 *     [ReportDialogBody] reads the selection, [ExplanationField] reads the text,
 *     and [SubmitReportButton] reads a `derivedStateOf` boolean that only flips
 *     when submittability actually changes. Typing recomposes the text field.
 *
 * The body is also a `verticalScroll` container, so once the field and the notice
 * exceed the cap the content — not the card — is what moves.
 *
 * ## Keyboard
 *
 * No `imePadding()` here, and that is a deliberate, source-checked decision.
 * `AlertDialog` renders in its own window, and `DialogProperties.decorFitsSystemWindows`
 * defaults to `true`, which (per its documentation and the branch in
 * `AndroidDialog`) sets `SOFT_INPUT_ADJUST_UNSPECIFIED` so the *window* is resized
 * or panned by the platform — IME insets are consumed before they reach Compose.
 * An `imePadding()` inside the dialog would therefore measure 0 and do nothing.
 * Making it real requires `decorFitsSystemWindows = false`, which also changes the
 * window to non-floating, sets `ADJUST_NOTHING` on API 31+, and lets content draw
 * under the system bars (Material3's dialog does not pad for them) — a window-level
 * behaviour change that cannot be validated without a device, so it is not being
 * made to fix a bug whose cause was the layout, not the keyboard.
 *
 * @param onDismiss invoked for Cancel, the back button and a scrim tap. The caller
 *   clears its `postToReport`, which is what actually dismisses the dialog.
 * @param onSubmit receives the **final** `reports.reason` string, already formatted
 *   by [ReportReasons.payloadFor] (`"Spam"`, `"Other: <text>"`, …). Callers must
 *   not re-format it; the wire format has exactly one definition.
 */
@Composable
fun ReportPostDialog(
    onDismiss: () -> Unit,
    onSubmit: (reason: String) -> Unit
) {
    val colors = rememberAgoraColors()
    // One holder for the whole form, so the three reads below stay separate and a
    // keystroke touches exactly one of them.
    val state = remember { ReportFormState() }

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
        text = { ReportDialogBody(state) },
        confirmButton = { SubmitReportButton(state, onSubmit) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textTertiary)
            }
        }
    )
}

/**
 * The mutable form, held together so reads can be confined to the composables
 * that need them.
 *
 * `var x by remember { mutableStateOf(…) }` inside the dialog body would work, but
 * it reads through a delegate at the point of *call*, which puts every read in the
 * parent scope — exactly the recomposition-everything behaviour this class exists
 * to avoid. Holding `MutableState` allows passing it down and reading it in the
 * leaf.
 *
 * Stable because every field is a `val` holding a `MutableState`; there is no
 * mutable Kotlin property for Compose to be conservative about.
 */
@Stable
private class ReportFormState {
    val selectedReason = mutableStateOf(ReportReasons.SPAM)

    /**
     * `TextFieldValue`, not `String`: it carries the selection and composition
     * ranges, so IME composition (which matters for any language that composes
     * rather than committing per key) is not reset on every change.
     */
    val explanation = mutableStateOf(TextFieldValue(""))

    /**
     * Selecting a predefined reason clears the explanation.
     *
     * Not cosmetic: the field is hidden for the first four, so leaving the text
     * behind would hide a value that still exists in state — and switching back to
     * "Other" would then resurface stale text the user thought they had left.
     * Clearing on the way out means what you see is what will be submitted.
     */
    fun select(reason: String) {
        selectedReason.value = reason
        if (!ReportReasons.requiresExplanation(reason)) {
            explanation.value = TextFieldValue("")
        }
    }
}

/**
 * The scrolling body: header, the five options, the conditional field, the notice.
 *
 * Reads the *selection* only. It never reads the explanation text, so typing does
 * not recompose this function or the option rows inside it.
 */
@Composable
private fun ReportDialogBody(state: ReportFormState) {
    val colors = rememberAgoraColors()
    val selected = state.selectedReason.value
    val needsExplanation = ReportReasons.requiresExplanation(selected)

    Column(
        modifier = Modifier
            // heightIn BEFORE verticalScroll: the cap is applied to the scroll
            // container's own size, so content taller than the cap scrolls instead
            // of stretching the card.
            .heightIn(max = DialogBodyMaxHeight)
            .verticalScroll(rememberScrollState())
    ) {
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
                    .clickable { state.select(reason) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == reason,
                    onClick = { state.select(reason) },
                    colors = RadioButtonDefaults.colors(selectedColor = colors.danger)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(reason, fontSize = 15.sp, color = colors.textPrimary)
            }
        }

        /* Only "Other" asks for words. The field grows in rather than appearing, so
           the option list never jumps under the user's finger between the tap and
           the release. Its height is fixed, so nothing resizes once it is open. */
        AnimatedVisibility(
            visible = needsExplanation,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ExplanationField(state.explanation)
        }

        Spacer(modifier = Modifier.height(16.dp))
        ContactDisclaimer()
    }
}

/**
 * The explanation field — the only composable that reads the text.
 *
 * Takes the `MutableState` rather than a `String` + callback so the `.value` read
 * happens *here*. Passing a value would move the read up into [ReportDialogBody],
 * and every character would then recompose the option list and the notice too.
 */
@Composable
private fun ExplanationField(explanation: MutableState<TextFieldValue>) {
    val colors = rememberAgoraColors()

    OutlinedTextField(
        value = explanation.value,
        onValueChange = { explanation.value = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            // Fixed, not minLines/maxLines: a growing field resizes the dialog card
            // on every line wrap, which is the reported jump. Long text scrolls
            // inside this box instead.
            .height(ExplanationFieldHeight),
        label = { Text("Tell us what's wrong") },
        placeholder = {
            Text(
                "Please describe the issue...",
                color = colors.textTertiary
            )
        },
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

/**
 * Submit — the only composable that reads the payload.
 *
 * `derivedStateOf` matters here beyond tidiness: the payload is a `String?` that
 * changes on every keystroke, but the *button's state* only changes when the text
 * crosses between blank and non-blank. Deriving the boolean collapses a whole
 * sentence's worth of keystrokes into at most one recomposition of the button.
 *
 * The click handler reads the live values directly rather than closing over the
 * derived one — a click is not a composition, so reading there subscribes to
 * nothing and cannot submit a stale payload.
 */
@Composable
private fun SubmitReportButton(
    state: ReportFormState,
    onSubmit: (reason: String) -> Unit
) {
    val canSubmit by remember(state) {
        derivedStateOf {
            ReportReasons.canSubmit(
                reason = state.selectedReason.value,
                explanation = state.explanation.value.text
            )
        }
    }

    AgoraPrimaryButton(
        onClick = {
            // Computed at click time, from the same function that drives `enabled`,
            // so the value sent is the value the button was enabled for.
            ReportReasons.payloadFor(
                reason = state.selectedReason.value,
                explanation = state.explanation.value.text
            )?.let(onSubmit)
        },
        enabled = canSubmit
    ) {
        Text("Submit Report", fontWeight = FontWeight.Bold)
    }
}

/**
 * The moderation-contact notice.
 *
 * A quiet inset panel rather than a line of body text: it is a legal-adjacent
 * statement the user should be able to find again, not part of the question, and
 * styling it like the option labels would make it read as a sixth choice. Low
 * contrast on a separate surface is the hierarchy — legible, never competing with
 * "Submit Report".
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
