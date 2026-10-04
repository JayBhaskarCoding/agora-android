package com.example.agora.utils

import com.example.agora.viewmodel.PasswordResetMode
import com.example.agora.viewmodel.PasswordResetStep

/**
 * Which shape the password flow takes, decided once when it opens.
 *
 * A signed-in user already proved their identity with the session JWT, so
 * `updateUser` can be called straight away; a signed-out user must prove they
 * own the address by entering the recovery code emailed to them before any
 * password mutation is possible.
 */
object PasswordResetRouting {

    fun modeFor(hasSession: Boolean): PasswordResetMode =
        if (hasSession) PasswordResetMode.CHANGE else PasswordResetMode.RECOVERY

    fun startStep(mode: PasswordResetMode): PasswordResetStep = when (mode) {
        PasswordResetMode.CHANGE -> PasswordResetStep.NEW_PASSWORD
        PasswordResetMode.RECOVERY -> PasswordResetStep.EMAIL
    }
}
