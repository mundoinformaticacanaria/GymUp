package com.mundoinformaticacanaria.gymup.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupStartupGateTest {
    @Test
    fun `recovery completes before seeding and ready is reused`() = runBlocking {
        val events = mutableListOf<String>()
        val gate = BackupStartupGate(
            recover = { events += "recover" },
            seed = { events += "seed" },
        )

        gate.awaitReady()
        gate.awaitReady()

        assertEquals(listOf("recover", "seed"), events)
    }

    @Test
    fun `failed recovery blocks seeding and retries before access`() = runBlocking {
        val events = mutableListOf<String>()
        var attempts = 0
        val gate = BackupStartupGate(
            recover = {
                events += "recover"
                if (++attempts == 1) error("Simulated corrupt journal")
            },
            seed = { events += "seed" },
        )

        assertTrue(runCatching { gate.awaitReady() }.isFailure)
        assertEquals(listOf("recover"), events)
        gate.awaitReady()

        assertEquals(listOf("recover", "recover", "seed"), events)
    }

    @Test
    fun `failed seeding never opens gate and is retried after recovery`() = runBlocking {
        val events = mutableListOf<String>()
        var attempts = 0
        val gate = BackupStartupGate(
            recover = { events += "recover" },
            seed = {
                events += "seed"
                if (++attempts == 1) error("Simulated seeder failure")
            },
        )

        assertTrue(runCatching { gate.awaitReady() }.isFailure)
        gate.awaitReady()

        assertEquals(listOf("recover", "seed", "recover", "seed"), events)
    }

    @Test
    fun `concurrent callers wait for recovery and only seed once`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val gate = BackupStartupGate(
            recover = {
                events += "recover"
                entered.complete(Unit)
                release.await()
            },
            seed = { events += "seed" },
        )

        val first = launch { gate.awaitReady() }
        entered.await()
        val second = launch { gate.awaitReady() }
        yield()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(listOf("recover"), events)

        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("recover", "seed"), events)
    }
}
