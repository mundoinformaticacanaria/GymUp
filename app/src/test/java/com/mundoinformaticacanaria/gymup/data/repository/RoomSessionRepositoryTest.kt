package com.mundoinformaticacanaria.gymup.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceInput
import com.mundoinformaticacanaria.gymup.domain.repository.MissingRirException
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSource
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSessionRepositoryTest {
    private lateinit var database: GymUpDatabase
    private lateinit var context: Context
    private lateinit var repository: RoomSessionRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseSeeder(context, database).seedIfNeeded()
        repository = RoomSessionRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun realDataTransitionsSessionRirBlocksFinalizationAndCompletedHistoryPreloadsNextTargets() = runBlocking {
        val type = database.masterDataDao().getSessionTypes().first()
        val exercise = database.exerciseDao().getAll().first { it.rirRequired }
        val firstDate = LocalDate.of(2026, 8, 20)
        val firstSessionId = repository.createSession(firstDate, type.id, null, null, SessionSource.Empty).sessionId
        repository.addExercise(firstSessionId, exercise.id)
        var firstDetail = requireNotNull(repository.getSessionDetail(firstSessionId))
        val firstExercise = firstDetail.exercises.single()
        if (firstExercise.sets.isEmpty()) repository.addSet(firstExercise.id)
        firstDetail = requireNotNull(repository.getSessionDetail(firstSessionId))
        val firstSet = firstDetail.exercises.single().sets.first()

        repository.updateSetTargets(
            firstSet.id,
            targetLoad = if (exercise.defaultLoadMode.name == "NO_WEIGHT" || exercise.defaultLoadMode.name == "BODYWEIGHT") null else 10.0,
            targetMeasurement = 10,
            loadMode = exercise.defaultLoadMode,
            measurementUnit = exercise.defaultMeasurementUnit,
        )
        repository.fulfillSet(firstSet.id)

        val started = requireNotNull(repository.getSessionDetail(firstSessionId)).summary
        assertEquals(SessionOperationalState.IN_PROGRESS, started.operationalState)
        assertEquals(SessionExecutionResult.COMPLETED, started.executionResult)

        assertThrows(MissingRirException::class.java) {
            runBlocking { repository.finalizeSession(firstSessionId) }
        }

        val afterFulfilled = requireNotNull(repository.getSessionDetail(firstSessionId)).exercises.single().sets.first()
        repository.updateSetActual(afterFulfilled.id, afterFulfilled.actualLoad, afterFulfilled.actualMeasurement, 2)
        repository.finalizeSession(firstSessionId)
        val realized = requireNotNull(repository.getSessionDetail(firstSessionId)).summary
        assertEquals(SessionOperationalState.REALIZED, realized.operationalState)
        assertEquals(SessionExecutionResult.COMPLETED, realized.executionResult)

        val secondSessionId = repository.createSession(firstDate.plusDays(1), type.id, null, null, SessionSource.Empty).sessionId
        repository.addExercise(secondSessionId, exercise.id)
        val secondSet = requireNotNull(repository.getSessionDetail(secondSessionId)).exercises.single().sets.first()
        assertEquals(afterFulfilled.actualLoad, secondSet.targetLoad)
        assertEquals(afterFulfilled.actualMeasurement, secondSet.targetMeasurement)
        assertEquals(false, secondSet.actualConfirmed)
    }

    @Test
    fun masterDefaultsPersistAndPreloadWhenExerciseIsAddedWithoutPriorHistory() = runBlocking {
        val maintenance = RoomCatalogMaintenanceRepository(context, database)
        val group = database.masterDataDao().getMuscleGroups().first { it.isActive }
        val type = database.masterDataDao().getSessionTypes().first { it.isActive }
        val exerciseId = maintenance.createExercise(
            ExerciseMaintenanceInput(
                nameEs = "Prueba defaults",
                nameEn = "Defaults test",
                muscleGroupId = group.id,
                equipmentId = null,
                loadMode = LoadMode.KG_TOTAL,
                measurementUnit = MeasurementUnit.REPETITIONS,
                rirRequired = true,
                initialSetCount = 3,
                initialLoad = 42.5,
                initialMeasurement = 8,
                description = "Ejercicio para validar precarga",
            ),
        )

        val stored = requireNotNull(maintenance.getExercise(exerciseId))
        assertEquals(3, stored.initialSetCount)
        assertEquals(42.5, stored.initialLoad ?: 0.0, 0.0)
        assertEquals(8, stored.initialMeasurement)

        val sessionId = repository.createSession(
            LocalDate.of(2026, 8, 21),
            type.id,
            null,
            null,
            SessionSource.Empty,
        ).sessionId
        repository.addExercise(sessionId, exerciseId)

        val planned = requireNotNull(repository.getSessionDetail(sessionId)).exercises.single()
        assertEquals(3, planned.sets.size)
        planned.sets.forEachIndexed { index, set ->
            assertEquals(index + 1, set.position)
            assertEquals(42.5, set.targetLoad ?: 0.0, 0.0)
            assertEquals(8, set.targetMeasurement)
            assertEquals(false, set.actualConfirmed)
        }
    }

    @Test
    fun movingSessionToAnotherDateCompactsOriginAndDestinationOrders() = runBlocking {
        val type = database.masterDataDao().getSessionTypes().first()
        val origin = LocalDate.of(2026, 8, 20)
        val destination = origin.plusDays(1)
        val first = repository.createSession(origin, type.id, "A", null, SessionSource.Empty).sessionId
        val moving = repository.createSession(origin, type.id, "B", null, SessionSource.Empty).sessionId
        val third = repository.createSession(origin, type.id, "C", null, SessionSource.Empty).sessionId
        repository.createSession(destination, type.id, "D", null, SessionSource.Empty)

        repository.changeSessionPosition(moving, destination, 1)

        val originSessions = repository.observeSessionsForDate(origin).first()
        val destinationSessions = repository.observeSessionsForDate(destination).first()
        assertEquals(listOf(first, third), originSessions.map { it.id })
        assertEquals(listOf(1, 2), originSessions.map { it.orderInDay })
        assertEquals(moving, destinationSessions.first().id)
        assertEquals(listOf(1, 2), destinationSessions.map { it.orderInDay })
    }
}

// Keeps the date-order regression test on the branch while the repository fix is applied.
