package com.mundoinformaticacanaria.gymup.data.cleanup

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalCleanupManagerTest {
    private val cutoff = LocalDate.of(2026, 1, 1)

    @Test
    fun `preview exposes exact transactional counts without deleting`() = runBlocking {
        val store = FakeStore()
        val manager = HistoricalCleanupManager(store)

        val impact = manager.preview(cutoff)

        assertEquals(3, impact.sessions)
        assertEquals(8, impact.sessionExercises)
        assertEquals(24, impact.sessionSets)
        assertEquals(32, impact.associatedRecords)
        assertEquals(35, impact.totalTransactionalRecords)
        assertEquals(0, store.deleteCalls)
        assertEquals(0, store.compactCalls)
    }

    @Test
    fun `confirmed cleanup deletes first then compacts and reports reclaimed bytes`() = runBlocking {
        val store = FakeStore()
        val manager = HistoricalCleanupManager(store)

        val result = manager.execute(
            cutoffDate = cutoff,
            confirmation = HistoricalCleanupConfirmation.IRREVERSIBLE_CONFIRMED,
        )

        assertEquals(1, store.deleteCalls)
        assertEquals(1, store.compactCalls)
        assertEquals(listOf("delete", "compact"), store.operations)
        assertEquals(4096L, result.bytesReclaimed)
        assertEquals(35, result.deleted.totalTransactionalRecords)
    }

    @Test
    fun `failed compaction does not disguise a committed deletion as failed`() = runBlocking {
        val store = FakeStore().apply { compactFailure = IllegalStateException("VACUUM unavailable") }
        val result = HistoricalCleanupManager(store).execute(
            cutoffDate = cutoff,
            confirmation = HistoricalCleanupConfirmation.IRREVERSIBLE_CONFIRMED,
        )

        assertEquals(1, store.deleteCalls)
        assertEquals(1, store.compactCalls)
        assertEquals(35, result.deleted.totalTransactionalRecords)
        assertEquals(null, result.bytesReclaimed)
        assertEquals(null, result.bytesBeforeCompaction)
        assertEquals(null, result.bytesAfterCompaction)
    }

    @Test
    fun `compaction cancellation is not swallowed`() = runBlocking {
        val store = FakeStore().apply {
            compactFailure = kotlinx.coroutines.CancellationException("cancelled")
        }
        try {
            HistoricalCleanupManager(store).execute(
                cutoffDate = cutoff,
                confirmation = HistoricalCleanupConfirmation.IRREVERSIBLE_CONFIRMED,
            )
            org.junit.Assert.fail("Expected cancellation to propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(1, store.deleteCalls)
        }
    }

    private inner class FakeStore : HistoricalCleanupStore {
        var compactFailure: Exception? = null
        var deleteCalls = 0
        var compactCalls = 0
        val operations = mutableListOf<String>()

        private val impact = HistoricalCleanupImpact(
            cutoffDate = cutoff,
            sessions = 3,
            sessionExercises = 8,
            sessionSets = 24,
        )

        override suspend fun preview(cutoffDate: LocalDate): HistoricalCleanupImpact = impact

        override suspend fun deleteTransactionalData(cutoffDate: LocalDate): HistoricalCleanupImpact {
            operations += "delete"
            deleteCalls += 1
            return impact
        }

        override suspend fun compact(): Pair<Long?, Long?> {
            operations += "compact"
            compactCalls += 1
            compactFailure?.let { throw it }
            return 16_384L to 12_288L
        }
    }
}
