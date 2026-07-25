package com.dai2010.betterpak.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dai2010.betterpak.domain.OverwritePolicy
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
    val archiveDefaults: ArchiveDefaults = ArchiveDefaults(),
)

data class ArchiveDefaults(
    val maxEntries: Int = 100_000,
    val maxExpandedBytes: Long = 50L * 1024L * 1024L * 1024L,
    val maxPreviewBytes: Long = 8L * 1024L * 1024L,
    val overwritePolicy: OverwritePolicy = OverwritePolicy.REPLACE,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val customSeedHex = stringPreferencesKey("custom_seed_hex")
        val maxEntries = longPreferencesKey("max_entries")
        val maxExpandedBytes = longPreferencesKey("max_expanded_bytes")
        val maxPreviewBytes = longPreferencesKey("max_preview_bytes")
        val overwritePolicy = stringPreferencesKey("overwrite_policy")
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
                archiveDefaults = ArchiveDefaults(
                    maxEntries = preferences[Keys.maxEntries]?.toInt()?.coerceIn(1, 100_000) ?: 100_000,
                    maxExpandedBytes = preferences[Keys.maxExpandedBytes]
                        ?.coerceIn(1L, 50L * 1024L * 1024L * 1024L)
                        ?: 50L * 1024L * 1024L * 1024L,
                    maxPreviewBytes = preferences[Keys.maxPreviewBytes]
                        ?.coerceIn(1L, 8L * 1024L * 1024L)
                        ?: 8L * 1024L * 1024L,
                    overwritePolicy = preferences[Keys.overwritePolicy]
                        ?.let { value -> OverwritePolicy.entries.firstOrNull { it.name == value } }
                        ?: OverwritePolicy.REPLACE,
                ),
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

    suspend fun setArchiveDefaults(defaults: ArchiveDefaults) {
        context.betterPakDataStore.edit { preferences ->
            preferences[Keys.maxEntries] = defaults.maxEntries.coerceIn(1, 100_000).toLong()
            preferences[Keys.maxExpandedBytes] = defaults.maxExpandedBytes
                .coerceIn(1L, 50L * 1024L * 1024L * 1024L)
            preferences[Keys.maxPreviewBytes] = defaults.maxPreviewBytes
                .coerceIn(1L, 8L * 1024L * 1024L)
            preferences[Keys.overwritePolicy] = defaults.overwritePolicy.name
        }
    }
}
