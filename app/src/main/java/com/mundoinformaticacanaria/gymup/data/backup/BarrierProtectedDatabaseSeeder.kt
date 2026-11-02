package com.mundoinformaticacanaria.gymup.data.backup

/**
 * Coordinates initial catalog seeding with the same domain-write barrier used by backup.
 *
 * This is an isolated composition adapter, not installed in AppContainer. A production
 * startup must first complete recovery through BackupStartupGate and only then invoke
 * this guarded seed. Holding the barrier alone does NOT prevent startup from running
 * before recovery, nor does it cover direct DAO writes elsewhere.
 *
 * The backup path must not invoke this adapter while holding the non-reentrant barrier.
 */
internal class BarrierProtectedDatabaseSeeder(
    private val seedIfNeededUnprotected: suspend () -> Unit,
    private val barrier: BackupDomainWriteBarrier,
) {
    suspend fun seedIfNeeded() {
        barrier.withDomainWrite { seedIfNeededUnprotected() }
    }
}
