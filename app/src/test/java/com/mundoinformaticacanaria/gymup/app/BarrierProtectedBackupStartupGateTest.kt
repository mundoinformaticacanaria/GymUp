package com.mundoinformaticacanaria.gymup.app

import com.mundoinformaticacanaria.gymup.data.backup.BackupDomainWriteBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the exclusive startup composition used by AppContainer. It guards cooperating writers;
 * callers that bypass the barrier and independent processes remain outside this guarantee.
 */
class BarrierProtectedBackupStartupGateTest {
    @Test
    fun `domain writer cannot run between recovery and seeding`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val recovering = CompletableDeferred<Unit>()
        val finishRecovery = CompletableDeferred<Unit>()
        val seeding = CompletableDeferred<Unit>()
        val finishSeeding = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val gate = BackupStartupGate(
            recover = {
                events += "recover-start"
                recovering.complete(Unit)
                finishRecovery.await()
                events += "recover-end"
            },
            seed = {
                events += "seed-start"
                seeding.complete(Unit)
                finishSeeding.await()
                events += "seed-end"
            },
            writeBarrier = barrier,
        )

        val startup = launch { gate.awaitReady() }
        recovering.await()
        val writer = launch { barrier.withDomainWrite { events += "write" } }
        yield()
        assertFalse(writer.isCompleted)

        finishRecovery.complete(Unit)
        seeding.await()
        yield()
        assertFalse(writer.isCompleted)

        finishSeeding.complete(Unit)
        startup.join()
        writer.join()
        assertEquals(
            listOf("recover-start", "recover-end", "seed-start", "seed-end", "write"),
            events,
        )
    }

    @Test
    fun `failed recovery releases barrier and retry still runs before seeding`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        var attempts = 0
        var seeds = 0
        val gate = BackupStartupGate(
            recover = {
                attempts++
                if (attempts == 1) error("simulated recovery failure")
            },
            seed = { seeds++ },
            writeBarrier = barrier,
        )

        assertTrue(runCatching { gate.awaitReady() }.isFailure)
        assertEquals(0, seeds)
        assertTrue(runCatching { barrier.withDomainWrite { "unsafe" } }.isFailure)

        gate.awaitReady()
        gate.awaitReady()
        assertEquals(2, attempts)
        assertEquals(1, seeds)
        assertEquals("unlocked", barrier.withDomainWrite { "unlocked" })
    }
    @Test
    fun `domain writer cannot interleave with orphan image reconciliation`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val enteredCleanup = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val gate = BackupStartupGate(
            recover = { events += "recover" },
            seed = { events += "seed" },
            reconcileImages = {
                events += "cleanup-start"
                enteredCleanup.complete(Unit)
                finishCleanup.await()
                events += "cleanup-end"
            },
            writeBarrier = barrier,
        )

        val startup = launch { gate.awaitReady() }
        enteredCleanup.await()
        val writer = launch { barrier.withDomainWrite { events += "write" } }
        yield()
        assertFalse(writer.isCompleted)
        finishCleanup.complete(Unit)
        startup.join()
        writer.join()
        assertEquals(
            listOf("recover", "seed", "cleanup-start", "cleanup-end", "write"),
            events,
        )
    }

    @Test
    fun `failed seeding keeps cooperating writers blocked until full retry`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        var seedAttempts = 0
        val gate = BackupStartupGate(
            recover = {},
            seed = {
                if (++seedAttempts == 1) error("seed failed after recovery")
            },
            writeBarrier = barrier,
        )

        assertTrue(runCatching { gate.awaitReady() }.isFailure)
        assertTrue(runCatching { barrier.withDomainWrite { "unsafe" } }.isFailure)
        gate.awaitReady()
        assertEquals(2, seedAttempts)
        assertEquals("ready", barrier.withDomainWrite { "ready" })
    }

    @Test
    fun `failed image reconciliation keeps cooperating writers blocked until retry`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        var cleanupAttempts = 0
        val gate = BackupStartupGate(
            recover = {},
            seed = {},
            reconcileImages = {
                if (++cleanupAttempts == 1) error("reconciliation failed")
            },
            writeBarrier = barrier,
        )

        assertTrue(runCatching { gate.awaitReady() }.isFailure)
        assertTrue(runCatching { barrier.withDomainWrite { "unsafe" } }.isFailure)
        gate.awaitReady()
        assertEquals(2, cleanupAttempts)
        assertEquals("ready", barrier.withDomainWrite { "ready" })
    }


    @Test
    fun `cancelled recovery keeps cooperating writers blocked until startup retry`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val enteredRecovery = CompletableDeferred<Unit>()
        val waitForever = CompletableDeferred<Unit>()
        var recoveries = 0
        var seeds = 0
        val gate = BackupStartupGate(
            recover = {
                if (++recoveries == 1) {
                    enteredRecovery.complete(Unit)
                    waitForever.await()
                }
            },
            seed = { seeds++ },
            writeBarrier = barrier,
        )

        val first = launch { gate.awaitReady() }
        enteredRecovery.await()
        first.cancelAndJoin()

        assertEquals(0, seeds)
        assertTrue(runCatching { barrier.withDomainWrite { "unsafe" } }.isFailure)
        gate.awaitReady()
        assertEquals(2, recoveries)
        assertEquals(1, seeds)
        assertEquals("ready", barrier.withDomainWrite { "ready" })
    }

    @Test
    fun `cancelled seeding keeps cooperating writers blocked until full retry`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val enteredSeed = CompletableDeferred<Unit>()
        val waitForever = CompletableDeferred<Unit>()
        var recoveries = 0
        var seeds = 0
        var reconciliations = 0
        val gate = BackupStartupGate(
            recover = { recoveries++ },
            seed = {
                if (++seeds == 1) {
                    enteredSeed.complete(Unit)
                    waitForever.await()
                }
            },
            reconcileImages = { reconciliations++ },
            writeBarrier = barrier,
        )

        val first = launch { gate.awaitReady() }
        enteredSeed.await()
        first.cancelAndJoin()

        assertEquals(1, recoveries)
        assertEquals(1, seeds)
        assertEquals(0, reconciliations)
        assertTrue(runCatching { barrier.withDomainWrite { "unsafe" } }.isFailure)

        gate.awaitReady()
        assertEquals(2, recoveries)
        assertEquals(2, seeds)
        assertEquals(1, reconciliations)
        assertEquals("ready", barrier.withDomainWrite { "ready" })
    }

    @Test
    fun `cancelled reconciliation keeps cooperating writers blocked until full retry`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val enteredReconciliation = CompletableDeferred<Unit>()
        val waitForever = CompletableDeferred<Unit>()
        var recoveries = 0
        var seeds = 0
        var reconciliations = 0
        val gate = BackupStartupGate(
            recover = { recoveries++ },
            seed = { seeds++ },
            reconcileImages = {
                if (++reconciliations == 1) {
                    enteredReconciliation.complete(Unit)
                    waitForever.await()
                }
            },
            writeBarrier = barrier,
        )

        val first = launch { gate.awaitReady() }
        enteredReconciliation.await()
        first.cancelAndJoin()

        assertTrue(runCatching { barrier.withDomainWrite { "unsafe" } }.isFailure)
        gate.awaitReady()
        assertEquals(2, recoveries)
        assertEquals(2, seeds)
        assertEquals(2, reconciliations)
        assertEquals("ready", barrier.withDomainWrite { "ready" })
    }

}
