package net.agora.app.report

/**
 * ✦ REPORT POLICY — the five reasons, and the exact string that reaches Supabase.
 *
 * Pure Kotlin: no Compose, no Android. That is deliberate, because the part of
 * "Report Post" that can actually be wrong is not the dialog — it is the value
 * written into `reports.reason`. A formatting bug there is silent: the row
 * inserts, the user sees "Report submitted. Thank you.", and moderation gets a
 * reason it cannot triage. [ReportPostDialog] is the thin UI over this; these
 * rules are unit tested in `ReportReasonsTest`.
 *
 * ## The wire format
 *
 * `reports` has `id`, `post_id`, `reporter_id`, `reason`, `created_at`, and
 * `reason` is a single free-text column. The four predefined reasons are stored
 * verbatim, so they stay groupable (`WHERE reason = 'Spam'`). "Other" is stored
 * as `"Other: <explanation>"`, which keeps the *fact that Other was chosen*
 * queryable by prefix while preserving the user's own words:
 *
 * ```
 *   "Spam"                            -> reason = "Spam"
 *   "Other" + "the caption is a scam" -> reason = "Other: the caption is a scam"
 * ```
 *
 * Storing the bare explanation would lose the distinction between a predefined
 * reason and a custom one; storing only "Other" would throw the explanation
 * away. The prefix costs one `startsWith("Other: ")` and keeps both.
 *
 * ## Why the strings are spelled out rather than derived
 *
 * [HATE_SPEECH] is `"Hate speech"`, not `"Hate Speech"`. The app previously
 * shipped the title-case spelling, which does not match the spec and would not
 * match any existing rows already stored under the other one. Down-casing the
 * "s" is a data change, not a cosmetic one, and is pinned by a test.
 */
object ReportReasons {

    const val SPAM = "Spam"
    const val HARASSMENT = "Harassment"
    const val HATE_SPEECH = "Hate speech"
    const val MISINFORMATION = "Misinformation"
    const val OTHER = "Other"

    /**
     * Display order, and the complete set of accepted reasons. Iterating this
     * list is what keeps the dialog's options and [payloadFor]'s allow-list from
     * drifting apart — there is exactly one definition of "the five reasons".
     *
     * The first four submit immediately; only [OTHER] opens the explanation field.
     */
    val ALL: List<String> = listOf(SPAM, HARASSMENT, HATE_SPEECH, MISINFORMATION, OTHER)

    /**
     * Written literally, not templated from [OTHER], so it cannot silently
     * become something else. `ReportReasonsTest` asserts it still equals
     * `"$OTHER: "`, which catches drift without putting a compile-time template
     * in the one string the backend depends on.
     */
    const val OTHER_PREFIX = "Other: "

    /**
     * Shown in the dialog above the action buttons.
     *
     * Exact wording is a requirements item, so it lives here as a constant with
     * a test rather than as a literal buried in a composable: a typo in a legal
     * -adjacent notice is the kind of thing that should fail a test, not ship.
     */
    const val CONTACT_DISCLAIMER =
        "Note: If you submit a report, our moderation team may contact you via " +
            "the email address registered to your Agora account if additional " +
            "details are needed."

    /** True only for [OTHER]: the one reason that requires the user to explain. */
    fun requiresExplanation(reason: String): Boolean = reason == OTHER

    /**
     * The exact `reports.reason` value for this selection, or `null` when the
     * selection is not submittable yet.
     *
     * `null` (rather than `""` or a partial string) is what the UI binds the
     * Submit button's `enabled` to, so "cannot submit" and "has a payload" are
     * one decision instead of two that can disagree.
     *
     * Whitespace-only input is treated as empty: `"   "` is not an explanation,
     * and storing `"Other:    "` would be a reason moderators cannot read.
     */
    fun payloadFor(reason: String, explanation: String): String? {
        // An unknown reason is a programming error, not a user choice; refusing
        // it here means a typo in an option list can never reach the database.
        if (reason !in ALL) return null

        if (!requiresExplanation(reason)) return reason

        val trimmed = explanation.trim()
        return if (trimmed.isEmpty()) null else OTHER_PREFIX + trimmed
    }

    /** Convenience for the button state; identical to `payloadFor(..) != null`. */
    fun canSubmit(reason: String, explanation: String): Boolean =
        payloadFor(reason, explanation) != null
}
