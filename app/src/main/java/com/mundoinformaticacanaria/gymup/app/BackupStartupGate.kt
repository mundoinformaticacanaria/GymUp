package com.mundoinformaticacanaria.gymup.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fail-closed startup sequence for durable backup recovery.
 *
 * Call [awaitReady] before starting database seeding or exposing any repository/UI access.
 * Concurrent callers wait for the same recovery and seeding sequence; a failed or cancelled
 * attempt never marks startup as ready and may be retried. This is only a sequencing primitive:
 * it does not prevent writes by callers that bypass it, and is NOT yet wired into AppContainer.
 * Production wiring also requires a domain-wide write barrier and verified durable recovery.
 */
internal class BackupStartupGate(
    private val recover: suspend () -> Unit,
    private val seed: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var ready = false

    suspend fun awaitReady() {
        mutex.withLock {
            if (ready) return@withLock
            recover()
            seed()
            ready = true
        }
    }
}
