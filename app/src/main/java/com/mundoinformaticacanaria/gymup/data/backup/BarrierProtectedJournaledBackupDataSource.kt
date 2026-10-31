package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Coordinates the complete journaled backup operation with cooperating domain writers.
 *
 * The SAME [BackupDomainWriteBarrier] must guard every mutation of Room, private image files
 * and DataStore before this wrapper can be used in production. The delegate must use raw,
 * unwrapped storage adapters: the mutex is not reentrant, so invoking a guarded repository
 * from inside a backup operation would deadlock.
 *
 * Installed by DefaultAppContainer in PR #57. A failed import triggers a best-effort
 * recovery before releasing the exclusive barrier. If that recovery also fails, further
 * cooperating writes/exports are blocked until recovery succeeds (normally on restart).
 * This does not stop already-composed UI readers or protect other processes.
 */
internal class BarrierProtectedJournaledBackupDataSource(
    private val delegate: JournaledBackupDataSource,
    private val barrier: BackupDomainWriteBarrier,
) : BackupDataSource {
    /** Must finish before any repository, seeder or UI can access the persisted domain. */
    suspend fun recoverBeforeDataAccess() {
        barrier.withExclusiveBackup {
            // Startup recovery can fail even before any import in this process. Keep the
            // shared barrier closed until a complete replay has actually succeeded.
            // This also protects callers that retry recovery after initial startup.
            barrier.markRecoveryRequired()
            delegate.recoverBeforeDataAccess()
            barrier.markRecovered()
        }
    }

    override suspend fun exportSnapshot(): BackupSnapshot =
        barrier.withExclusiveBackup {
            barrier.ensureRecovered()
            delegate.exportSnapshot()
        }

    override suspend fun replaceAll(snapshot: BackupSnapshot) {
        barrier.withExclusiveBackup {
            barrier.ensureRecovered()
            try {
                delegate.replaceAll(snapshot)
            } catch (failure: Throwable) {
                // The coordinator tries rollback first. If it leaves PREPARED behind,
                // retry recovery without allowing another domain writer to interleave.
                // Cancellation must not interrupt this final recovery attempt.
                withContext(NonCancellable) {
                    try {
                        delegate.recoverBeforeDataAccess()
                    } catch (recoveryFailure: Throwable) {
                        barrier.markRecoveryRequired()
                        if (recoveryFailure !== failure) failure.addSuppressed(recoveryFailure)
                    }
                }
                throw failure
            }
        }
    }
}
