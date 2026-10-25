package com.mundoinformaticacanaria.gymup.data.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Startup recovery state machine for an interrupted replace-all.
 *
 * This is intentionally not wired into application startup yet. The caller must block *all*
 * repository access and database seeding until [recoverBeforeDataAccess] returns successfully.
 * [restorePrevious] must validate the saved archive, restore Room, images and preferences, and
 * confirm that the restored state is durable before returning. A failed or cancelled restoration
 * leaves PREPARED intact for a retry on the next startup.
 *
 * The mutex only serializes calls on this instance; app-wide writes and multiple processes still
 * require separate exclusion before this coordinator can be used in production.
 */
internal class BackupRecoveryCoordinator(private val journal: BackupRecoveryJournal) {
    private val mutex = Mutex()

    suspend fun recoverBeforeDataAccess(restorePrevious: suspend (ByteArray) -> Unit) {
        mutex.withLock {
            when (val state = journal.inspect()) {
                BackupRecoveryJournal.State.Absent -> Unit
                BackupRecoveryJournal.State.Committed -> journal.cleanupCommitted()
                is BackupRecoveryJournal.State.Prepared -> {
                    // Do not catch restoration failures or cancellation: keep the pre-image.
                    restorePrevious(state.previousArchive)
                    // A crash here may cause another restore; restorePrevious must be idempotent.
                    journal.markCommitted()
                    journal.cleanupCommitted()
                }
            }
        }
    }
}
