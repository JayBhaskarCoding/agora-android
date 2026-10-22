package net.agora.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import net.agora.app.data.ThemePreference
import net.agora.app.data.ThemePreferenceStore
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

    /* Note: there is deliberately no `setDarkMode(Boolean)` here any more. A
     * two-state setter cannot express "follow the device" — it can only ever
     * write LIGHT or DARK — which is why Account Details now offers the three
     * states explicitly and calls [setThemePreference] instead. */

    /** Logout: forget the override so auth screens follow the device again. */
    fun resetToSystem() {
        ThemePreferenceStore.resetToSystem(getApplication<Application>())
    }
}
