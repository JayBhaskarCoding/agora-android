package net.agora.app.utils

/**
 * Ported from the retired template package history. Guards the reset state machine:
 * recovery must always start at EMAIL, and the new-password step must stay
 * locked until Supabase actually verified the emailed code.
 */

import net.agora.app.viewmodel.PasswordResetMode
import net.agora.app.viewmodel.PasswordResetStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordResetRoutingTest {

    // ── Signed-in *Change Password*: session is proof of identity ──────────

    @Test
    fun changePasswordOpensOnTheNewPasswordStep() {
        assertEquals(
            PasswordResetStep.NEW_PASSWORD,
            PasswordResetRouting.startStep(PasswordResetMode.CHANGE)
        )
    }

    @Test
    fun changePasswordNeverNeedsAnEmailedCode() {
        assertTrue(PasswordResetRouting.canOpenNewPasswordStep(PasswordResetMode.CHANGE, false))
        assertFalse(PasswordResetRouting.canOpenOtpStep(PasswordResetMode.CHANGE, true))
    }

    // ── Signed-out recovery: strict email → OTP → new password ─────────────

    @Test
    fun recoveryAlwaysStartsAtTheEmailStep() {
        assertEquals(
            PasswordResetStep.EMAIL,
            PasswordResetRouting.startStep(PasswordResetMode.RECOVERY)
        )
    }

    @Test
    fun newPasswordStepIsLockedUntilTheRecoverySessionIsVerified() {
        assertFalse(PasswordResetRouting.canOpenNewPasswordStep(PasswordResetMode.RECOVERY, false))
        assertTrue(PasswordResetRouting.canOpenNewPasswordStep(PasswordResetMode.RECOVERY, true))
    }

    @Test
    fun otpStepRequiresAnAcceptedCodeRequest() {
        assertFalse(PasswordResetRouting.canOpenOtpStep(PasswordResetMode.RECOVERY, false))
        assertTrue(PasswordResetRouting.canOpenOtpStep(PasswordResetMode.RECOVERY, true))
    }

    @Test
    fun everyModeGetsAGraphEntryPoint() {
        PasswordResetMode.entries.forEach { mode ->
            val route = PasswordResetStep.entries.contains(PasswordResetRouting.startStep(mode))
            assertTrue("no start step for $mode", route)
        }
    }
}
