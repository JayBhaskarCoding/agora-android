package net.agora.app.utils

import net.agora.app.viewmodel.PasswordResetMode
import net.agora.app.viewmodel.PasswordResetStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the reset state machine.
 *
 * The invariant this file exists to protect: **no path reaches
 * `NEW_PASSWORD` without a Supabase-verified emailed code, in either mode.**
 * That includes *Change Password* from Account Details, which used to skip
 * straight there on the strength of a live session alone.
 */
class PasswordResetRoutingTest {

    // ── Both modes enter at the email step ─────────────────────────────────

    @Test
    fun changePasswordStartsAtTheEmailStep() {
        assertEquals(
            PasswordResetStep.EMAIL,
            PasswordResetRouting.startStep(PasswordResetMode.CHANGE)
        )
    }

    @Test
    fun recoveryAlwaysStartsAtTheEmailStep() {
        assertEquals(
            PasswordResetStep.EMAIL,
            PasswordResetRouting.startStep(PasswordResetMode.RECOVERY)
        )
    }

    /**
     * The regression guard. A previous revision opened CHANGE on
     * NEW_PASSWORD, so a stolen unlocked phone was enough to change the
     * password: no email check, no emailed code.
     */
    @Test
    fun noModeCanStartPastTheEmailStep() {
        PasswordResetMode.entries.forEach { mode ->
            assertEquals(
                "mode $mode must start at EMAIL",
                PasswordResetStep.EMAIL,
                PasswordResetRouting.startStep(mode)
            )
        }
    }

    // ── The new-password step is locked until the code is verified ─────────

    @Test
    fun newPasswordStepIsLockedForBothModesUntilVerified() {
        PasswordResetMode.entries.forEach { mode ->
            assertFalse(
                "mode $mode opened NEW_PASSWORD without a verified code",
                PasswordResetRouting.canOpenNewPasswordStep(mode, recoverySessionVerified = false)
            )
            assertTrue(
                "mode $mode stayed locked after a verified code",
                PasswordResetRouting.canOpenNewPasswordStep(mode, recoverySessionVerified = true)
            )
        }
    }

    /** CHANGE in particular: a session alone must not unlock step 3. */
    @Test
    fun changePasswordSessionAloneDoesNotUnlockTheNewPasswordStep() {
        assertFalse(
            PasswordResetRouting.canOpenNewPasswordStep(
                PasswordResetMode.CHANGE,
                recoverySessionVerified = false
            )
        )
    }

    // ── The OTP step requires a code to have actually been requested ───────

    @Test
    fun otpStepRequiresAnAcceptedCodeRequest() {
        PasswordResetMode.entries.forEach { mode ->
            assertFalse(
                "mode $mode opened OTP before a code was requested",
                PasswordResetRouting.canOpenOtpStep(mode, codeRequested = false)
            )
            assertTrue(
                "mode $mode stayed locked after a code was requested",
                PasswordResetRouting.canOpenOtpStep(mode, codeRequested = true)
            )
        }
    }

    /**
     * Named separately from [noModeCanStartPastTheEmailStep] on purpose: that
     * test pins the *current* entry step, this one pins the rule that survives
     * the entry step changing — NEW_PASSWORD is never an entry point, in any
     * mode, for any future revision.
     */
    @Test
    fun noModeStartsAtTheNewPasswordStep() {
        PasswordResetMode.entries.forEach { mode ->
            assertNotEquals(
                "mode $mode started at NEW_PASSWORD",
                PasswordResetStep.NEW_PASSWORD,
                PasswordResetRouting.startStep(mode)
            )
        }
    }
}
