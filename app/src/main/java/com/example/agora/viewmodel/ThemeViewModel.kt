package com.example.agora.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemePreference { LIGHT, DARK, SYSTEM }

class ThemeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    private val savedPrefName = prefs.getString("theme_preference", ThemePreference.SYSTEM.name)
    private val initialPref = try {
        ThemePreference.valueOf(savedPrefName ?: ThemePreference.SYSTEM.name)
    } catch (_: Exception) {
        ThemePreference.SYSTEM
    }

    private val _themePreference = MutableStateFlow(initialPref)
    val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    private val _isDarkMode = MutableStateFlow(prefs.getBoolean("is_dark_mode", true))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun setThemePreference(preference: ThemePreference) {
        _themePreference.value = preference
        prefs.edit().putString("theme_preference", preference.name).apply()
        val isDark = when (preference) {
            ThemePreference.DARK -> true
            ThemePreference.LIGHT -> false
            ThemePreference.SYSTEM -> true // Defaults to Dark Mode for system preference baseline
        }
        _isDarkMode.value = isDark
        prefs.edit().putBoolean("is_dark_mode", isDark).apply()
    }

    fun setDarkMode(isDark: Boolean) {
        val pref = if (isDark) ThemePreference.DARK else ThemePreference.LIGHT
        setThemePreference(pref)
    }
}
