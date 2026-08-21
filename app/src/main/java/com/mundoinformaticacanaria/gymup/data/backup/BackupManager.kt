package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveReadResult
import java.time.Instant

/**
 * Boundary between backup archive serialization and the concrete persistence implementation.
 *
 * The persistence adapter is responsible for producing a consistent snapshot and for applying a
 * validated replace-all transaction. BackupManager never asks it to mutate state until the entire
 * ZIP has been parsed and validated successfully.
 */
interface BackupDataSource {
    fun exportFiles(): Map<String, ByteArray>

    fun replaceAll(files: Map<String, ByteArray>)
}

sealed interface BackupImportResult {
    data class Imported(val fileCount: Int) : BackupImportResult
    data class Rejected(val reason: String) : BackupImportResult
}

class BackupManager(
    private val dataSource: BackupDataSource,
) {
    fun export(exportedAt: Instant = Instant.now()): ByteArray =
        BackupArchiveCodec.create(
            files = dataSource.exportFiles(),
            exportedAt = exportedAt,
        )

    fun importReplaceAll(archive: ByteArray): BackupImportResult =
        when (val result = BackupArchiveCodec.readAndValidate(archive)) {
            is BackupArchiveReadResult.Valid -> {
                dataSource.replaceAll(result.files)
                BackupImportResult.Imported(result.files.size)
            }

            is BackupArchiveReadResult.Invalid -> BackupImportResult.Rejected(result.reason)
            is BackupArchiveReadResult.ValidationFailed -> BackupImportResult.Rejected(
                "Backup no válido: ${result.result}",
            )
        }
}
