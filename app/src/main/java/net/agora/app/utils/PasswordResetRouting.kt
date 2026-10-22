package net.agora.app.utils

import net.agora.app.viewmodel.PasswordResetMode
import net.agora.app.viewmodel.PasswordResetStep

/**
 * The password-reset state machine's *policy*.
 *
 * Both entry points run the SAME strict three steps:
 *
 * ```
 * EMAIL  ->  OTP  ->  NEW_PASSWORD
 * ```
 *
 * There are two modes because the wording and the exit differ, not because the
 * security does:
 *
 *  - [PasswordResetMode.CHANGE]   — a signed-in user tapped *Change Password* in
 *    Account Details. The session proves identity, but a live session is also
 *    exactly what an unattended phone has: re-authenticating against the inbox
 *    is what stops somebody holding an unlocked device from silently taking the
 *    account over. The email typed at step 1 must match the signed-in account,
 *    Supabase mails a recovery code, and only a code that verifies unlocks the
 *    new-password step.
 *  - [PasswordResetMode.RECOVERY] — a signed-out user tapped *Forgot password?*.
 *    Same three steps; unrelated to any session, so it also works on a device
 *    that has never signed in.
 *
 * `CHANGE` used to short-circuit straight to NEW_PASSWORD, which compiled, was
 * reachable from Account Details, and skipped both the email check and the
 * OTP — a stolen unlocked phone was enough to change the password. The graph
 * below no longer has that edge.
 */
object PasswordResetRouting {

    /**
     * Both modes enter at the email step. There is no mode that can start any
     * later, so no path exists that reaches [PasswordResetStep.NEW_PASSWORD]
     * without passing through [PasswordResetStep.OTP].
     */
    fun startStep(mode: PasswordResetMode): PasswordResetStep = when (mode) {
        PasswordResetMode.CHANGE -> PasswordResetStep.EMAIL
        PasswordResetMode.RECOVERY -> PasswordResetStep.EMAIL
    }

    /**
     * The new-password step is reachable ONLY with a recovery session that
     * Supabase actually minted by verifying the emailed code.
     *
     * Note the missing `mode == CHANGE` escape hatch: a live JWT is not proof
     * that the person holding the phone is the account owner, so [mode] is
     * accepted only to keep the call sites symmetric and to make the intent
     * explicit at every guard.
     */
    fun canOpenNewPasswordStep(
        mode: PasswordResetMode,
        recoverySessionVerified: Boolean
    ): Boolean = when (mode) {
        // Both modes are gated on the same proof. Written as an exhaustive
        // `when` (no `else`) so a third mode has to state its rule here.
        PasswordResetMode.CHANGE, PasswordResetMode.RECOVERY -> recoverySessionVerified
    }

    /**
     * A code may only be verified after one was successfully requested for this
     * flow. Applies to both modes: `CHANGE` requests a real recovery code too.
     */
    fun canOpenOtpStep(
        mode: PasswordResetMode,
        codeRequested: Boolean
    ): Boolean = when (mode) {
        PasswordResetMode.CHANGE, PasswordResetMode.RECOVERY -> codeRequested
    }
}
