package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRepository

/**
 * Coordinates complete routine mutations with a future journaled backup path.
 *
 * The same [BackupDomainWriteBarrier] must be used by all Room, image and preference
 * writers and the backup coordinator before this wrapper is installed in AppContainer.
 * Reads remain delegated. Do not pass this guarded adapter into the raw backup
 * data source: [BackupDomainWriteBarrier] is non-reentrant.
 *
 * This is an isolated composition building block, NOT a production-wide barrier.
 */
internal class BarrierProtectedRoutineRepository(
    private val delegate: RoutineRepository,
    private val barrier: BackupDomainWriteBarrier,
) : RoutineRepository by delegate {
    override suspend fun saveRoutine(
        routineId: String?,
        name: String,
        suggestedSessionTypeId: String?,
        description: String?,
        orderedExerciseIds: List<String>,
    ): String = barrier.withDomainWrite {
        delegate.saveRoutine(routineId, name, suggestedSessionTypeId, description, orderedExerciseIds)
    }

    override suspend fun deleteRoutine(routineId: String) {
        barrier.withDomainWrite { delegate.deleteRoutine(routineId) }
    }
}
