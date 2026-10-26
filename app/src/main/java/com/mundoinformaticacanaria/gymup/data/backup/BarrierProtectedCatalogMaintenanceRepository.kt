package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.repository.CatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionResult
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceInput
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogKind

/**
 * Coordinates master and exercise maintenance mutations with the backup write barrier.
 *
 * Deletion can affect both Room and private exercise images; the delegate must finish
 * the entire operation before the shared barrier is released. Read-only operations
 * are delegated without locking.
 *
 * Isolated building block only: not installed in AppContainer. Backup/recovery must
 * use the unwrapped storage path because the barrier mutex is not reentrant.
 */
internal class BarrierProtectedCatalogMaintenanceRepository(
    private val delegate: CatalogMaintenanceRepository,
    private val barrier: BackupDomainWriteBarrier,
) : CatalogMaintenanceRepository by delegate {
    override suspend fun createMaster(kind: MasterCatalogKind, name: String): String =
        barrier.withDomainWrite { delegate.createMaster(kind, name) }

    override suspend fun renameMaster(kind: MasterCatalogKind, id: String, name: String) {
        barrier.withDomainWrite { delegate.renameMaster(kind, id, name) }
    }

    override suspend fun deactivateMaster(kind: MasterCatalogKind, id: String) {
        barrier.withDomainWrite { delegate.deactivateMaster(kind, id) }
    }

    override suspend fun createExercise(input: ExerciseMaintenanceInput): String =
        barrier.withDomainWrite { delegate.createExercise(input) }

    override suspend fun updateExercise(exerciseId: String, input: ExerciseMaintenanceInput) {
        barrier.withDomainWrite { delegate.updateExercise(exerciseId, input) }
    }

    override suspend fun deleteExercise(
        exerciseId: String,
        confirmRoutineRemoval: Boolean,
    ): ExerciseDeletionResult = barrier.withDomainWrite {
        delegate.deleteExercise(exerciseId, confirmRoutineRemoval)
    }
}
