package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository

/**
 * Serializes the favorite flag mutation with a future exclusive backup/recovery.
 *
 * ExerciseCatalogRepository exposes only one mutation; reads and flows remain delegated.
 * The same [BackupDomainWriteBarrier] must guard every other Room, image and DataStore
 * writer before this adapter can be installed in AppContainer.
 *
 * Isolated composition building block only. Backup must use unwrapped storage
 * adapters because [BackupDomainWriteBarrier] is not reentrant.
 */
internal class BarrierProtectedExerciseCatalogRepository(
    private val delegate: ExerciseCatalogRepository,
    private val barrier: BackupDomainWriteBarrier,
) : ExerciseCatalogRepository by delegate {
    override suspend fun setFavorite(exerciseId: String, favorite: Boolean) {
        barrier.withDomainWrite { delegate.setFavorite(exerciseId, favorite) }
    }
}
