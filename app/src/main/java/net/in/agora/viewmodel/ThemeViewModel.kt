package net.in.agora.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import net.in.agora.data.ThemePreference
import net.in.agora.data.ThemePreferenceStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin, observable facade over [ThemePreferenceStore].
 *
 * The ViewModel intentionally holds NO theme state of its own and no derived
 * "isDark" boolean: the effective dark flag depends on the device configuration
 * and is resolved in Compose with `isSystemInDarkTheme()`.
 */
class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    init {
        ThemePreferenceStore.init(application)
    }

    /** The user's explicit choice — [ThemePreference.SYSTEM] means "no override". */
    val themePreference: StateFlow<ThemePreference> = ThemePreferenceStore.preference

    fun setThemePreference(preference: ThemePreference) {
        ThemePreferenceStore.set(getApplication<Application>(), preference)
    }

    /**
     * Account Details switch: writing here records an EXPLICIT override
     * (`LIGHT` / `DARK`), never `SYSTEM`.
     */
    fun setDarkMode(isDark: Boolean) {
        setThemePreference(if (isDark) ThemePreference.DARK else ThemePreference.LIGHT)
    }

    /** Logout: forget the override so auth screens follow the device again. */
    fun resetToSystem() {
        ThemePreferenceStore.resetToSystem(getApplication<Application>())
    }
}
