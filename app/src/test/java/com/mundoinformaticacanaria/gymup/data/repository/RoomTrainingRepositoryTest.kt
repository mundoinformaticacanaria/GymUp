package com.mundoinformaticacanaria.gymup.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.repository.MissingRirException
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSource
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomTrainingRepositoryTest {
    private lateinit var database: GymUpDatabase
    private lateinit var context: Context
    private lateinit var repository: RoomTrainingRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseSeeder(context, database).seedIfNeeded()
        repository = RoomTrainingRepository(database)
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
