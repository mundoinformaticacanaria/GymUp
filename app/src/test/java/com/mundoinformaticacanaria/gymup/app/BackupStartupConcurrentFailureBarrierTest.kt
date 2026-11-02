package com.mundoinformaticacanaria.gymup.app

import com.mundoinformaticacanaria.gymup.data.backup.BackupDomainWriteBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #56: a writer queued behind a failing startup must never run in the interval
 * before another Activity's queued awaitReady() can retry recovery.
 *
 * This is process-local JVM coordination, not a device, power-loss or
 * cross-process durability test.
 */
class BackupStartupConcurrentFailureBarrierTest {
    @Test
    fun `queued writer fails closed between failed startup and concurrent retry`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val firstEntered = CompletableDeferred<Unit>()
        val allowFirstFailure = CompletableDeferred<Unit>()
        val retryEntered = CompletableDeferred<Unit>()
        val allowRetry = CompletableDeferred<Unit>()
        var recoveries = 0
        var seeds = 0
        var writes = 0

        val gate = BackupStartupGate(
            recover = {
                when (++recoveries) {
                    1 -> {
                        firstEntered.complete(Unit)
                        allowFirstFailure.await()
                        error("injected recovery failure")
                    }
                    2 -> {
                        retryEntered.complete(Unit)
                        allowRetry.await()
                    }
                }
            },
            seed = { seeds++ },
            writeBarrier = barrier,
        )

        val first = async { runCatching { gate.awaitReady() }.isFailure }
        firstEntered.await()

        // UNDISPATCHED starts both callers immediately. The writer is already
        // waiting for the domain mutex before the first recovery releases it.
        val queuedWriter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { barrier.withDomainWrite { writes++ } }.isFailure
        }
        val secondActivity = async(start = CoroutineStart.UNDISPATCHED) {
            gate.awaitReady()
        }
        assertFalse(queuedWriter.isCompleted)
        assertFalse(secondActivity.isCompleted)

        allowFirstFailure.complete(Unit)
        assertTrue(first.await())
        assertTrue("Writer must be rejected while recovery remains pending", queuedWriter.await())
        retryEntered.await()
        assertEquals(0, writes)
        assertEquals(0, seeds)
        assertFalse(secondActivity.isCompleted)

        allowRetry.complete(Unit)
        secondActivity.await()
        assertEquals(2, recoveries)
        assertEquals(1, seeds)
        assertEquals(0, writes)
        assertEquals("safe", barrier.withDomainWrite { "safe" })
    }
}
