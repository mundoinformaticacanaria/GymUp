package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot

/**
 * Connects the durable recovery coordinator to the existing backup data source.
 *
 * Wired by DefaultAppContainer in PR #57 behind a shared process-local write barrier and
 * startup recovery gate. The delegate remains raw to avoid reentering the non-reentrant mutex.
 * This does not protect UI readers during hot imports, bypass writers, other processes or
 * power-loss durability. The PR remains experimental until those gates are resolved.
 */
internal class JournaledBackupDataSource(
    private val delegate: BackupDataSource,
    private val coordinator: BackupRecoveryCoordinator,
    appVersion: String,
) : BackupDataSource {
    private val snapshotAdapter = BackupRecoverySnapshotAdapter(delegate, appVersion)

    /** Must run successfully before exposing any domain data after process restart. */
    suspend fun recoverBeforeDataAccess() {
        coordinator.recoverBeforeDataAccess(snapshotAdapter::restorePrevious)
    }

    override suspend fun exportSnapshot(): BackupSnapshot =
        coordinator.readSnapshotIfRecovered { delegate.exportSnapshot() }

    override suspend fun replaceAll(snapshot: BackupSnapshot) {
        coordinator.replaceWithRecovery(
            capturePrevious = snapshotAdapter::capturePrevious,
            applyReplacement = { delegate.replaceAll(snapshot) },
            restorePrevious = snapshotAdapter::restorePrevious,
        )
    }
}
