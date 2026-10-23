package net.agora.app.utils

/**
 * ✦ ONBOARDING ROUTING — the one place that decides Home vs "Create Your Profile".
 *
 * Pure Kotlin: no Compose, no Supabase, no Android. That is deliberate. This
 * decision used to be a boolean buried in a coroutine, and it is the only thing
 * standing between a finished account and being sent back through onboarding —
 * so it is worth being able to test every branch on the JVM.
 *
 * ## The bug this exists to prevent
 *
 * After finishing onboarding the user reached Home and Account Details showed
 * their data, but a restart put them back on "Create Your Profile". The cause was
 * not one line: the in-session screen was decided by an **in-memory** flag that
 * [net.agora.app.viewmodel.AuthViewModel.finishOnboarding] cleared, while the
 * restart was decided by re-reading `public.profiles`. Those two disagreed
 * whenever the row was not actually written, and the old check collapsed every
 * failure mode into one answer:
 *
 * ```
 *   profile == null  ->  "incomplete"  ->  onboarding
 * ```
 *
 * "no row", "I could not read the row", and "the row is a placeholder" are three
 * different facts, and only the third one means "this user has not onboarded".
 * Treating them as one is what trapped completed accounts (and, worse, let the
 * cold-start cleanup delete them).
 *
 * ## The three verdicts
 *
 * - [Verdict.COMPLETE] — the row carries a real username, or this device has
 *   already recorded that onboarding finished for this account.
 * - [Verdict.NEEDS_ONBOARDING] — a row exists and is still a placeholder, or no
 *   row exists and nothing on this device says otherwise.
 * - [Verdict.UNKNOWN] — the row could not be read (timeout, RLS race, aborted
 *   session) and nothing else proves onboarding finished. **Never** treated as
 *   onboarding, and **never** allowed to wipe.
 *
 * The invariant that makes this safe: evidence from outside the row (the local
 * record, or a handle in `user_metadata`) can only ever *raise* the verdict to
 * [Verdict.COMPLETE]. It can never produce [Verdict.NEEDS_ONBOARDING], and it can
 * never authorise a delete. So a caller that asks "is this COMPLETE?" gets a
 * conservative `false` when nothing is known, and a caller that asks "may I
 * onboard?" must test for [Verdict.NEEDS_ONBOARDING] explicitly.
 */
internal object OnboardingRouting {

    /** What the database (plus the local record) says about an account. */
    enum class Verdict {
        COMPLETE,
        NEEDS_ONBOARDING,
        UNKNOWN
    }

    /** Where the shell should put the user. */
    enum class Destination {
        HOME,
        ONBOARDING
    }

    /**
     * Handles that only ever came from a placeholder row.
     *
     * `handle` is nullable in `public.profiles`, and the Kotlin model used to
     * decode a NULL into the literal string `"user"` — so this check was partly
     * matching a *serialization default* rather than real data. The sentinel is
     * gone; these remain for legacy rows written before that change.
     */
    private val PLACEHOLDER_HANDLES = setOf("user")

    /** First names written by an older signup path. Kept for legacy rows. */
    private val PLACEHOLDER_FIRST_NAMES = setOf("Pending")

    /**
     * True when this handle is not a usable, user-chosen username.
     *
     * Blank covers both "NULL in the database" and "empty string"; the prefix
     * check covers rows created by `handle_new_user()` from sign-up metadata that
     * old clients used to send.
     */
    fun isPlaceholderHandle(handle: String?): Boolean {
        val value = handle?.trim().orEmpty()
        return value.isEmpty() ||
            value in PLACEHOLDER_HANDLES ||
            value.startsWith("user_")
    }

    fun isPlaceholderFirstName(firstName: String?): Boolean {
        val value = firstName?.trim().orEmpty()
        return value.isEmpty() || value in PLACEHOLDER_FIRST_NAMES
    }

