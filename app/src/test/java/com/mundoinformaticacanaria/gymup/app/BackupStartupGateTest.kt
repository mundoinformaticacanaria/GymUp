package com.mundoinformaticacanaria.gymup.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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

    @Test
    fun `cancelled recovery leaves startup closed and retries before seeding`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        var recoveries = 0
        var seeds = 0
        val gate = BackupStartupGate(
            recover = {
                recoveries++
                if (recoveries == 1) {
                    entered.complete(Unit)
                    neverReleased.await()
                }
            },
            seed = { seeds++ },
        )

        val first = launch { gate.awaitReady() }
        entered.await()
        first.cancelAndJoin()

        assertEquals(1, recoveries)
        assertEquals(0, seeds)
        gate.awaitReady()
        assertEquals(2, recoveries)
        assertEquals(1, seeds)
    }

    @Test
    fun `cancelled seeding leaves startup closed and retries recovery`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        var recoveries = 0
        var seeds = 0
        val gate = BackupStartupGate(
            recover = { recoveries++ },
            seed = {
                seeds++
                if (seeds == 1) {
                    entered.complete(Unit)
                    neverReleased.await()
                }
            },
        )

        val first = launch { gate.awaitReady() }
        entered.await()
        first.cancelAndJoin()

        assertEquals(1, recoveries)
        assertEquals(1, seeds)
        gate.awaitReady()
        assertEquals(2, recoveries)
        assertEquals(2, seeds)
    }

    @Test
    fun `waiting caller retries after first recovery fails without opening startup early`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var recoveries = 0
        var seeds = 0
        val gate = BackupStartupGate(
            recover = {
                recoveries++
                if (recoveries == 1) {
                    entered.complete(Unit)
                    release.await()
                    error("First recovery attempt failed")
                }
            },
            seed = { seeds++ },
        )

        val first = async { runCatching { gate.awaitReady() }.isFailure }
        entered.await()
        val waiting = async { gate.awaitReady() }
        yield()
        assertFalse(waiting.isCompleted)
        assertEquals(0, seeds)

        release.complete(Unit)
        assertTrue(first.await())
        waiting.await()
        assertEquals(2, recoveries)
        assertEquals(1, seeds)
        gate.awaitReady()
        assertEquals(2, recoveries)
    }

    @Test
    fun `cancelled waiting caller does not cancel active recovery or seeding`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var recoveries = 0
        var seeds = 0
        val gate = BackupStartupGate(
            recover = {
                recoveries++
                entered.complete(Unit)
                release.await()
            },
            seed = { seeds++ },
        )

        val active = launch { gate.awaitReady() }
        entered.await()
        val waiting = launch { gate.awaitReady() }
        yield()
        assertFalse(waiting.isCompleted)
        waiting.cancelAndJoin()
        assertFalse(active.isCompleted)
        assertEquals(0, seeds)

        release.complete(Unit)
        active.join()
        gate.awaitReady()
        assertEquals(1, recoveries)
        assertEquals(1, seeds)
    }

}
