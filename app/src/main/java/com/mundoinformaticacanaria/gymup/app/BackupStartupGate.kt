package com.mundoinformaticacanaria.gymup.app

import com.mundoinformaticacanaria.gymup.data.backup.BackupDomainWriteBarrier

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fail-closed startup sequence for durable backup recovery.
 *
 * Call [awaitReady] before starting database seeding or exposing any repository/UI access.
 * Concurrent callers wait for the same recovery and seeding sequence; a failed or cancelled
 * attempt never marks startup as ready and may be retried. This is only a sequencing primitive:
 * it does not prevent writes by callers that bypass it, and is NOT yet wired into AppContainer.
 * Production wiring also requires verified durable recovery and gating all data access.
 * When [writeBarrier] is supplied, recovery AND seeding run in one exclusive section.
 * Callbacks must use RAW, unwrapped stores: nested barrier acquisition would deadlock.
 * The optional barrier is still NOT installed in AppContainer, and callers bypassing
 * the startup gate remain unprotected.
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
