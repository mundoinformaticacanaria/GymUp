package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.repository.RoutineDetail
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRepository
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarrierProtectedRoutineRepositoryTest {
    @Test
    fun `save waits for backup to release shared barrier`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeRoutineRepository()
        val guarded = BarrierProtectedRoutineRepository(delegate, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backup = launch {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val save = launch { guarded.saveRoutine(null, "Rutina", null, null, listOf("exercise")) }
        yield()
        assertFalse(save.isCompleted)
        assertEquals(0, delegate.saves)

        release.complete(Unit)
        backup.join()
        save.join()
        assertEquals(1, delegate.saves)
    }

    @Test
    fun `delete waits for backup to release shared barrier`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeRoutineRepository()
        val guarded = BarrierProtectedRoutineRepository(delegate, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backup = launch {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val delete = launch { guarded.deleteRoutine("routine") }
        yield()
        assertFalse(delete.isCompleted)
        assertEquals(0, delegate.deletes)

        release.complete(Unit)
        backup.join()
        delete.join()
        assertEquals(1, delegate.deletes)
    }

    @Test
    fun `delegate failure releases barrier for subsequent backup`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeRoutineRepository(failSave = true)
        val guarded = BarrierProtectedRoutineRepository(delegate, barrier)

        val result = runCatching {
            guarded.saveRoutine(null, "Rutina", null, null, emptyList())
        }
        assertTrue(result.isFailure)
        assertEquals("ready", barrier.withExclusiveBackup { "ready" })
    }

    private class FakeRoutineRepository(
        private val failSave: Boolean = false,
    ) : RoutineRepository {
        var saves = 0
        var deletes = 0

        override fun observeRoutines(): Flow<List<RoutineSummary>> = flowOf(emptyList())
        override suspend fun getRoutineDetail(routineId: String): RoutineDetail? = null

        override suspend fun saveRoutine(
            routineId: String?,
            name: String,
            suggestedSessionTypeId: String?,
            description: String?,
            orderedExerciseIds: List<String>,
        ): String {
            if (failSave) error("simulated save failure")
            saves++
            return routineId ?: "new-routine"
        }

        override suspend fun deleteRoutine(routineId: String) {
            deletes++
        }
    }
}
