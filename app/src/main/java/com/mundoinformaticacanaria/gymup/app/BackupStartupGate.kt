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
 * This gate does not protect bypass writers, reads during hot import, other processes,
 * or abrupt process/power loss. Those remain separate #56 verification gates.
 */
internal class BackupStartupGate(
    private val recover: suspend () -> Unit,
    private val seed: suspend () -> Unit,
    private val writeBarrier: BackupDomainWriteBarrier? = null,
) {
    private val mutex = Mutex()
    private var ready = false

    suspend fun awaitReady() {
        mutex.withLock {
            if (ready) return@withLock
            if (writeBarrier == null) {
                recover()
                seed()
            } else {
                // Hold one lock across BOTH phases: a writer cannot interleave between
                // restoring the previous state and seeding the recovered database.
                writeBarrier.withExclusiveBackup {
                    recover()
                    seed()
                }
            }
            ready = true
        }
    }
}
