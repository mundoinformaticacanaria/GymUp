package com.mundoinformaticacanaria.gymup.app

import com.mundoinformaticacanaria.gymup.data.backup.BackupDomainWriteBarrier

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fail-closed startup sequence for durable backup recovery.
 *
 * DefaultAppContainer calls [awaitReady] before composing the repository-backed UI.
 * Concurrent callers wait for the same recovery and seeding sequence; a failed or cancelled
 * attempt never marks startup as ready and may be retried. Recovery and seeding share the
 * production write barrier; callbacks must use raw stores to avoid nested mutex acquisition.
 * Orphan-image reconciliation runs after recovery/seeding, under the same lock, before ready.
 * This gate does not protect bypass writers, reads during hot import, other processes,
 * or abrupt process/power loss. Those remain separate #56 verification gates.
 */
internal class BackupStartupGate(
    private val recover: suspend () -> Unit,
    private val seed: suspend () -> Unit,
    private val writeBarrier: BackupDomainWriteBarrier? = null,
    private val reconcileImages: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private var ready = false

    suspend fun awaitReady() {
        mutex.withLock {
            if (ready) return@withLock
            if (writeBarrier == null) {
                recover()
                seed()
                reconcileImages()
            } else {
                // Hold the same barrier across recovery, seeding and reconciliation.
                // A failed or cancelled startup must keep cooperating writers blocked:
                // awaiting the UI gate is not a guarantee for callers holding repositories.
                writeBarrier.withExclusiveBackup {
                    writeBarrier.markRecoveryRequired()
                    recover()
                    seed()
                    reconcileImages()
                    writeBarrier.markRecovered()
                }
            }
            ready = true
        }
    }
}
