package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.SessionCreationResult
import com.mundoinformaticacanaria.gymup.domain.repository.SessionDetail
import com.mundoinformaticacanaria.gymup.domain.repository.SessionRepository
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSource
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary
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
import java.time.LocalDate

class BarrierProtectedSessionRepositoryTest {
    @Test
    fun allNineteenMutationsWaitForExclusiveBackup() = runBlocking {
        val operations: List<suspend (SessionRepository) -> Unit> = listOf(
            { repository -> repository.createSession(LocalDate.of(2026, 10, 26), "type", null, null, SessionSource.Empty); Unit },
            { repository -> repository.deleteSession("session"); Unit },
            { repository -> repository.updateSessionMetadata("session", "type", null, null); Unit },
            { repository -> repository.changeSessionPosition("session", LocalDate.of(2026, 10, 26), 1); Unit },
            { repository -> repository.setOperationalState("session", SessionOperationalState.entries.first()); Unit },
            { repository -> repository.reopenSession("session"); Unit },
            { repository -> repository.recalculateObjectives("session"); Unit },
            { repository -> repository.addExercise("session", "exercise"); Unit },
            { repository -> repository.deleteExercise("session-exercise"); Unit },
            { repository -> repository.reorderExercises("session", listOf("session-exercise")); Unit },
            { repository -> repository.updateExerciseMeta("session-exercise", null, null, null); Unit },
            { repository -> repository.finalizeExercise("session-exercise"); Unit },
            { repository -> repository.addSet("session-exercise"); Unit },
            { repository -> repository.deleteSet("set"); Unit },
            { repository -> repository.updateSetActual("set", 12.0, 8, null); Unit },
            { repository -> repository.updateSetTargets("set", 12.0, 8, LoadMode.entries.first(), MeasurementUnit.entries.first()); Unit },
            { repository -> repository.updateSetRest("set", 60); Unit },
            { repository -> repository.fulfillSet("set"); Unit },
            { repository -> repository.finalizeSession("session"); Unit },
        )
        assertEquals(19, operations.size)

        for (operation in operations) {
            val barrier = BackupDomainWriteBarrier()
            val delegate = FakeSessionRepository()
            val guarded = BarrierProtectedSessionRepository(delegate, barrier)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val backup = launch(start = CoroutineStart.UNDISPATCHED) {
                barrier.withExclusiveBackup {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()

            val writer = launch(start = CoroutineStart.UNDISPATCHED) { operation(guarded) }
            assertFalse(writer.isCompleted)
            assertEquals(0, delegate.writes)

            release.complete(Unit)
            backup.join()
            writer.join()
            assertEquals(1, delegate.writes)
        }
    }

    @Test
    fun readOperationsRemainDelegatedWhileBackupIsExclusive() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeSessionRepository()
        val guarded = BarrierProtectedSessionRepository(delegate, barrier)

        barrier.withExclusiveBackup {
            assertTrue(guarded.observeSessions().first().isEmpty())
            assertTrue(guarded.observeSessionsForDate(LocalDate.of(2026, 10, 26)).first().isEmpty())
            assertEquals(null, guarded.getSessionDetail("session"))
        }
        assertEquals(0, delegate.writes)
    }

    @Test
    fun delegateFailureReleasesBarrier() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val guarded = BarrierProtectedSessionRepository(FakeSessionRepository(failWrites = true), barrier)
        assertTrue(runCatching { guarded.finalizeSession("session") }.isFailure)
        assertEquals("ready", barrier.withExclusiveBackup { "ready" })
    }

    @Test
    fun cancellationWhileWaitingDoesNotWrite() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val delegate = FakeSessionRepository()
        val guarded = BarrierProtectedSessionRepository(delegate, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val writer = launch(start = CoroutineStart.UNDISPATCHED) { guarded.fulfillSet("set") }
        writer.cancelAndJoin()
        release.complete(Unit)
        backup.join()
        assertEquals(0, delegate.writes)
    }

    private class FakeSessionRepository(private val failWrites: Boolean = false) : SessionRepository {
        var writes = 0

        private fun recordWrite() {
            if (failWrites) error("simulated write failure")
            writes++
        }

        override fun observeSessions(): Flow<List<SessionSummary>> = flowOf(emptyList())
        override fun observeSessionsForDate(date: LocalDate): Flow<List<SessionSummary>> = flowOf(emptyList())
        override suspend fun getSessionDetail(sessionId: String): SessionDetail? = null

        override suspend fun createSession(date: LocalDate, sessionTypeId: String, name: String?, note: String?, source: SessionSource): SessionCreationResult {
            recordWrite()
            return SessionCreationResult("new-session")
        }

        override suspend fun deleteSession(sessionId: String) {
            recordWrite()
        }

        override suspend fun updateSessionMetadata(sessionId: String, sessionTypeId: String, name: String?, note: String?) {
            recordWrite()
        }

        override suspend fun changeSessionPosition(sessionId: String, date: LocalDate, orderInDay: Int) {
            recordWrite()
        }

        override suspend fun setOperationalState(sessionId: String, state: SessionOperationalState) {
            recordWrite()
        }

        override suspend fun reopenSession(sessionId: String) {
            recordWrite()
        }

        override suspend fun recalculateObjectives(sessionId: String) {
            recordWrite()
        }

        override suspend fun addExercise(sessionId: String, exerciseId: String) {
            recordWrite()
        }

        override suspend fun deleteExercise(sessionExerciseId: String) {
            recordWrite()
        }

        override suspend fun reorderExercises(sessionId: String, orderedSessionExerciseIds: List<String>) {
            recordWrite()
        }

        override suspend fun updateExerciseMeta(sessionExerciseId: String, restSeconds: Int?, note: String?, incompleteReason: String?) {
            recordWrite()
        }

        override suspend fun finalizeExercise(sessionExerciseId: String) {
            recordWrite()
        }

        override suspend fun addSet(sessionExerciseId: String) {
            recordWrite()
        }

        override suspend fun deleteSet(setId: String) {
            recordWrite()
        }

        override suspend fun updateSetActual(setId: String, actualLoad: Double?, actualMeasurement: Int?, rir: Int?) {
            recordWrite()
        }

        override suspend fun updateSetTargets(setId: String, targetLoad: Double?, targetMeasurement: Int?, loadMode: LoadMode, measurementUnit: MeasurementUnit) {
            recordWrite()
        }

        override suspend fun updateSetRest(setId: String, restOverrideSeconds: Int?) {
            recordWrite()
        }

        override suspend fun fulfillSet(setId: String) {
            recordWrite()
        }

        override suspend fun finalizeSession(sessionId: String) {
            recordWrite()
        }
    }
}
