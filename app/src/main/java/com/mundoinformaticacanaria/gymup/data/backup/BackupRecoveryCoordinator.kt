package com.mundoinformaticacanaria.gymup.data.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
            runCatching { journal.cleanupRetired() }
            journal.cleanupAbandonedPreparation()
            when (val state = journal.inspect()) {
                BackupRecoveryJournal.State.Absent -> Unit
                BackupRecoveryJournal.State.Committed -> runCatching { journal.cleanupCommitted() }.getOrNull()
                is BackupRecoveryJournal.State.Prepared -> {
                    // Do not catch restoration failures or cancellation: keep the pre-image.
                    restorePrevious(state.previousArchive)
                    // A crash here may cause another restore; restorePrevious must be idempotent.
                    journal.markCommitted()
                    // Successful recovery must not be invalidated by obsolete-file cleanup failure.
                    runCatching { journal.cleanupCommitted() }
                }
            }
        }
    }

    /**
     * Orchestrates a reversible replace-all around a durable pre-image.
     *
     * Not yet connected to the real import path: callers must ensure an app-wide write barrier,
     * a consistent pre-image and a durable/idempotent [restorePrevious] implementation.
     * If restoration fails, PREPARED is retained for startup recovery. A failed cleanup after
     * successful commit leaves COMMITTED for the next startup; it is not a failed import.
     */
    suspend fun replaceWithRecovery(
        capturePrevious: suspend () -> ByteArray,
        applyReplacement: suspend () -> Unit,
        restorePrevious: suspend (ByteArray) -> Unit,
    ) {
        mutex.withLock {
            runCatching { journal.cleanupRetired() }
            journal.cleanupAbandonedPreparation()
            check(journal.inspect() is BackupRecoveryJournal.State.Absent) {
                "Existe una recuperación pendiente: no se puede importar"
            }
            val previous = capturePrevious()
            journal.prepare(previous)
            try {
                applyReplacement()
                journal.markCommitted()
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try {
                        restorePrevious(previous)
                        journal.markCommitted()
                    } catch (recoveryFailure: Throwable) {
                        failure.addSuppressed(recoveryFailure)
                    }
                }
                throw failure
            }
            // Committed data is authoritative even if obsolete journal cleanup cannot complete.
            runCatching { journal.cleanupCommitted() }
        }
    }
}
