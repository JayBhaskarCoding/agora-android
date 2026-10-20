package net.in.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferenceTest {

    @Test
    fun systemFollowsTheDeviceInBothDirections() {
        assertTrue(ThemePreference.SYSTEM.resolveIsDark(systemInDarkTheme = true))
        assertFalse(ThemePreference.SYSTEM.resolveIsDark(systemInDarkTheme = false))
    }

    @Test
    fun explicitOverridesIgnoreTheDevice() {
        assertTrue(ThemePreference.DARK.resolveIsDark(systemInDarkTheme = false))
        assertFalse(ThemePreference.LIGHT.resolveIsDark(systemInDarkTheme = true))
    }

    @Test
    fun unknownOrMissingStorageValuesFallBackToSystem() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage(""))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage("PURPLE"))
        assertEquals(ThemePreference.DARK, ThemePreference.fromStorage("DARK"))
        assertEquals(ThemePreference.LIGHT, ThemePreference.fromStorage("LIGHT"))
    }

    @Test
    fun freshInstallDoesNotResolveToDarkWhenTheDeviceIsLight() {
        // Regression guard: the old boolean `is_dark_mode` defaulted to true.
        assertEquals(
            ThemePreference.SYSTEM,
            ThemePreference.fromStorage(null)
        )
        assertFalse(ThemePreference.fromStorage(null).resolveIsDark(systemInDarkTheme = false))
    }
}
