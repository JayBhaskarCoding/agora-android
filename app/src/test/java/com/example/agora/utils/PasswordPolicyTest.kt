package com.example.agora.utils

import org.junit.Assert.*
import org.junit.Test

class PasswordPolicyTest {
    @Test
    fun acceptsPasswordMeetingEveryRuleAtMinimumLength() {
        assertTrue(PasswordPolicy.isValid("Abcdef1!"))
        assertEquals(5, PasswordPolicy.requirements("Abcdef1!").count { it.satisfied })
    }

    @Test
    fun rejectsEachMissingRequirementIndependently() {
        val examples = listOf(
            "Abcd1!" to 0,
            "abcdef1!" to 1,
            "ABCDEF1!" to 2,
            "Abcdefg!" to 3,
            "Abcdef12" to 4
        )
        examples.forEach { (password, missingIndex) ->
            assertFalse(password, PasswordPolicy.isValid(password))
            val rules = PasswordPolicy.requirements(password)
            assertFalse(password, rules[missingIndex].satisfied)
            assertEquals(password, 4, rules.count { it.satisfied })
        }
    }

    @Test
    fun emptyPasswordMeetsNoRules() {
        assertEquals(0, PasswordPolicy.requirements("").count { it.satisfied })
    }

    @Test
    fun spacesAreNotSpecialCharacters() {
        assertFalse(PasswordPolicy.isValid("Abcdef1 "))
    }

    @Test
    fun allSupportedSymbolsCountAsSpecialCharacters() {
        PasswordPolicy.SPECIAL_CHARACTERS.forEach { symbol ->
            assertTrue("Symbol $symbol", PasswordPolicy.isValid("Abcdef1$symbol"))
        }
    }

    @Test
    fun removingRequiredCharacterImmediatelyInvalidatesPassword() {
        assertTrue(PasswordPolicy.isValid("Abcdef1!"))
        assertFalse(PasswordPolicy.isValid("Abcdef1"))
    }
}
