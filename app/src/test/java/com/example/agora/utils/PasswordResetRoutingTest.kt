package com.example.agora.utils

import com.example.agora.viewmodel.PasswordResetMode
import com.example.agora.viewmodel.PasswordResetStep
import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordResetRoutingTest {

    @Test
    fun signedInUsersChangeTheirPasswordWithoutAnEmailRoundTrip() {
        val mode = PasswordResetRouting.modeFor(hasSession = true)

        assertEquals(PasswordResetMode.CHANGE, mode)
        assertEquals(PasswordResetStep.NEW_PASSWORD, PasswordResetRouting.startStep(mode))
    }

    @Test
    fun signedOutUsersMustRequestAnEmailedCodeFirst() {
        val mode = PasswordResetRouting.modeFor(hasSession = false)

        assertEquals(PasswordResetMode.RECOVERY, mode)
        assertEquals(PasswordResetStep.EMAIL, PasswordResetRouting.startStep(mode))
    }

    @Test
    fun onlyRecoveryStartsAtTheEmailStep() {
        PasswordResetMode.entries.forEach { mode ->
            val step = PasswordResetRouting.startStep(mode)
            if (mode == PasswordResetMode.RECOVERY) {
                assertEquals(PasswordResetStep.EMAIL, step)
            } else {
                assertEquals(PasswordResetStep.NEW_PASSWORD, step)
            }
        }
    }
}
