package net.agora.app.utils

/**
 * ✦ SESSION CONTINUITY — the one place that decides when the app is allowed to
 * reset what the user is looking at.
 *
 * Supabase reports a *refresh* and a *sign-in* through the same
 * [io.github.jan.supabase.auth.status.SessionStatus.Authenticated] type. The
 * session object differs (`source`), the user is the same, and from the app's
 * point of view nothing about the identity changed — the token is simply newer.
 *
 * Code that reacts to `sessionStatus` has to distinguish those two cases, and
 * getting it wrong is not a cosmetic bug: the shell rebuilds its navigation
 * graph whenever it is told "this is a new session", so a refresh mistaken for a
 * sign-in drops the user's open screen, their tab and their back stack. The
 * reported symptom is "I minimize the app and come back and I'm on the Feed".
 *
 * The rule, in one line: ONLY an actual signed-out ↔ signed-in transition is
 * allowed to reset navigation. Everything in between is continuity.
 */
internal object SessionContinuity {

    /**
     * True only when the authentication *state itself* changed.
     *
     * `true -> true` is the token refresh that must be ignored, and `false ->
     * false` is the signed-out shell staying signed out. Both must leave the
     * composed UI — and therefore the NavHost and its back stack — untouched.
     */
    fun isIdentityTransition(wasAuthenticated: Boolean, isAuthenticated: Boolean): Boolean =
        wasAuthenticated != isAuthenticated

    /**
     * True when the caller is allowed to run the once-per-session start-up work
     * (the cold-start profile check) and therefore to take the claim.
     *
     * Deliberately independent of `isNew`: a cold start restores a session that
     * Supabase does not consider new, yet it still needs exactly one check —
     * that check is what detects an abandoned account. What must never happen
     * is a *second* run for the same session, because the check swaps the shell
     * for a spinner and takes the NavHost down with it.
     */
    fun shouldRunStartupVerification(alreadyClaimed: Boolean): Boolean = !alreadyClaimed
}
