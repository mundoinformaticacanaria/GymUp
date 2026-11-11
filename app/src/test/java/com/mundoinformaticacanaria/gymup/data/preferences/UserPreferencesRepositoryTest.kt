package com.mundoinformaticacanaria.gymup.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.data.backup.BackupDomainWriteBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserPreferencesRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val themeKey = stringPreferencesKey("theme_mode")

    @Test
    fun `invalid persisted theme is tolerated by UI but rejected for backup`() = runBlocking {
        val store = storeWithTheme("UNRECOGNIZED")
        val repository = UserPreferencesRepository(context, store)

        assertEquals(ThemeMode.SYSTEM, repository.currentThemeMode())
        val failure = runCatching { repository.currentThemeModeForBackup() }.exceptionOrNull()
        assertTrue("Backup must not silently export SYSTEM for an invalid value", failure is IllegalStateException)
        assertEquals("UNRECOGNIZED", store.data.first()[themeKey])
    }

    @Test
    fun `missing theme is a valid first run default for backup`() = runBlocking {
        val repository = UserPreferencesRepository(context, storeWithTheme(null))
        assertEquals(ThemeMode.SYSTEM, repository.currentThemeModeForBackup())
    }

    @Test
    fun `valid theme is exported without normalization`() = runBlocking {
        val repository = UserPreferencesRepository(context, storeWithTheme("DARK"))
        assertEquals(ThemeMode.DARK, repository.currentThemeModeForBackup())
    }

    @Test
    fun `theme write waits for exclusive backup and does not mutate DataStore early`() = runBlocking {
        val store = storeWithTheme("LIGHT")
        val barrier = BackupDomainWriteBarrier()
        val repository = UserPreferencesRepository(context, store, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backup = launch {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val writer = launch { repository.setThemeMode(ThemeMode.DARK) }
        yield()
        assertFalse(writer.isCompleted)
        assertEquals("LIGHT", store.data.first()[themeKey])

        release.complete(Unit)
        backup.join()
        writer.join()
        assertEquals("DARK", store.data.first()[themeKey])
    }

    private fun storeWithTheme(raw: String?): DataStore<Preferences> {
        val initial = emptyPreferences().toMutablePreferences()
        if (raw != null) initial[themeKey] = raw
        val state = MutableStateFlow<Preferences>(initial)
        return object : DataStore<Preferences> {
            override val data: Flow<Preferences> = state

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                val updated = transform(state.value)
                state.value = updated
                return updated
            }
        }
    }
}
