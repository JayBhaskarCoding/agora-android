package net.agora.app.data

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's explicit theme choice.
 *
 * [SYSTEM] is the ABSENCE of an override — "follow the device". It is the
 * default for a fresh install and the value the app returns to after logout,
 * so the unauthenticated Login / Registration screens always match the
 * device's Light/Dark setting.
 */
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK;

    /** Resolves this preference against the live system setting. */
    fun resolveIsDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** Tolerant parse — unknown values fall back to [SYSTEM]. */
        fun fromStorage(raw: String?): ThemePreference =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/**
 * 🌟 THEME SOURCE OF TRUTH.
 *
 * Previously [net.agora.app.viewmodel.ThemeViewModel] persisted a second,
 * boolean `is_dark_mode` flag that DEFAULTED TO `true`. MainActivity themed the
 * whole app from that flag, so a fresh install (and every user who never touched
 * the switch) was forced into Dark Mode even when the OS was in Light Mode.
 *
 * A single string preference is now authoritative:
 *  - key absent / `SYSTEM`  → follow `isSystemInDarkTheme()`
 *  - `LIGHT` / `DARK`       → explicit user override (only written from the
 *                             Account Details switch or the onboarding picker)
 *
 * The legacy boolean is removed on first read: it cannot express "follow
 * system", and users who genuinely toggled the switch already have an explicit
 * `LIGHT`/`DARK` string written by `setThemePreference`, so nothing is lost.
 */
object ThemePreferenceStore {

    const val PREFS_NAME = "theme_prefs"

    private const val KEY_PREFERENCE = "theme_preference"
    private const val LEGACY_KEY_IS_DARK = "is_dark_mode"

    private val _preference = MutableStateFlow(ThemePreference.SYSTEM)

    /** Observable theme choice, consumed by [net.agora.app.viewmodel.ThemeViewModel]. */
    val preference: StateFlow<ThemePreference> = _preference.asStateFlow()

    @Volatile
    private var loaded = false

    /** Reads the persisted choice once (idempotent; safe to call from Application.onCreate). */
    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            _preference.value = ThemePreference.fromStorage(prefs.getString(KEY_PREFERENCE, null))
            if (prefs.contains(LEGACY_KEY_IS_DARK)) {
                prefs.edit().remove(LEGACY_KEY_IS_DARK).apply()
            }
            loaded = true
        }
    }

    /** Current choice, loading it lazily when needed. */
    fun current(context: Context): ThemePreference {
        init(context)
        return _preference.value
    }

    /** Persists an explicit override. */
    fun set(context: Context, preference: ThemePreference) {
        val prefs = prefs(context)
        if (preference == ThemePreference.SYSTEM) {
            prefs.edit().remove(KEY_PREFERENCE).apply()
        } else {
            prefs.edit().putString(KEY_PREFERENCE, preference.name).apply()
        }
        _preference.value = preference
        loaded = true
    }

    /**
     * Drops the user's override, returning the app to system-following mode.
     * Called on every logout so the auth screens match the device again.
     */
    fun resetToSystem(context: Context) {
        val prefs = prefs(context)
        prefs.edit()
            .remove(KEY_PREFERENCE)
            .remove(LEGACY_KEY_IS_DARK)
            .apply()
        _preference.value = ThemePreference.SYSTEM
        loaded = true
    }

    /** True when the device is currently in night mode. */
    fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Effective dark flag for code that runs BEFORE Compose (splash, window bg, system bars). */
    fun resolveIsDark(context: Context): Boolean =
        current(context).resolveIsDark(isSystemDark(context))

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
