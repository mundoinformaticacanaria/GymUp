package com.mundoinformaticacanaria.gymup.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
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
class RoomRoutineRepositoryTest {
    private lateinit var database: GymUpDatabase
    private lateinit var repository: RoomRoutineRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseSeeder(context, database).seedIfNeeded()
        repository = RoomRoutineRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `save creates and atomically replaces routine order`() = runBlocking {
        val exercises = database.exerciseDao().getAll().take(3)
        val type = database.masterDataDao().getSessionTypes().first()
        val routineId = repository.saveRoutine(
            routineId = null,
            name = "  Rutina A  ",
            suggestedSessionTypeId = type.id,
            description = "  Prueba  ",
            orderedExerciseIds = exercises.map { it.id },
        )

        val created = requireNotNull(repository.getRoutineDetail(routineId))
        assertEquals("Rutina A", created.routine.name)
        assertEquals("Prueba", created.routine.description)
        assertEquals(exercises.map { it.id }, created.exercises.map { it.exerciseId })

        repository.saveRoutine(
            routineId = routineId,
            name = "Rutina editada",
            suggestedSessionTypeId = null,
            description = "",
            orderedExerciseIds = listOf(exercises[2].id, exercises[0].id),
        )

        val updated = requireNotNull(repository.getRoutineDetail(routineId))
        assertEquals("Rutina editada", updated.routine.name)
        assertEquals(null, updated.routine.description)
        assertEquals(listOf(exercises[2].id, exercises[0].id), updated.exercises.map { it.exerciseId })
    }

    @Test
    fun `duplicate exercises reject the whole save`() = runBlocking {
        val exerciseId = database.exerciseDao().getAll().first().id

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveRoutine(null, "Inválida", null, null, listOf(exerciseId, exerciseId))
            }
        }

        assertEquals(0, repository.observeRoutines().first().size)
    }
}
