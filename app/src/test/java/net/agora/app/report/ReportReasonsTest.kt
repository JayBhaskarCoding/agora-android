package net.agora.app.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `reports.reason` wire format, pinned.
 *
 * Everything asserted here is a value a moderator or a query depends on, so it is
 * a contract rather than an implementation detail:
 *
 *  - the five labels are spelled exactly as specified (`"Hate speech"`, not
 *    `"Hate Speech"` — the app shipped the latter);
 *  - the four predefined reasons are stored verbatim, so they stay groupable;
 *  - "Other" is stored as `"Other: <trimmed text>"`;
 *  - blank explanations are never submittable, and never reach the string.
 *
 * Pure JVM: no Android, no Robolectric.
 */
class ReportReasonsTest {

    // ── the five reasons ────────────────────────────────────────────────────

    @Test
    fun theFiveReasonsAreExactlyAsSpecified() {
        assertEquals(
            listOf("Spam", "Harassment", "Hate speech", "Misinformation", "Other"),
            ReportReasons.ALL
        )
    }

    @Test
    fun hateSpeechIsSentenceCase() {
        // The regression: "Hate Speech" was shipped by all three dialog copies.
        // A capital S here is a different database value, not a cosmetic variant.
        assertEquals("Hate speech", ReportReasons.HATE_SPEECH)
        assertFalse(ReportReasons.ALL.contains("Hate Speech"))
    }

    @Test
    fun theOptionListHasNoDuplicatesAndEndsWithOther() {
        assertEquals(ReportReasons.ALL.size, ReportReasons.ALL.distinct().size)
        assertEquals(5, ReportReasons.ALL.size)
        assertEquals(ReportReasons.OTHER, ReportReasons.ALL.last())
    }

    // ── predefined reasons ──────────────────────────────────────────────────

    @Test
    fun predefinedReasonsAreStoredVerbatim() {
        listOf(
            ReportReasons.SPAM,
            ReportReasons.HARASSMENT,
            ReportReasons.HATE_SPEECH,
            ReportReasons.MISINFORMATION
        ).forEach { reason ->
            assertEquals(
                "A predefined reason must reach the database unchanged.",
                reason,
                ReportReasons.payloadFor(reason, explanation = "")
            )
        }
    }

    @Test
    fun predefinedReasonsSubmitWithoutAnExplanation() {
        // "The user can immediately tap Submit Report" — an empty text field
        // must not block the first four options.
        listOf(
            ReportReasons.SPAM,
            ReportReasons.HARASSMENT,
            ReportReasons.HATE_SPEECH,
            ReportReasons.MISINFORMATION
        ).forEach { reason ->
            assertTrue(ReportReasons.canSubmit(reason, explanation = ""))
        }
    }

    @Test
    fun explanationIsIgnoredForPredefinedReasons() {
        // Leftover text must never be appended to a predefined reason.
        assertEquals(
            ReportReasons.SPAM,
            ReportReasons.payloadFor(ReportReasons.SPAM, explanation = "  something  ")
        )
    }

    @Test
    fun onlyOtherRequiresAnExplanation() {
        ReportReasons.ALL.forEach { reason ->
            assertEquals(reason == ReportReasons.OTHER, ReportReasons.requiresExplanation(reason))
        }
    }

    // ── Other ───────────────────────────────────────────────────────────────

    @Test
    fun otherIsPrefixedWithItsOwnName() {
        assertEquals(
            "Other: the caption is a scam",
            ReportReasons.payloadFor(ReportReasons.OTHER, "the caption is a scam")
        )
    }

    @Test
    fun otherPrefixMatchesTheConstantAndTheReasonName() {
        // Guards two kinds of drift at once: a typo in the literal prefix, and a
        // rename of OTHER that leaves the prefix — and therefore every stored
        // row's shape — behind.
        assertEquals("${ReportReasons.OTHER}: ", ReportReasons.OTHER_PREFIX)
        assertTrue(ReportReasons.payloadFor(ReportReasons.OTHER, "x")!!.startsWith(ReportReasons.OTHER_PREFIX))
    }

    @Test
    fun otherWithBlankExplanationIsNotSubmittable() {
        listOf("", " ", "   ", "\n", "\t", " \n\t ").forEach { blank ->
            assertNull(
                "A whitespace-only explanation must not produce a payload.",
                ReportReasons.payloadFor(ReportReasons.OTHER, blank)
            )
            assertFalse(ReportReasons.canSubmit(ReportReasons.OTHER, blank))
        }
    }

    @Test
    fun otherWithTextBecomesSubmittable() {
        assertTrue(ReportReasons.canSubmit(ReportReasons.OTHER, "a"))
        assertTrue(ReportReasons.canSubmit(ReportReasons.OTHER, "  a  "))
    }

    @Test
    fun otherIsTrimmedExactlyOnce() {
        // Leading/trailing whitespace, including newlines from the multi-line
        // field, must not survive into the stored reason.
        assertEquals(
            "Other: spam link",
            ReportReasons.payloadFor(ReportReasons.OTHER, "\n   spam link   \n")
        )
    }

    @Test
    fun interiorWhitespaceIsPreserved() {
        // Only the edges are trimmed: the user's own line breaks and spacing are
        // the explanation, and collapsing them would edit what they wrote.
        assertEquals(
            "Other: line one\nline two",
            ReportReasons.payloadFor(ReportReasons.OTHER, "  line one\nline two  ")
        )
    }

    @Test
    fun otherTextContainingAColonOrDefaultsIsNotReformatted() {
        // Someone may well type "Other: ..." or their own prefix; it is data,
        // not a directive. Exactly one prefix is added.
        assertEquals(
            "Other: Other: already prefixed",
            ReportReasons.payloadFor(ReportReasons.OTHER, "Other: already prefixed")
        )
    }

    // ── rejections ──────────────────────────────────────────────────────────

    @Test
    fun unknownReasonsAreRejected() {
        listOf("", "spam", "SPAM", "Hate Speech", "other", "Anything").forEach { bogus ->
            assertNull(
                "'$bogus' is not one of the five reasons and must not be storable.",
                ReportReasons.payloadFor(bogus, "text")
            )
            assertFalse(ReportReasons.canSubmit(bogus, "text"))
        }
    }

    @Test
    fun canSubmitAgreesWithPayloadForEveryReason() {
        // The button's enabled state and the value it would send are derived
        // from the same function, so they cannot disagree — asserted, not assumed.
        ReportReasons.ALL.forEach { reason ->
            listOf("", "   ", "real text").forEach { text ->
                assertEquals(
                    ReportReasons.payloadFor(reason, text) != null,
                    ReportReasons.canSubmit(reason, text)
                )
            }
        }
    }

    // ── the notice ──────────────────────────────────────────────────────────

    @Test
    fun theContactDisclaimerIsWordForWord() {
        assertEquals(
            "Note: If you submit a report, our moderation team may contact you via " +
                "the email address registered to your Agora account if additional " +
                "details are needed.",
            ReportReasons.CONTACT_DISCLAIMER
        )
    }

    @Test
    fun theDisclaimerIsASingleSentenceWithNoStrayWhitespace() {
        // It is built from three concatenated strings; a missed or doubled space
        // at a join is invisible in review but wrong on screen.
        val text = ReportReasons.CONTACT_DISCLAIMER
        assertFalse(text.contains("  "))
        assertFalse(text.contains(" ."))
        assertTrue(text.startsWith("Note: "))
        assertTrue(text.endsWith("needed."))
        assertEquals(1, text.count { it == ':' })
    }
}
