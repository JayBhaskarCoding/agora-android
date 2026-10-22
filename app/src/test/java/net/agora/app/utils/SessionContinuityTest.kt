package net.agora.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement: "Minimizing and reopening must keep the current inner screen.
 * Only signed-out -> signed-in or signed-in -> signed-out may reset."
 *
 * These are the executable form of that sentence. They exist because the two
 * cases are indistinguishable by type — a token refresh and a sign-in both
 * arrive as [io.github.jan.supabase.auth.status.SessionStatus.Authenticated] —
 * so the distinction has to live in a rule someone can point at.
 */
class SessionContinuityTest {

    /** The bug: a refresh re-emits Authenticated and the shell rebuilt its NavHost. */
    @Test
    fun aTokenRefreshIsNotAnIdentityTransition() {
        assertFalse(SessionContinuity.isIdentityTransition(wasAuthenticated = true, isAuthenticated = true))
    }

    @Test
    fun signingInIsAnIdentityTransition() {
        assertTrue(SessionContinuity.isIdentityTransition(wasAuthenticated = false, isAuthenticated = true))
    }

    @Test
    fun signingOutIsAnIdentityTransition() {
        assertTrue(SessionContinuity.isIdentityTransition(wasAuthenticated = true, isAuthenticated = false))
    }

    /** Two "no session yet" reads in a row must not be treated as a logout. */
    @Test
    fun stayingSignedOutIsNotAnIdentityTransition() {
        assertFalse(SessionContinuity.isIdentityTransition(wasAuthenticated = false, isAuthenticated = false))
    }

    @Test
    fun theStartupCheckRunsWhenItHasNotBeenClaimed() {
        assertTrue(SessionContinuity.shouldRunStartupVerification(alreadyClaimed = false))
    }

    /**
     * The regression guard: once a session has been claimed, every further
     * Authenticated emit (refresh, resume, unlocked screen) must be a no-op.
     * Running it twice swaps the shell for a spinner, which unmounts MainScreen
     * and destroys its NavHost even though the user never left.
     */
    @Test
    fun theStartupCheckNeverRunsTwiceForTheSameSession() {
        assertFalse(SessionContinuity.shouldRunStartupVerification(alreadyClaimed = true))
    }
}
