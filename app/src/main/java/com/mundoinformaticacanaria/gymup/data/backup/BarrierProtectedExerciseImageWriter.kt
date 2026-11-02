package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageItem

/**
 * Coordinates user-image mutations (private files AND their Room metadata) with backup.
 *
 * Supply ExerciseImageManager::addUserImage and ::deleteUserImage as the unwrapped
 * delegates. The barrier covers the complete operation, not just its DAO call.
 *
 * This is an isolated composition adapter: ExerciseImageManager is still exposed
 * directly by AppContainer and its UI callers bypass this class. Do NOT install the
 * journaled backup until all those callers, other DAO writes and DataStore mutations
 * use the SAME barrier, and startup recovery runs before data access.
 *
 * Backup/recovery must use unwrapped delegates while holding the non-reentrant
 * exclusive barrier. Reads are intentionally not intercepted.
 */
internal class BarrierProtectedExerciseImageWriter(
    private val addUserImageUnprotected: suspend (String, ByteArray) -> ExerciseImageItem,
    private val deleteUserImageUnprotected: suspend (String) -> Unit,
    private val barrier: BackupDomainWriteBarrier,
) {
    suspend fun addUserImage(exerciseId: String, bytes: ByteArray): ExerciseImageItem =
        barrier.withDomainWrite { addUserImageUnprotected(exerciseId, bytes) }

    suspend fun deleteUserImage(imageId: String) {
        barrier.withDomainWrite { deleteUserImageUnprotected(imageId) }
    }
}
