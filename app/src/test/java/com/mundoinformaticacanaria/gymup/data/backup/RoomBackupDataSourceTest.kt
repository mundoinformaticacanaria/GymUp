package com.mundoinformaticacanaria.gymup.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.SessionEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionSetEntity
import com.mundoinformaticacanaria.gymup.data.preferences.UserPreferencesRepository
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.backup.BackupDataCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupDataValidationResult
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomBackupDataSourceTest {
    private lateinit var context: Context
    private lateinit var database: GymUpDatabase
    private lateinit var preferences: UserPreferencesRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = UserPreferencesRepository(context)
        DatabaseSeeder(context, database).seedIfNeeded()
        preferences.setThemeMode(ThemeMode.DARK)
    }

    @After
    fun tearDown() {
        database.close()
        context.filesDir.resolve("exercise-images").deleteRecursively()
    }

    @Test
    fun `snapshot round trip restores masters sessions sets and preferences`() = runBlocking {
        val dao = database.backupDao()
        val sessionType = dao.getSessionTypes().first()
        val exercise = dao.getExercises().first()
        val sessionId = UUID.randomUUID().toString()
        val sessionExerciseId = UUID.randomUUID().toString()
        val setId = UUID.randomUUID().toString()

        dao.insertSessions(
            listOf(
                SessionEntity(
                    id = sessionId,
                    sessionDateEpochDay = LocalDate.of(2026, 8, 21).toEpochDay(),
                    orderInDay = 1,
                    sessionTypeId = sessionType.id,
                    sessionTypeNameSnapshot = sessionType.name,
                    name = "Sesión backup",
                    isAutoName = false,
                    generalNote = "nota",
                    operationalState = SessionOperationalState.REALIZED,
                    executionResult = SessionExecutionResult.COMPLETED,
                ),
            ),
        )
        dao.insertSessionExercises(
            listOf(
                SessionExerciseEntity(
                    id = sessionExerciseId,
                    sessionId = sessionId,
                    exerciseId = exercise.id,
                    position = 1,
                    exerciseNameEsSnapshot = exercise.nameEs,
                    exerciseNameEnSnapshot = exercise.nameEn,
                    muscleGroupNameSnapshot = "Pecho",
                    equipmentNameSnapshot = null,
                    defaultLoadModeSnapshot = LoadMode.KG_TOTAL,
                    defaultMeasurementUnitSnapshot = MeasurementUnit.REPETITIONS,
                    rirRequiredSnapshot = true,
                    descriptionSnapshot = exercise.description,
                    exerciseRestSeconds = 90,
                    note = null,
                    incompleteReason = null,
                    isFinalized = true,
                ),
            ),
        )
        dao.insertSessionSets(
            listOf(
                SessionSetEntity(
                    id = setId,
                    sessionExerciseId = sessionExerciseId,
                    position = 1,
                    loadMode = LoadMode.KG_TOTAL,
                    measurementUnit = MeasurementUnit.REPETITIONS,
                    targetLoad = 40.0,
                    actualLoad = 42.5,
                    targetMeasurement = 10,
                    actualMeasurement = 10,
                    rir = 1,
                    restOverrideSeconds = null,
                    actualConfirmed = true,
                ),
            ),
        )

        val source = RoomBackupDataSource(context, database, preferences)
        val snapshot = source.exportSnapshot()
        val decoded = BackupDataCodec.decode(snapshot.data.decodeToString()).getOrThrow()

        assertEquals(BackupDataValidationResult.Valid, BackupDataCodec.validate(decoded))
        assertEquals(61, decoded.exercises.size)
        assertEquals(1, decoded.sessions.size)
        assertEquals(ThemeMode.DARK.name, decoded.preferences.themeMode)

        dao.deleteSessionSets()
        dao.deleteSessionExercises()
        dao.deleteSessions()
        preferences.setThemeMode(ThemeMode.LIGHT)

        source.replaceAll(snapshot)

        assertEquals(61, dao.getExercises().size)
        assertNotNull(database.trainingDao().getSession(sessionId))
        assertEquals(1, database.trainingDao().getSets(sessionExerciseId).size)
        assertEquals(ThemeMode.DARK, preferences.currentThemeMode())
        assertTrue(snapshot.images.isEmpty())
    }
}
