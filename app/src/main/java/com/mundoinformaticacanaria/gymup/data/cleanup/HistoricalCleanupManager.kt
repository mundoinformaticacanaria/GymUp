package com.mundoinformaticacanaria.gymup.data.cleanup

import androidx.room.withTransaction
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HistoricalCleanupImpact(
    val cutoffDate: LocalDate,
    val sessions: Int,
    val sessionExercises: Int,
    val sessionSets: Int,
) {
    val associatedRecords: Int get() = sessionExercises + sessionSets
    val totalTransactionalRecords: Int get() = sessions + associatedRecords
}

data class HistoricalCleanupResult(
    val deleted: HistoricalCleanupImpact,
    val bytesBeforeCompaction: Long?,
    val bytesAfterCompaction: Long?,
) {
    val bytesReclaimed: Long?
        get() = if (bytesBeforeCompaction != null && bytesAfterCompaction != null) {
            (bytesBeforeCompaction - bytesAfterCompaction).coerceAtLeast(0L)
        } else {
            null
        }
}

/**
 * Explicit token required by the destructive API. UI code should only pass it after presenting the
 * cutoff, affected counts, irreversibility warning and backup recommendation required by v1.
 */
enum class HistoricalCleanupConfirmation {
    IRREVERSIBLE_CONFIRMED,
}

interface HistoricalCleanupStore {
    suspend fun preview(cutoffDate: LocalDate): HistoricalCleanupImpact
    suspend fun deleteTransactionalData(cutoffDate: LocalDate): HistoricalCleanupImpact
    suspend fun compact(): Pair<Long?, Long?>
}

class HistoricalCleanupManager(
    private val store: HistoricalCleanupStore,
) {
    suspend fun preview(cutoffDate: LocalDate): HistoricalCleanupImpact = store.preview(cutoffDate)

    suspend fun execute(
        cutoffDate: LocalDate,
        confirmation: HistoricalCleanupConfirmation,
    ): HistoricalCleanupResult {
        require(confirmation == HistoricalCleanupConfirmation.IRREVERSIBLE_CONFIRMED)
        val deleted = store.deleteTransactionalData(cutoffDate)
        val (before, after) = store.compact()
        return HistoricalCleanupResult(
            deleted = deleted,
            bytesBeforeCompaction = before,
            bytesAfterCompaction = after,
        )
    }
}

class RoomHistoricalCleanupStore(
    private val database: GymUpDatabase,
) : HistoricalCleanupStore {
    override suspend fun preview(cutoffDate: LocalDate): HistoricalCleanupImpact =
        database.withTransaction {
            impact(cutoffDate)
        }

    override suspend fun deleteTransactionalData(cutoffDate: LocalDate): HistoricalCleanupImpact =
        database.withTransaction {
            val impact = impact(cutoffDate)
            val deletedSessions = database.trainingDao().deleteSessionsBefore(cutoffDate.toEpochDay())
            check(deletedSessions == impact.sessions) {
                "Historical cleanup changed while deleting: expected ${impact.sessions} sessions, deleted $deletedSessions"
            }
            impact
        }

    override suspend fun compact(): Pair<Long?, Long?> = withContext(Dispatchers.IO) {
        val sqlite = database.openHelper.writableDatabase
        val before = sqlite.allocatedBytes()
        sqlite.execSQL("VACUUM")
        val after = sqlite.allocatedBytes()
        before to after
    }

    private suspend fun impact(cutoffDate: LocalDate): HistoricalCleanupImpact {
        val cutoffEpochDay = cutoffDate.toEpochDay()
        val dao = database.trainingDao()
        return HistoricalCleanupImpact(
            cutoffDate = cutoffDate,
            sessions = dao.countSessionsBefore(cutoffEpochDay),
            sessionExercises = dao.countSessionExercisesBefore(cutoffEpochDay),
            sessionSets = dao.countSessionSetsBefore(cutoffEpochDay),
        )
    }
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.allocatedBytes(): Long? =
    runCatching {
        val pageCount = pragmaLong("page_count")
        val pageSize = pragmaLong("page_size")
        Math.multiplyExact(pageCount, pageSize)
    }.getOrNull()

private fun androidx.sqlite.db.SupportSQLiteDatabase.pragmaLong(name: String): Long =
    query("PRAGMA $name").use { cursor ->
        check(cursor.moveToFirst()) { "PRAGMA $name returned no rows" }
        cursor.getLong(0)
    }
