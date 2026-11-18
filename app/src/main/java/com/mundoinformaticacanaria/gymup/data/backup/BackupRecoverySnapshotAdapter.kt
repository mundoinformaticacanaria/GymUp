package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveReadResult

/**
 * Converts a complete Room/images/preferences snapshot into the durable pre-image consumed by
 * BackupRecoveryCoordinator, and replays it through the existing replace-all validation path.
 *
 * Not connected to application startup or user imports yet. Production use still requires an
 * app-wide write barrier, a consistent snapshot and recovery before seeding or exposing the UI.
 */
internal class BackupRecoverySnapshotAdapter(
    private val dataSource: BackupDataSource,
    private val appVersion: String,
) {
    suspend fun capturePrevious(): ByteArray {
        val archive = BackupArchiveCodec.create(dataSource.exportSnapshot(), appVersion)
        check(BackupArchiveCodec.readAndValidate(archive) is BackupArchiveReadResult.Valid) {
            "No se pudo verificar la copia previa para recuperación"
        }
        return archive
    }

    /**
     * A PREPARED journal must remain intact until this operation finishes. Replaying the same
     * validated archive more than once is allowed when a process dies during recovery.
     */
    suspend fun restorePrevious(archive: ByteArray) {
        val result = BackupArchiveCodec.readAndValidate(archive)
        check(result is BackupArchiveReadResult.Valid) {
            "La copia previa de recuperación no es un backup válido"
        }
        dataSource.replaceAll(result.snapshot)
    }
}
