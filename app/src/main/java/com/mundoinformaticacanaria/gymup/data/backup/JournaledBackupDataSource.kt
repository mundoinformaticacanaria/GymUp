package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot

/**
 * Connects the durable recovery coordinator to the existing backup data source.
 *
 * This adapter is not installed in AppContainer yet. Production wiring MUST first provide a
 * global write barrier, consistent pre-image capture and a startup gate before database seeding
 * or any repository/UI access. Using it for imports without startup recovery would be unsafe.
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
