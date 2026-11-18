package com.mundoinformaticacanaria.gymup.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import com.mundoinformaticacanaria.gymup.data.backup.BackupDomainWriteBarrier
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gymUpPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences",
)

class UserPreferencesRepository internal constructor(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.gymUpPreferences,
    private val domainWriteBarrier: BackupDomainWriteBarrier? = null,
) {

    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map(::decodeThemeMode)

    suspend fun currentThemeMode(): ThemeMode = themeMode.first()

    /**
     * Backup/recovery must not silently turn a corrupt persisted value into SYSTEM.
     * An absent key is a legitimate first-run default; an unknown value is not.
     * The UI keeps its tolerant fallback, but an archive must reflect actual stored state.
     */
    suspend fun currentThemeModeForBackup(): ThemeMode {
        val stored = dataStore.data.first()[THEME_MODE_KEY] ?: return ThemeMode.SYSTEM
        return ThemeMode.entries.firstOrNull { it.name == stored }
            ?: throw IllegalStateException("El modo de tema guardado no es válido para el backup")
    }

    private fun decodeThemeMode(preferences: Preferences): ThemeMode {
        val stored = preferences[THEME_MODE_KEY]
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        if (domainWriteBarrier == null) {
            setThemeModeUnprotected(themeMode)
        } else {
            domainWriteBarrier.withDomainWrite { setThemeModeUnprotected(themeMode) }
        }
    }

    private suspend fun setThemeModeUnprotected(themeMode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = themeMode.name
        }
    }

    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    }
}
