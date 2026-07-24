package com.dai2010.betterpak.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.betterPakDataStore by preferencesDataStore(name = "betterpak_settings")

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val customSeedHex: String = "",
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val customSeedHex = stringPreferencesKey("custom_seed_hex")
    }

    val settings: Flow<AppSettings> = context.betterPakDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            val mode = preferences[Keys.themeMode]
                ?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } }
                ?: ThemeMode.SYSTEM
            AppSettings(
                themeMode = mode,
                customSeedHex = preferences[Keys.customSeedHex].orEmpty(),
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.betterPakDataStore.edit { preferences ->
            preferences[Keys.themeMode] = mode.name
        }
    }

    suspend fun setCustomSeedHex(value: String) {
        context.betterPakDataStore.edit { preferences ->
            preferences[Keys.customSeedHex] = value
        }
    }
}
