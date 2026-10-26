package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot

/**
 * Coordinates the complete journaled backup operation with cooperating domain writers.
 *
 * The SAME [BackupDomainWriteBarrier] must guard every mutation of Room, private image files
 * and DataStore before this wrapper can be used in production. The delegate must use raw,
 * unwrapped storage adapters: the mutex is not reentrant, so invoking a guarded repository
 * from inside a backup operation would deadlock.
 *
 * Not installed in AppContainer. This is a composition building block, not evidence of a
 * global write barrier or safe startup recovery. Other processes are not covered.
 */
internal class BarrierProtectedJournaledBackupDataSource(
    private val delegate: JournaledBackupDataSource,
    private val barrier: BackupDomainWriteBarrier,
) : BackupDataSource {
    /** Must finish before any repository, seeder or UI can access the persisted domain. */
    suspend fun recoverBeforeDataAccess() {
        barrier.withExclusiveBackup { delegate.recoverBeforeDataAccess() }
    }

    override suspend fun exportSnapshot(): BackupSnapshot =
        barrier.withExclusiveBackup { delegate.exportSnapshot() }

    override suspend fun replaceAll(snapshot: BackupSnapshot) {
        barrier.withExclusiveBackup { delegate.replaceAll(snapshot) }
    }
}
