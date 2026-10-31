package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogItem
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarrierProtectedExerciseCatalogRepositoryTest {
    @Test
    fun favoriteMutationWaitsForExclusiveBackup() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeExerciseCatalogRepository()
        val guarded = BarrierProtectedExerciseCatalogRepository(delegate, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            guarded.setFavorite("exercise", true)
        }
        assertFalse(writer.isCompleted)
        assertEquals(0, delegate.writes)

        release.complete(Unit)
        backup.join()
        writer.join()
        assertEquals(1, delegate.writes)
        assertEquals("exercise" to true, delegate.lastWrite)
    }

    @Test
    fun readsRemainAvailableDuringExclusiveBackup() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeExerciseCatalogRepository()
        val guarded = BarrierProtectedExerciseCatalogRepository(delegate, barrier)

        barrier.withExclusiveBackup {
            assertTrue(guarded.observeActiveExercises().first().isEmpty())
            assertEquals(null, guarded.findByNormalizedName("exercise"))
        }
        assertEquals(0, delegate.writes)
    }

    @Test
    fun delegateFailureReleasesBarrier() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeExerciseCatalogRepository(failWrites = true)
        val guarded = BarrierProtectedExerciseCatalogRepository(delegate, barrier)

        assertTrue(runCatching { guarded.setFavorite("exercise", true) }.isFailure)
        assertEquals("ready", barrier.withExclusiveBackup { "ready" })
        assertEquals(0, delegate.writes)
    }

    @Test
    fun cancelledFavoriteMutationWaitingForBackupNeverWrites() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeExerciseCatalogRepository()
        val guarded = BarrierProtectedExerciseCatalogRepository(delegate, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            guarded.setFavorite("exercise", false)
        }
        writer.cancelAndJoin()
        release.complete(Unit)
        backup.join()

        assertEquals(0, delegate.writes)
    }

    private class FakeExerciseCatalogRepository(
        private val failWrites: Boolean = false,
    ) : ExerciseCatalogRepository {
        var writes = 0
        var lastWrite: Pair<String, Boolean>? = null

        override fun observeActiveExercises(): Flow<List<ExerciseCatalogItem>> = flowOf(emptyList())

        override suspend fun findByNormalizedName(normalizedName: String): ExerciseCatalogItem? = null

        override suspend fun setFavorite(exerciseId: String, favorite: Boolean) {
            if (failWrites) error("simulated favorite update failure")
            writes++
            lastWrite = exerciseId to favorite
        }
    }
}
