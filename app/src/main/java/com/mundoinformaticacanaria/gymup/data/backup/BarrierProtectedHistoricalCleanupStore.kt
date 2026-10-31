package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupImpact
import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupStore
import java.time.LocalDate

/**
 * Coordinates destructive history cleanup and database compaction with backup operations.
 *
 * The delete transaction and VACUUM are individually protected. The manager may run a
 * backup between these two steps: deletion has already committed and compaction changes
 * only physical database layout, not the logical snapshot. Preview is read-only.
 *
 * Isolated composition building block, NOT installed in AppContainer. The same barrier
 * must protect every other Room/image/DataStore writer before production integration.
 * Do not call a guarded store from an exclusive backup section (non-reentrant mutex).
 */
internal class BarrierProtectedHistoricalCleanupStore(
    private val delegate: HistoricalCleanupStore,
    private val barrier: BackupDomainWriteBarrier,
) : HistoricalCleanupStore {
    override suspend fun preview(cutoffDate: LocalDate): HistoricalCleanupImpact =
        delegate.preview(cutoffDate)

    override suspend fun deleteTransactionalData(cutoffDate: LocalDate): HistoricalCleanupImpact =
        barrier.withDomainWrite { delegate.deleteTransactionalData(cutoffDate) }

    override suspend fun compact(): Pair<Long?, Long?> =
        barrier.withDomainWrite { delegate.compact() }
}
