package net.agora.app.report

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guards on the Report Post flow.
 *
 * [ReportReasonsTest] covers the policy. This covers the *wiring*, because every
 * failure mode here is silent: the dialog still opens, still says "Report Post",
 * and still submits — it just submits the wrong reason, or skips the explanation
 * rule, or shows no notice. None of that is a compile error and none of it is
 * covered by a JVM test of the policy.
 *
 * The specific regression this locks down: the dialog existed as three
 * hand-copied `AlertDialog`s in the feed, the profile grid and the single-post
 * page. They had already drifted from the spec (`"Hate Speech"`), and a
 * conditional field added to two of three copies would have been invisible
 * until someone reported from the third screen.
 */
class ReportFlowContractTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate app/src/main from ${System.getProperty("user.dir")}")
    }

    private fun source(relativePath: String): String =
        File(repoRoot(), relativePath).readText()

    /** Every screen that can report a post. Adding a fourth belongs in this list. */
    private val reportingScreens = listOf(
        "app/src/main/java/net/agora/app/ui/FeedScreen.kt",
        "app/src/main/java/net/agora/app/ui/ProfileScreen.kt",
        "app/src/main/java/net/agora/app/ui/SinglePostScreen.kt"
    )

    private val dialogSource: String
        get() = source("app/src/main/java/net/agora/app/ui/components/ReportPostDialog.kt")

    /**
     * Drops `//` and block comments so a scan checks code rather than prose.
     *
     * Deliberately simple: it is only used on files where the literals under test
     * cannot legally contain `//` inside a string, so it cannot silently mangle a
     * value it is meant to inspect.
     */
    private fun codeOnly(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .lines()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker >= 0) line.substring(0, marker) else line
            }

    /**
     * Returns the exact source of a function, from its signature to its matching
     * closing brace.
     *
     * A "to the next @Composable" slice sweeps the following function into the
     * region under test, so an assertion about what a function does *not* read can
     * be satisfied by a neighbour rather than by the function itself.
     */
    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) return ""
        val open = text.indexOf('{', start)
        if (open < 0) return ""
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return ""
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    // ── one dialog, three call sites ────────────────────────────────────────

    @Test
    fun everyReportingScreenUsesTheSharedDialog() {
        reportingScreens.forEach { path ->
            val text = source(path)
            assertTrue(
                "$path does not call ReportPostDialog.",
                text.contains("ReportPostDialog(")
            )
            assertTrue(
                "$path must import the shared dialog.",
                text.contains("import net.agora.app.ui.components.ReportPostDialog")
            )
            // Exactly one: a second call would mean a screen kept a private copy.
            assertEquals(
                "$path calls ReportPostDialog more than once.",
                1,
                countOccurrences(text, "ReportPostDialog(")
            )
        }
    }

    @Test
    fun noScreenStillBuildsItsOwnReportDialog() {
        // The prompt text lives in the shared component now. If it reappears in a
        // screen, that screen has an inline copy again.
        reportingScreens.forEach { path ->
            assertFalse(
                "$path still contains an inline report dialog (" +
                    "\"Why are you reporting this post?\" belongs to ReportPostDialog only).",
                source(path).contains("Why are you reporting this post?")
            )
        }
    }

    @Test
    fun noScreenHoldsItsOwnReportReasonState() {
        // Hoisted reason/option state is what let the copies drift, and it also
        // leaked the previous selection into the next dialog open.
        reportingScreens.forEach { path ->
            val text = source(path)
            assertFalse(
                "$path still declares reportOptions; the five reasons come from ReportReasons.ALL.",
                text.contains("val reportOptions")
            )
            assertFalse(
                "$path still declares reportReason; selection belongs to the dialog.",
                text.contains("var reportReason")
            )
        }
    }

    // ── the option list has one definition ──────────────────────────────────

    @Test
    fun theReasonListIsNotRetypedOutsideItsOwner() {
        // A literal re-typed in a screen is a second definition of the five
        // reasons, and the one that silently wins when someone edits the other.
        val offenders = reportingScreens.filter { path ->
            val text = source(path)
            text.contains("\"Hate speech\"") || text.contains("\"Hate Speech\"")
        }
        assertTrue(
            "The reason labels are re-typed in $offenders. They belong in ReportReasons only.",
            offenders.isEmpty()
        )

        // Prose is allowed to name the mistake — ReportReasons' KDoc explains it.
        // What must not exist is the misspelling as a *value*.
        assertFalse(
            "The misspelling \"Hate Speech\" must not appear as a code value.",
            codeOnly(source("app/src/main/java/net/agora/app/report/ReportReasons.kt"))
                .contains("\"Hate Speech\"")
        )
    }

    @Test
    fun theDialogRendersTheReasonsFromTheSharedList() {
        val text = dialogSource
        assertTrue(
            "The option rows must iterate ReportReasons.ALL, not a local list.",
            text.contains("ReportReasons.ALL.forEach")
        )
    }

    // ── the conditional field and the disabled button ───────────────────────

    @Test
    fun theExplanationFieldIsConditional() {
        val text = dialogSource
        assertTrue(
            "The explanation field must sit inside an AnimatedVisibility so it is " +
                "revealed only for \"Other\".",
            text.contains("AnimatedVisibility(")
        )
        assertTrue(
            "The field's visibility must be driven by requiresExplanation.",
            text.contains("visible = needsExplanation") &&
                text.contains("requiresExplanation(selected)")
        )
    }

    // ── stable geometry: the "dialog jumps on every keystroke" bug ──────────

    /**
     * The field must have a CONSTANT height.
     *
     * This is the bug, stated as an assertion. `AlertDialog` sizes its card to its
     * content (its text slot is `weight(1f, fill = false)`), so a field that grows
     * a line at a time — `minLines = 3, maxLines = 6` — re-measured the whole card
     * on every wrap. A centred dialog grows in both directions, which is what read
     * on device as the page jumping up and down while typing.
     */
    @Test
    fun theExplanationFieldHasAConstantHeight() {
        val text = dialogSource
        assertTrue(
            "The explanation field must be given a fixed height.",
            text.contains(".height(ExplanationFieldHeight)")
        )
        // Code, not prose: the KDoc above ExplanationFieldHeight deliberately names
        // minLines when explaining why it was removed.
        assertFalse(
            "The field must NOT use minLines/maxLines — a growing field resizes the " +
                "whole dialog card on every line wrap.",
            codeOnly(text).contains("minLines")
        )
        assertTrue(
            "ExplanationFieldHeight must be declared as a fixed Dp constant.",
            text.contains("private val ExplanationFieldHeight = 132.dp")
        )
    }

    /** The card itself must be bounded, with the content scrolling inside it. */
    @Test
    fun theDialogBodyIsHeightCappedAndScrollable() {
        val text = dialogSource
        assertTrue(
            "The dialog body must be height-capped so content cannot stretch the card.",
            text.contains(".heightIn(max = DialogBodyMaxHeight)")
        )
        assertTrue(
            "The dialog body must scroll, so overflow moves content rather than the card.",
            text.contains(".verticalScroll(rememberScrollState())")
        )
        assertTrue(
            "DialogBodyMaxHeight must be declared.",
            text.contains("private val DialogBodyMaxHeight = 420.dp")
        )
        // The cap must be applied OUTSIDE the scroller, or it constrains the wrong node.
        val cap = text.indexOf(".heightIn(max = DialogBodyMaxHeight)")
        val scroll = text.indexOf(".verticalScroll(rememberScrollState())", cap)
        assertTrue(
            "heightIn must be applied before verticalScroll (on the scroll container's " +
                "own size), not to the unbounded content inside it.",
            scroll > cap && scroll - cap < 200
        )
    }

    // ── recomposition scoping ───────────────────────────────────────────────

    /**
     * Typing must not recompose the option list or the notice.
     *
     * The field takes the `MutableState` and reads `.value` itself. If it were
     * handed the value instead, the read would land in `ReportDialogBody` and every
     * character would recompose the five rows and the disclaimer too.
     */
    @Test
    fun onlyTheFieldReadsTheExplanationText() {
        val text = dialogSource

        assertTrue(
            "ExplanationField must take the MutableState, not a String value.",
            text.contains("explanation: MutableState<TextFieldValue>")
        )

        val body = functionBody(text, "private fun ReportDialogBody")
        assertTrue("ReportDialogBody not found.", body.isNotEmpty())

        assertFalse(
            "ReportDialogBody must not read the explanation text — that would make " +
                "every keystroke recompose the option rows and the notice.",
            body.contains("state.explanation.value")
        )
        assertTrue(
            "ReportDialogBody must pass the state holder down to the field.",
            body.contains("ExplanationField(state.explanation)")
        )
    }

    /**
     * The submit button must react to submittability, not to every character.
     *
     * The payload is a String? that changes on every keystroke; `enabled` is a
     * Boolean that only changes when the text crosses blank/non-blank. Deriving it
     * collapses a sentence's worth of keystrokes into at most one recomposition.
     */
    @Test
    fun submitEnabledStateIsDerivedNotRecomputedPerKeystroke() {
        val text = dialogSource
        assertTrue(
            "`enabled` must come from a derivedStateOf boolean, not a per-keystroke payload.",
            text.contains("derivedStateOf {") && text.contains("enabled = canSubmit")
        )
        assertTrue(
            "The derived value must be ReportReasons.canSubmit.",
            text.contains("ReportReasons.canSubmit(")
        )
        assertFalse(
            "The dialog must not compute a payload in its own body — that reads the " +
                "text state in the parent scope and defeats the scoping.",
            text.contains("val payload = ReportReasons.payloadFor")
        )
    }

    @Test
    fun submitIsDisabledUntilAPayloadExists() {
        val text = dialogSource
        assertTrue(
            "Submit must be enabled only when a payload exists.",
            text.contains("enabled = canSubmit")
        )
        assertTrue(
            "The payload must come from ReportReasons.payloadFor, which trims and " +
                "rejects blank text.",
            text.contains("ReportReasons.payloadFor(")
        )
        assertTrue(
            "onSubmit must receive the formatted payload, never the raw selection.",
            text.contains(")?.let(onSubmit)")
        )
        assertFalse(
            "onSubmit must not be handed the bare selected reason.",
            text.contains("onSubmit(selectedReason)")
        )
        assertFalse(
            "onSubmit must not be handed raw user text; it takes the formatted reason.",
            text.contains("onSubmit(state.explanation")
        )
    }

    @Test
    fun selectingAPredefinedReasonClearsTheExplanation() {
        assertTrue(
            "Switching away from \"Other\" must clear the explanation, or hidden text " +
                "survives and reappears when the user switches back.",
            dialogSource.contains("if (!ReportReasons.requiresExplanation(reason)) {")
        )
        assertTrue(
            "Clearing must reset the field to an empty TextFieldValue.",
            dialogSource.contains("explanation.value = TextFieldValue(\"\")")
        )
    }

    // ── the notice ──────────────────────────────────────────────────────────

    @Test
    fun theDialogShowsTheContactDisclaimer() {
        val text = dialogSource
        assertTrue(
            "The dialog must render ReportReasons.CONTACT_DISCLAIMER.",
            text.contains("ReportReasons.CONTACT_DISCLAIMER")
        )
        // The constant itself is word-for-word asserted in ReportReasonsTest;
        // this only proves the dialog actually shows it rather than defining it.
        assertFalse(
            "The notice must not be re-typed in the dialog — that is a second copy.",
            text.contains("moderation team may contact you")
        )
    }

    // ── the reason never bypasses the dialog's formatting ───────────────────

    @Test
    fun theViewModelStoresWhateverItIsHanded() {
        // reportPost must persist the string verbatim: all formatting happens
        // before it, in ReportReasons. If the ViewModel started rewriting the
        // reason, "Other: ..." would be mangled after the dialog already decided.
        val vm = source("app/src/main/java/net/agora/app/viewmodel/FeedViewModel.kt")
        assertTrue(
            "reportPost must insert post_id/reporter_id/reason.",
            vm.contains("\"post_id\" to postId") &&
                vm.contains("\"reporter_id\" to userId") &&
                vm.contains("\"reason\" to reason")
        )
        assertTrue(
            "reportPost must write to the `reports` table.",
            vm.contains("from(\"reports\")")
        )
    }
}
