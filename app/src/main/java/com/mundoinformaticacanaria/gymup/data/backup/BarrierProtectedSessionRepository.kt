package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.SessionCreationResult
import com.mundoinformaticacanaria.gymup.domain.repository.SessionRepository
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSource
import java.time.LocalDate

/**
 * Serializes all SessionRepository mutations with the future backup/recovery operation.
 *
 * The delegate is the raw Room repository; it must not acquire the barrier itself.
 * Reads/flows are delegated unchanged. A shared barrier is useful only after ALL
 * writers (including seeder, images, preferences and cleanup) cooperate.
 *
 * Isolated building block: NOT installed in AppContainer and NOT proof of a
 * production-wide write barrier, safe startup or process-crash recovery.
 */
internal class BarrierProtectedSessionRepository(
    private val delegate: SessionRepository,
    private val barrier: BackupDomainWriteBarrier,
) : SessionRepository by delegate {
    override suspend fun createSession(date: LocalDate, sessionTypeId: String, name: String?, note: String?, source: SessionSource): SessionCreationResult =
        barrier.withDomainWrite { delegate.createSession(date, sessionTypeId, name, note, source) }

    override suspend fun deleteSession(sessionId: String) =
        barrier.withDomainWrite { delegate.deleteSession(sessionId) }

    override suspend fun updateSessionMetadata(sessionId: String, sessionTypeId: String, name: String?, note: String?) =
        barrier.withDomainWrite { delegate.updateSessionMetadata(sessionId, sessionTypeId, name, note) }

    override suspend fun changeSessionPosition(sessionId: String, date: LocalDate, orderInDay: Int) =
        barrier.withDomainWrite { delegate.changeSessionPosition(sessionId, date, orderInDay) }

    override suspend fun setOperationalState(sessionId: String, state: SessionOperationalState) =
        barrier.withDomainWrite { delegate.setOperationalState(sessionId, state) }

    override suspend fun reopenSession(sessionId: String) =
        barrier.withDomainWrite { delegate.reopenSession(sessionId) }

    override suspend fun recalculateObjectives(sessionId: String) =
        barrier.withDomainWrite { delegate.recalculateObjectives(sessionId) }

    override suspend fun addExercise(sessionId: String, exerciseId: String) =
        barrier.withDomainWrite { delegate.addExercise(sessionId, exerciseId) }

    override suspend fun deleteExercise(sessionExerciseId: String) =
        barrier.withDomainWrite { delegate.deleteExercise(sessionExerciseId) }

    override suspend fun reorderExercises(sessionId: String, orderedSessionExerciseIds: List<String>) =
        barrier.withDomainWrite { delegate.reorderExercises(sessionId, orderedSessionExerciseIds) }

    override suspend fun updateExerciseMeta(sessionExerciseId: String, restSeconds: Int?, note: String?, incompleteReason: String?) =
        barrier.withDomainWrite { delegate.updateExerciseMeta(sessionExerciseId, restSeconds, note, incompleteReason) }

    override suspend fun finalizeExercise(sessionExerciseId: String) =
        barrier.withDomainWrite { delegate.finalizeExercise(sessionExerciseId) }

    override suspend fun addSet(sessionExerciseId: String) =
        barrier.withDomainWrite { delegate.addSet(sessionExerciseId) }

    override suspend fun deleteSet(setId: String) =
        barrier.withDomainWrite { delegate.deleteSet(setId) }

    override suspend fun updateSetActual(setId: String, actualLoad: Double?, actualMeasurement: Int?, rir: Int?) =
        barrier.withDomainWrite { delegate.updateSetActual(setId, actualLoad, actualMeasurement, rir) }

    override suspend fun updateSetTargets(setId: String, targetLoad: Double?, targetMeasurement: Int?, loadMode: LoadMode, measurementUnit: MeasurementUnit) =
        barrier.withDomainWrite { delegate.updateSetTargets(setId, targetLoad, targetMeasurement, loadMode, measurementUnit) }

    override suspend fun updateSetRest(setId: String, restOverrideSeconds: Int?) =
        barrier.withDomainWrite { delegate.updateSetRest(setId, restOverrideSeconds) }

    override suspend fun fulfillSet(setId: String) =
        barrier.withDomainWrite { delegate.fulfillSet(setId) }

    override suspend fun finalizeSession(sessionId: String) =
        barrier.withDomainWrite { delegate.finalizeSession(sessionId) }
}
