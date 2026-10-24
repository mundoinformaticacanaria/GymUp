package com.mundoinformaticacanaria.gymup.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSource
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomHistoryRepositoryTest {
    private lateinit var database: GymUpDatabase
    private lateinit var context: Context
    private lateinit var training: RoomSessionRepository
    private lateinit var routines: RoomRoutineRepository
    private lateinit var history: RoomHistoryRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java).allowMainThreadQueries().build()
        DatabaseSeeder(context, database).seedIfNeeded()
        training = RoomSessionRepository(database)
        routines = RoomRoutineRepository(database)
        history = RoomHistoryRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun historyIncludesPartialAndCompletedButOnlyActualSetsAndTracksLatestUsageAndRoutine() = runBlocking {
        val type = database.masterDataDao().getSessionTypes().first()
        val exercise = database.exerciseDao().getAll().first()
        val firstDate = LocalDate.of(2026, 8, 19)
        val secondDate = LocalDate.of(2026, 8, 20)

        val partialSession = training.createSession(firstDate, type.id, "Parcial", null, SessionSource.Empty).sessionId
        training.addExercise(partialSession, exercise.id)
        var partialDetail = requireNotNull(training.getSessionDetail(partialSession))
        val partialExerciseId = partialDetail.exercises.single().id
        while (requireNotNull(training.getSessionDetail(partialSession)).exercises.single().sets.size < 2) {
            training.addSet(partialExerciseId)
        }
        partialDetail = requireNotNull(training.getSessionDetail(partialSession))
        val partialSet = partialDetail.exercises.single().sets.first()
        training.updateSetActual(partialSet.id, 10.0, 10, 1)

        val completedSession = training.createSession(secondDate, type.id, "Completada", null, SessionSource.Empty).sessionId
        training.addExercise(completedSession, exercise.id)
        var completedDetail = requireNotNull(training.getSessionDetail(completedSession))
        val completedExerciseId = completedDetail.exercises.single().id
        if (completedDetail.exercises.single().sets.isEmpty()) training.addSet(completedExerciseId)
        completedDetail = requireNotNull(training.getSessionDetail(completedSession))
        completedDetail.exercises.single().sets.forEach { set ->
            training.updateSetActual(set.id, 12.5, 12, if (exercise.rirRequired) 2 else null)
        }

        routines.saveRoutine(null, "Rutina", type.id, null, listOf(exercise.id))

        val result = requireNotNull(history.getExerciseHistory(exercise.id, 10))
        assertEquals(2, result.executions.size)
        assertEquals(secondDate, result.executions.first().date)
        assertEquals(ExerciseExecutionStatus.COMPLETED, result.executions.first().status)
        assertEquals(ExerciseExecutionStatus.PARTIAL, result.executions.last().status)
        assertEquals(1, result.executions.last().sets.size)

        val metadata = history.observeExerciseSearchMetadata().first().getValue(exercise.id)
        assertEquals(secondDate, metadata.lastExecutionDate)
        assertTrue(metadata.inRoutine)
    }
}
