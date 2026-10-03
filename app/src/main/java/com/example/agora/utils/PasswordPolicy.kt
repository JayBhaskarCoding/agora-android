package com.example.agora.utils

/** Shared registration policy. ASCII classes match Supabase's password policy. */
object PasswordPolicy {
    const val SPECIAL_CHARACTERS = "!@#$%^&*()_+-=[]{};':\"\\|<>?,./`~"
    const val ERROR_MESSAGE = "Use at least 8 characters, including an uppercase letter, a lowercase letter, a number, and a special character."

    data class Requirement(val label: String, val satisfied: Boolean)

    fun requirements(password: String): List<Requirement> = listOf(
        Requirement("At least 8 characters", password.length >= 8),
        Requirement("At least one uppercase letter", password.any { it in 'A'..'Z' }),
        Requirement("At least one lowercase letter", password.any { it in 'a'..'z' }),
        Requirement("At least one number", password.any { it in '0'..'9' }),
        Requirement("At least one special character", password.any { it in SPECIAL_CHARACTERS })
    )

    fun isValid(password: String): Boolean = requirements(password).all { it.satisfied }
}
