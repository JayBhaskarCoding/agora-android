package net.agora.app.utils

/**
 * Ported from the retired template package history (branch side that was never
 * merged into the current line). Behaviour unchanged; package updated.
 *
 * The reset flow's POLICY lives here — free of Android types, so it is unit
 * tested instead of being asserted by comments.
 */

import net.agora.app.viewmodel.PasswordResetMode
import net.agora.app.viewmodel.PasswordResetStep

/**
 * The password-reset state machine's *policy*, kept free of Android types so it
 * can be unit-tested.
 *
 * Entry points are explicit — never inferred from whatever session happens to be
 * lying around:
 *
 *  - [PasswordResetMode.CHANGE]   — a signed-in user tapped *Change Password*.
 *    The session JWT already proved identity, so the flow opens on the new
 *    password step.
 *  - [PasswordResetMode.RECOVERY] — a signed-out user tapped *Forgot password?*.
 *    Strict three steps: email → 6-digit recovery code → new password.
 *    It ALWAYS starts at the email step, even if a stale or half-finished
 *    recovery session exists, so the OTP step can never be skipped.
 */
object PasswordResetRouting {

    fun startStep(mode: PasswordResetMode): PasswordResetStep = when (mode) {
        PasswordResetMode.CHANGE -> PasswordResetStep.NEW_PASSWORD
        PasswordResetMode.RECOVERY -> PasswordResetStep.EMAIL
    }

    /**
     * The new-password step is reachable only with proven identity: a live
     * session (CHANGE) or a recovery code that Supabase actually verified and
     * that minted the temporary recovery session (RECOVERY).
     */
    fun canOpenNewPasswordStep(
        mode: PasswordResetMode,
        recoverySessionVerified: Boolean
    ): Boolean = mode == PasswordResetMode.CHANGE || recoverySessionVerified

    /** A recovery code may only be verified after one was successfully requested. */
    fun canOpenOtpStep(mode: PasswordResetMode, codeRequested: Boolean): Boolean =
        mode == PasswordResetMode.RECOVERY && codeRequested
}
