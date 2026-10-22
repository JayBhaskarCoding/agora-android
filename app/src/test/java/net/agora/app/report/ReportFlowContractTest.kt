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
                text.contains("requiresExplanation(selectedReason)")
        )
        assertTrue(
            "The explanation field must be multi-line.",
            text.contains("minLines = 3")
        )
    }

    @Test
    fun submitIsDisabledUntilAPayloadExists() {
        val text = dialogSource
        assertTrue(
            "Submit must be enabled only when a payload exists.",
            text.contains("enabled = payload != null")
        )
        assertTrue(
            "The payload must come from ReportReasons.payloadFor, which trims and " +
                "rejects blank text.",
            text.contains("ReportReasons.payloadFor(selectedReason, explanation)")
        )
        assertTrue(
            "onSubmit must receive the formatted payload, never the raw selection.",
            text.contains("onClick = { payload?.let(onSubmit) }")
        )
        assertFalse(
            "onSubmit must not be handed the bare selected reason.",
            text.contains("onSubmit(selectedReason)")
        )
    }

    @Test
    fun selectingAPredefinedReasonClearsTheExplanation() {
        assertTrue(
            "Switching away from \"Other\" must clear the explanation, or hidden text " +
                "survives and reappears when the user switches back.",
            dialogSource.contains("if (!ReportReasons.requiresExplanation(reason)) explanation = \"\"")
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
