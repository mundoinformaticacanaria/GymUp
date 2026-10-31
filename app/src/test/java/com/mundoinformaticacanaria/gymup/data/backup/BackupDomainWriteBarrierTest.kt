package com.mundoinformaticacanaria.gymup.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDomainWriteBarrierTest {
    @Test
    fun `backup waits for active domain mutation`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val writer = launch {
            barrier.withDomainWrite {
                events += "write-start"
                entered.complete(Unit)
                release.await()
                events += "write-end"
            }
        }
        entered.await()
        val backup = launch { barrier.withExclusiveBackup { events += "backup" } }
        yield()
        assertFalse(backup.isCompleted)
        release.complete(Unit)
        writer.join()
        backup.join()
        assertEquals(listOf("write-start", "write-end", "backup"), events)
    }

    @Test
    fun `domain mutation waits for active backup`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val backup = launch {
            barrier.withExclusiveBackup {
                events += "backup-start"
                entered.complete(Unit)
                release.await()
                events += "backup-end"
            }
        }
        entered.await()
        val writer = launch { barrier.withDomainWrite { events += "write" } }
        yield()
        assertFalse(writer.isCompleted)
        release.complete(Unit)
        backup.join()
        writer.join()
        assertEquals(listOf("backup-start", "backup-end", "write"), events)
    }

    @Test
    fun `cancelling waiting writer does not interrupt backup or unlock it`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val backup = launch {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val waiting = launch { barrier.withDomainWrite { writes++ } }
        yield()
        waiting.cancelAndJoin()
        assertFalse(backup.isCompleted)
        assertEquals(0, writes)
        release.complete(Unit)
        backup.join()
        barrier.withDomainWrite { writes++ }
        assertEquals(1, writes)
    }

    @Test
    fun `failure releases barrier for a subsequent operation`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val result = runCatching { barrier.withExclusiveBackup { error("Simulated failure") } }
        assertTrue(result.isFailure)
        assertEquals("available", barrier.withDomainWrite { "available" })
    }
}
