package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionPreview
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionResult
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceData
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceInput
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogKind
import com.mundoinformaticacanaria.gymup.domain.repository.MasterMaintenanceItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarrierProtectedCatalogMaintenanceRepositoryTest {
    private val input = ExerciseMaintenanceInput(
        nameEs = "Ejercicio",
        nameEn = "Exercise",
        muscleGroupId = "group",
        equipmentId = null,
        loadMode = LoadMode.entries.first(),
        measurementUnit = MeasurementUnit.entries.first(),
        rirRequired = true,
        initialSetCount = null,
        initialLoad = null,
        initialMeasurement = null,
        description = null,
    )

    @Test
    fun allSixMutationsWaitForBackupExclusiveLock() = runBlocking {
        val operations: List<suspend (CatalogMaintenanceRepository) -> Unit> = listOf(
            { it.createMaster(MasterCatalogKind.SESSION_TYPE, "Nuevo"); Unit },
            { it.renameMaster(MasterCatalogKind.SESSION_TYPE, "master", "Nuevo"); Unit },
            { it.deactivateMaster(MasterCatalogKind.SESSION_TYPE, "master") },
            { it.createExercise(input); Unit },
            { it.updateExercise("exercise", input) },
            { it.deleteExercise("exercise", confirmRoutineRemoval = true); Unit },
        )

        for (operation in operations) {
            val barrier = BackupDomainWriteBarrier()
            val delegate = FakeCatalogMaintenanceRepository()
            val guarded = BarrierProtectedCatalogMaintenanceRepository(delegate, barrier)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val backup = launch {
                barrier.withExclusiveBackup {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()

            val writer = launch { operation(guarded) }
            yield()
            assertFalse(writer.isCompleted)
            assertEquals(0, delegate.writes)

            release.complete(Unit)
            backup.join()
            writer.join()
            assertEquals(1, delegate.writes)
        }
    }

    @Test
    fun readsRemainAvailableDuringExclusiveBackup() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeCatalogMaintenanceRepository()
        val guarded = BarrierProtectedCatalogMaintenanceRepository(delegate, barrier)

        barrier.withExclusiveBackup {
            assertEquals(null, guarded.getExercise("exercise"))
            assertEquals(0, guarded.previewExerciseDeletion("exercise").historicalReferences)
            assertTrue(guarded.observeMasters(MasterCatalogKind.SESSION_TYPE).first().isEmpty())
        }
        assertEquals(0, delegate.writes)
    }

    @Test
    fun delegateFailureReleasesBarrier() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeCatalogMaintenanceRepository(failWrites = true)
        val guarded = BarrierProtectedCatalogMaintenanceRepository(delegate, barrier)

        val failure = runCatching { guarded.deleteExercise("exercise", confirmRoutineRemoval = true) }
        assertTrue(failure.isFailure)
        assertEquals("ready", barrier.withExclusiveBackup { "ready" })
        assertEquals(0, delegate.writes)
    }

    @Test
    fun cancelledWriterNeverEntersDelegate() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeCatalogMaintenanceRepository()
        val guarded = BarrierProtectedCatalogMaintenanceRepository(delegate, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backup = launch {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            guarded.deleteExercise("exercise", confirmRoutineRemoval = true)
        }
        writer.cancelAndJoin()
        release.complete(Unit)
        backup.join()

        assertEquals(0, delegate.writes)
    }

    private class FakeCatalogMaintenanceRepository(
        private val failWrites: Boolean = false,
    ) : CatalogMaintenanceRepository {
        var writes = 0

        private fun recordWrite() {
            if (failWrites) error("simulated write failure")
            writes++
        }

        override fun observeMasters(kind: MasterCatalogKind): Flow<List<MasterMaintenanceItem>> =
            flowOf(emptyList())

        override suspend fun createMaster(kind: MasterCatalogKind, name: String): String {
            recordWrite()
            return "master"
        }

        override suspend fun renameMaster(kind: MasterCatalogKind, id: String, name: String) {
            recordWrite()
        }

        override suspend fun deactivateMaster(kind: MasterCatalogKind, id: String) {
            recordWrite()
        }

        override suspend fun getExercise(exerciseId: String): ExerciseMaintenanceData? = null

        override suspend fun createExercise(input: ExerciseMaintenanceInput): String {
            recordWrite()
            return "exercise"
        }

        override suspend fun updateExercise(exerciseId: String, input: ExerciseMaintenanceInput) {
            recordWrite()
        }

        override suspend fun previewExerciseDeletion(exerciseId: String): ExerciseDeletionPreview =
            ExerciseDeletionPreview(historicalReferences = 0, routineReferences = 0)

        override suspend fun deleteExercise(
            exerciseId: String,
            confirmRoutineRemoval: Boolean,
        ): ExerciseDeletionResult {
            recordWrite()
            return ExerciseDeletionResult.Deactivated
        }
    }
}
