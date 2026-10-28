package com.mundoinformaticacanaria.gymup.data.backup

import java.util.concurrent.ConcurrentHashMap
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
 * Coordinators targeting the same canonical journal path share an in-process mutex. This does
 * not protect direct repository writes, external processes, or the app's startup UI/seeder; a
 * global domain write barrier is still required before production wiring.
 */
internal class BackupRecoveryCoordinator(
    private val journal: BackupRecoveryJournal,
    // Deterministic crash-window seam: production uses the no-op default.
    private val afterRestoreBeforeCommit: suspend () -> Unit = {},
) {
    private val mutex = locks.computeIfAbsent(journal.lockKey) { Mutex() }

    private companion object {
        // Multiple wrappers for the same journal must not independently inspect/modify PREPARED.
        // The map is process-local; it deliberately does not claim cross-process safety.
        val locks = ConcurrentHashMap<String, Mutex>()
    }

    /**
     * Serializes backup exports with replacements on this coordinator instance and refuses to
     * expose a snapshot while recovery is pending. This is NOT an app-wide write barrier:
     * production must also coordinate all repositories and startup before using this adapter.
     */
    suspend fun <T> readSnapshotIfRecovered(readSnapshot: suspend () -> T): T =
        mutex.withLock {
            journal.verifyPathSafety()
            // COMMITTED already makes the new (or restored) domain authoritative.
            // A failed best-effort journal cleanup must not block a read-only export;
            // PREPARED and corrupt/unknown markers still fail closed.
            check(journal.inspect() !is BackupRecoveryJournal.State.Prepared) {
                "Existe una recuperación pendiente: no se puede exportar"
            }
            readSnapshot()
        }

    suspend fun recoverBeforeDataAccess(restorePrevious: suspend (ByteArray) -> Unit) {
        mutex.withLock {
            journal.verifyPathSafety()
            runCatching { journal.cleanupRetired() }
            journal.cleanupAbandonedPreparation()
            when (val state = journal.inspect()) {
                BackupRecoveryJournal.State.Absent -> Unit
                BackupRecoveryJournal.State.Committed -> runCatching { journal.cleanupCommitted() }.getOrNull()
                is BackupRecoveryJournal.State.Prepared -> {
                    // Do not catch restoration failures or cancellation: keep the pre-image.
                    restorePrevious(state.previousArchive)
                    // A crash after replay but before COMMITTED must leave PREPARED intact.
                    // Tests inject a failure here; production performs no additional work.
                    afterRestoreBeforeCommit()
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
            journal.verifyPathSafety()
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
                // Only PREPARED authorizes rollback. A failure syncing the directory after
                // publishing COMMITTED can leave that marker visible, even though fsync
                // reported an error. Restoring the old data beneath COMMITTED would make
                // startup skip recovery and could leave a partially restored domain.
                // An unreadable marker is equally ambiguous: fail closed and preserve it.
                if (runCatching { journal.inspect() }.getOrNull() !is BackupRecoveryJournal.State.Prepared) {
                    throw failure
                }
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
