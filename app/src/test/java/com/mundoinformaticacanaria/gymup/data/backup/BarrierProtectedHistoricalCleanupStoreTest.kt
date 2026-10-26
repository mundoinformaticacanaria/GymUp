package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupImpact
import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupStore
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarrierProtectedHistoricalCleanupStoreTest {
    private val cutoff = LocalDate.of(2026, 10, 26)

    @Test
    fun deleteAndCompactionEachWaitForExclusiveBackup() = runBlocking {
        for (action in listOf<suspend (HistoricalCleanupStore) -> Unit>(
            { it.deleteTransactionalData(cutoff); Unit },
            { it.compact(); Unit },
        )) {
            val barrier = BackupDomainWriteBarrier()
            val delegate = FakeStore()
            val guarded = BarrierProtectedHistoricalCleanupStore(delegate, barrier)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val backup = launch(start = CoroutineStart.UNDISPATCHED) {
                barrier.withExclusiveBackup {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()

            val writer = launch(start = CoroutineStart.UNDISPATCHED) { action(guarded) }
            assertFalse(writer.isCompleted)
            assertEquals(0, delegate.writes)

            release.complete(Unit)
            backup.join()
            writer.join()
            assertEquals(1, delegate.writes)
        }
    }

    @Test
    fun previewRemainsReadableWhileBackupIsExclusive() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeStore()
        val guarded = BarrierProtectedHistoricalCleanupStore(delegate, barrier)

        barrier.withExclusiveBackup {
            assertEquals(cutoff, guarded.preview(cutoff).cutoffDate)
        }
        assertEquals(1, delegate.previews)
        assertEquals(0, delegate.writes)
    }

    @Test
    fun delegateFailureReleasesBarrier() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val guarded = BarrierProtectedHistoricalCleanupStore(FakeStore(failWrites = true), barrier)

        assertTrue(runCatching { guarded.deleteTransactionalData(cutoff) }.isFailure)
        assertEquals("ready", barrier.withExclusiveBackup { "ready" })
    }

    @Test
    fun cancelledCompactionWaitingForBackupDoesNotExecute() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeStore()
        val guarded = BarrierProtectedHistoricalCleanupStore(delegate, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val writer = launch(start = CoroutineStart.UNDISPATCHED) { guarded.compact() }
        writer.cancelAndJoin()
        release.complete(Unit)
        backup.join()
        assertEquals(0, delegate.writes)
    }

    private class FakeStore(private val failWrites: Boolean = false) : HistoricalCleanupStore {
        var writes = 0
        var previews = 0

        override suspend fun preview(cutoffDate: LocalDate): HistoricalCleanupImpact {
            previews++
            return HistoricalCleanupImpact(cutoffDate, 1, 2, 3)
        }

        override suspend fun deleteTransactionalData(cutoffDate: LocalDate): HistoricalCleanupImpact {
            recordWrite()
            return HistoricalCleanupImpact(cutoffDate, 1, 2, 3)
        }

        override suspend fun compact(): Pair<Long?, Long?> {
            recordWrite()
            return 100L to 80L
        }

        private fun recordWrite() {
            if (failWrites) error("simulated cleanup failure")
            writes++
        }
    }
}
