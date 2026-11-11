package com.mundoinformaticacanaria.gymup.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.preferences.UserPreferencesRepository
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupPreferencesReadFailureTest {
    @Test
    fun `unreadable DataStore blocks export and replace without mutating Room`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            DatabaseSeeder(context, database).seedIfNeeded()
            val healthyPreferences = UserPreferencesRepository(context)
            healthyPreferences.setThemeMode(ThemeMode.DARK)
            val healthyBackup = RoomBackupDataSource(context, database, healthyPreferences)
            val original = healthyBackup.exportSnapshot()
            val originalExerciseIds = database.backupDao().getExercises().map { it.id }

            val unreadablePreferences = UserPreferencesRepository(context, object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    throw IOException("Simulated unreadable DataStore")
                }

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    error("No preference write should occur after a failed strict read")
            })
            // The ordinary UI deliberately falls back to SYSTEM on an I/O failure.
            assertEquals(ThemeMode.SYSTEM, unreadablePreferences.currentThemeMode())

            val unsafeBackup = RoomBackupDataSource(context, database, unreadablePreferences)
            assertTrue( runCatching { unsafeBackup.exportSnapshot() }.exceptionOrNull() is IOException )
            assertTrue( runCatching { unsafeBackup.replaceAll(original) }.exceptionOrNull() is IOException )

            assertEquals(originalExerciseIds, database.backupDao().getExercises().map { it.id })
            assertArrayEquals(original.data, healthyBackup.exportSnapshot().data)
            assertEquals(ThemeMode.DARK, healthyPreferences.currentThemeModeForBackup())
        } finally {
            database.close()
        }
    }
}