    /**
     * True when the session's own `user_metadata` already proves onboarding ran.
     *
     * ## Why this exists
     *
     * [net.agora.app.data.OnboardingStateStore] only starts recording completions
     * from the build that introduced it. Everyone who onboarded **before** that
     * build has no local record, so on their first start after the update the only
     * evidence is the database — and if that one read fails they are sent back
     * through Create Your Profile, which is the reported bug for the largest group
     * of affected users.
     *
     * Their JWT does carry evidence, though: completing onboarding has always
     * written `handle` (and now `username`) into `user_metadata` via
     * `auth.updateUser`, and sign-up does not. So a **non-placeholder** handle in
     * metadata means a real person chose it in the wizard. The metadata copy lives
     * in the stored session, so this holds with no network at all.
     *
     * Deliberately narrow: only `handle`/`username`, only when they pass
     * [isPlaceholderHandle]. Google identities carry `full_name`/`name`/`picture`
     * and never a handle before onboarding, which is why Google users are
     * unaffected by this check in both directions.
     */
    fun metadataProvesOnboarding(handle: String?, username: String?): Boolean =
        !isPlaceholderHandle(handle) || !isPlaceholderHandle(username)

    /**
     * Classifies the account.
     *
     * @param rowPresent whether the query returned a row at all. A query that
     *   succeeds but matches nothing (RLS filtering, or a session whose JWT has
     *   not been attached yet) returns empty **without an error**, which is why
     *   this is separate from [readFailed].
     * @param readFailed the query threw — timeout, 401 mid-refresh, network down.
     * @param completionEvidence proof that onboarding already finished for this
     *   user, **from outside the row**: the per-user record this device wrote (see
     *   [net.agora.app.data.OnboardingStateStore]) or a real handle already sitting
     *   in the session's `user_metadata` (see [metadataProvesOnboarding]). Either
     *   one outranks a failed read, and either one forbids deletion.
     */
    fun verdict(
        rowPresent: Boolean,
        readFailed: Boolean,
        handle: String?,
        firstName: String?,
        completionEvidence: Boolean
    ): Verdict {
        /* Could not ask the database. Without outside evidence, say UNKNOWN
         * rather than guessing "new user" — that guess is what put completed
         * accounts back on Create Your Profile. WITH outside evidence there is
         * nothing to guess: onboarding finished, so say so. Reporting UNKNOWN here
         * instead would leave the two "did it finish?" answers disagreeing, and the
         * disagreement is the bug. */
        if (readFailed) {
            return if (completionEvidence) Verdict.COMPLETE else Verdict.UNKNOWN
        }

        if (!rowPresent) {
            /* No row. For an account that has never onboarded this is genuine
             * ("the trigger did not create one"), so onboarding is right. For an
             * account with outside evidence it can only be a read artefact — the
             * row exists, we just could not see it. Sending that user back through
             * the wizard is the reported bug. */
            return if (completionEvidence) Verdict.COMPLETE else Verdict.NEEDS_ONBOARDING
        }

        /* A real username is the strongest signal available and it is exactly the
         * rule the audit asked for: valid row + non-blank username -> complete,
         * regardless of what any local flag believes. */
        if (!isPlaceholderHandle(handle) && !isPlaceholderFirstName(firstName)) {
            return Verdict.COMPLETE
        }

        /* Row is still a placeholder shape. Trust a local completion record over
         * it anyway: the write path verifies the handle after writing, so a local
         * "complete" cannot be a lie about an account that never finished — and
         * refusing it would re-trap the user this whole change is about. */
        return if (completionEvidence) Verdict.COMPLETE else Verdict.NEEDS_ONBOARDING
    }

    /**
     * Home unless we have no reason at all to believe onboarding finished.
     *
     * [completionEvidence] wins outright — that is the rule that makes a restart
     * land on Home even when the network is down.
     */
    fun destination(completionEvidence: Boolean, verdict: Verdict): Destination = when {
        completionEvidence -> Destination.HOME
        verdict == Verdict.COMPLETE -> Destination.HOME
        else -> Destination.ONBOARDING
    }

    /**
     * Whether the cold-start cleanup may delete this account.
     *
     * Deliberately a whitelist of conditions rather than a blacklist: an account
     * may only be deleted when we are **positive** it is a placeholder, we have no
     * outside evidence that it finished, it was not created in this run, and it
     * was not verified recently on this device. Anything else — including
     * [UNKNOWN] — keeps the account.
     *
     * The old code deleted on `profile == null`, i.e. on a failed read. That is the
     * most destructive possible interpretation of "I don't know".
     */
    fun mayDeleteAccount(
        verdict: Verdict,
        completionEvidence: Boolean,
        recentlyVerified: Boolean,
        sessionCreatedInThisRun: Boolean
    ): Boolean =
        verdict == Verdict.NEEDS_ONBOARDING &&
            !completionEvidence &&
            !recentlyVerified &&
            !sessionCreatedInThisRun
}
