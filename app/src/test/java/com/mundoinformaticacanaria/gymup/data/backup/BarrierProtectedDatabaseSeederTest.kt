package com.mundoinformaticacanaria.gymup.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarrierProtectedDatabaseSeederTest {
    @Test
    fun seedWaitsForExclusiveBackup() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var seeds = 0
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val guarded = BarrierProtectedDatabaseSeeder({ seeds++ }, barrier)
        val seeding = launch(start = CoroutineStart.UNDISPATCHED) { guarded.seedIfNeeded() }

        assertFalse(seeding.isCompleted)
        assertEquals(0, seeds)
        release.complete(Unit)
        backup.join()
        seeding.join()
        assertEquals(1, seeds)
    }

    @Test
    fun backupWaitsUntilSeedingFinishes() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val guarded = BarrierProtectedDatabaseSeeder({
            events += "seed-start"
            entered.complete(Unit)
            release.await()
            events += "seed-end"
        }, barrier)
        val seeding = launch(start = CoroutineStart.UNDISPATCHED) { guarded.seedIfNeeded() }
        entered.await()
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup { events += "backup" }
        }

        assertFalse(backup.isCompleted)
        release.complete(Unit)
        seeding.join()
        backup.join()
        assertEquals(listOf("seed-start", "seed-end", "backup"), events)
    }

    @Test
    fun cancelledSeedWaitingForBackupNeverWrites() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var seeds = 0
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val guarded = BarrierProtectedDatabaseSeeder({ seeds++ }, barrier)
        val seeding = launch(start = CoroutineStart.UNDISPATCHED) { guarded.seedIfNeeded() }
        seeding.cancelAndJoin()
        release.complete(Unit)
        backup.join()
        assertEquals(0, seeds)
    }

    @Test
    fun seedingFailureReleasesBarrier() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val guarded = BarrierProtectedDatabaseSeeder({ error("simulated seeding failure") }, barrier)

        assertTrue(runCatching { guarded.seedIfNeeded() }.isFailure)
        assertEquals("available", barrier.withExclusiveBackup { "available" })
    }
}
