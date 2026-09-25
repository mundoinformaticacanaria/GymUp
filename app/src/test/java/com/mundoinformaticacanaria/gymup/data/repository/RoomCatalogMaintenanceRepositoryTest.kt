package com.mundoinformaticacanaria.gymup.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionResult
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceInput
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogKind
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRemovalConfirmationRequired
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSource
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomCatalogMaintenanceRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: GymUpDatabase
    private lateinit var maintenance: RoomCatalogMaintenanceRepository
    private lateinit var training: RoomTrainingRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseSeeder(context, database).seedIfNeeded()
        maintenance = RoomCatalogMaintenanceRepository(context, database)
        training = RoomTrainingRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.filesDir.resolve("exercise-images").deleteRecursively()
    }

    @Test
    fun `protected Other session type cannot be renamed or deactivated`() = runBlocking {
        val other = database.masterDataDao().getSessionTypes().single { it.isProtectedOther }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { maintenance.renameMaster(MasterCatalogKind.SESSION_TYPE, other.id, "Otro nombre") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { maintenance.deactivateMaster(MasterCatalogKind.SESSION_TYPE, other.id) }
        }
        Unit
    }

    @Test
    fun `editing an exercise preserves an already inactive master reference`() = runBlocking {
        val exercise = database.exerciseDao().getAll().first()
        maintenance.deactivateMaster(MasterCatalogKind.MUSCLE_GROUP, exercise.muscleGroupId)

        maintenance.updateExercise(
            exercise.id,
            exercise.toInput(description = "Descripción actualizada"),
        )

        val updated = requireNotNull(maintenance.getExercise(exercise.id))
        assertEquals(exercise.muscleGroupId, updated.muscleGroupId)
        assertEquals("Descripción actualizada", updated.description)
    }

    @Test
    fun `exercise initial configuration persists and populates a planned session`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(
            customInput(
                groupId = group.id,
                es = "Press prueba defaults",
                en = "Defaults test press",
                initialSetCount = 3,
                initialLoad = 20.0,
                initialMeasurement = 10,
            ),
        )

        val saved = requireNotNull(maintenance.getExercise(exerciseId))
        assertEquals(3, saved.initialSetCount)
        assertEquals(20.0, saved.initialLoad ?: -1.0, 0.0)
        assertEquals(10, saved.initialMeasurement)

        val sessionType = database.masterDataDao().getSessionTypes().first { it.isActive }
        val sessionId = training.createSession(
            LocalDate.of(2026, 8, 21),
            sessionType.id,
            null,
            null,
            SessionSource.Empty,
        ).sessionId
        training.addExercise(sessionId, exerciseId)

        val sets = requireNotNull(training.getSessionDetail(sessionId)).exercises.single().sets
        assertEquals(3, sets.size)
        sets.forEach { set ->
            assertEquals(20.0, set.targetLoad ?: -1.0, 0.0)
            assertEquals(10, set.targetMeasurement)
        }
    }

    @Test
    fun `exercise in routines requires confirmation and is removed atomically`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Ejercicio temporal", "Temporary exercise"))
        val routineId = training.createRoutine("Rutina", null, null)
        training.addRoutineExercise(routineId, exerciseId)

        assertThrows(RoutineRemovalConfirmationRequired::class.java) {
            runBlocking { maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = false) }
        }

        val result = maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = true)
        assertEquals(ExerciseDeletionResult.Deleted(1), result)
        assertNull(maintenance.getExercise(exerciseId))
        assertEquals(emptyList<String>(), requireNotNull(training.getRoutineDetail(routineId)).exercises.map { it.exerciseId })
    }

    @Test
    fun `exercise referenced by a session is deactivated instead of physically deleted`() = runBlocking {
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val exerciseId = maintenance.createExercise(customInput(group.id, "Ejercicio histórico", "Historical exercise"))
        val sessionType = database.masterDataDao().getSessionTypes().first { it.isActive }
        val sessionId = training.createSession(
            LocalDate.of(2026, 8, 21),
            sessionType.id,
            null,
            null,
            SessionSource.Empty,
        ).sessionId
        training.addExercise(sessionId, exerciseId)

        val result = maintenance.deleteExercise(exerciseId, confirmRoutineRemoval = false)

        assertEquals(ExerciseDeletionResult.Deactivated, result)
        assertFalse(requireNotNull(maintenance.getExercise(exerciseId)).isActive)
        assertEquals(exerciseId, requireNotNull(training.getSessionDetail(sessionId)).exercises.single().exerciseId)
    }

    private fun customInput(
        groupId: String,
        es: String,
        en: String,
        initialSetCount: Int? = null,
        initialLoad: Double? = null,
        initialMeasurement: Int? = null,
    ) = ExerciseMaintenanceInput(
        nameEs = es,
        nameEn = en,
        muscleGroupId = groupId,
        equipmentId = null,
        loadMode = LoadMode.KG_TOTAL,
        measurementUnit = MeasurementUnit.REPETITIONS,
        rirRequired = true,
        initialSetCount = initialSetCount,
        initialLoad = initialLoad,
        initialMeasurement = initialMeasurement,
        description = null,
    )

    private fun com.mundoinformaticacanaria.gymup.data.local.ExerciseEntity.toInput(description: String?) =
        ExerciseMaintenanceInput(
            nameEs = nameEs,
            nameEn = nameEn,
            muscleGroupId = muscleGroupId,
            equipmentId = equipmentId,
            loadMode = defaultLoadMode,
            measurementUnit = defaultMeasurementUnit,
            rirRequired = rirRequired,
            initialSetCount = initialSetCount,
            initialLoad = initialLoad,
            initialMeasurement = initialMeasurement,
            description = description,
        )
}
