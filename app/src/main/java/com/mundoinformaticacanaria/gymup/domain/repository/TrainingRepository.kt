package com.mundoinformaticacanaria.gymup.domain.repository

import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

sealed interface SessionSource {
    data object Empty : SessionSource
    data class Routine(val routineId: String) : SessionSource
    data class Duplicate(val sessionId: String) : SessionSource
}

data class SessionCreationResult(
    val sessionId: String,
    val omittedExerciseNames: List<String> = emptyList(),
)

data class SessionSummary(
    val id: String,
    val date: LocalDate,
    val orderInDay: Int,
    val name: String,
    val sessionTypeId: String,
    val sessionTypeName: String,
    val operationalState: SessionOperationalState,
    val executionResult: SessionExecutionResult,
)

data class TrainingSet(
    val id: String,
    val position: Int,
    val loadMode: LoadMode,
    val measurementUnit: MeasurementUnit,
    val targetLoad: Double?,
    val actualLoad: Double?,
    val targetMeasurement: Int?,
    val actualMeasurement: Int?,
    val rir: Int?,
    val restOverrideSeconds: Int?,
    val actualConfirmed: Boolean,
)

data class TrainingExercise(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val nameEs: String,
    val nameEn: String,
    val muscleGroupName: String,
    val equipmentName: String?,
    val loadMode: LoadMode,
    val measurementUnit: MeasurementUnit,
    val rirRequired: Boolean,
    val description: String?,
    val exerciseRestSeconds: Int?,
    val note: String?,
    val incompleteReason: String?,
    val isFinalized: Boolean,
    val status: ExerciseExecutionStatus,
    val sets: List<TrainingSet>,
)

data class SessionDetail(
    val summary: SessionSummary,
    val generalNote: String?,
    val isAutoName: Boolean,
    val sessionTypeId: String,
    val exercises: List<TrainingExercise>,
)

data class RoutineSummary(
    val id: String,
    val name: String,
    val suggestedSessionTypeId: String?,
    val description: String?,
)

data class RoutineExercise(
    val exerciseId: String,
    val position: Int,
    val nameEs: String,
    val nameEn: String,
    val isActive: Boolean,
)

data class RoutineDetail(
    val routine: RoutineSummary,
    val exercises: List<RoutineExercise>,
)

class MissingRirException(val missingSetIds: List<String>) : IllegalStateException("Falta RIR obligatorio")
class DuplicateExerciseException : IllegalStateException("El ejercicio ya existe")
class InactiveExerciseException : IllegalStateException("El ejercicio está desactivado")

interface TrainingRepository {
    fun observeSessions(): Flow<List<SessionSummary>>
    fun observeSessionsForDate(date: LocalDate): Flow<List<SessionSummary>>
    fun observeRoutines(): Flow<List<RoutineSummary>>

    suspend fun getSessionDetail(sessionId: String): SessionDetail?
    suspend fun getRoutineDetail(routineId: String): RoutineDetail?

    suspend fun createSession(
        date: LocalDate,
        sessionTypeId: String,
        name: String?,
        note: String?,
        source: SessionSource,
    ): SessionCreationResult

    suspend fun deleteSession(sessionId: String)
    suspend fun updateSessionMetadata(sessionId: String, sessionTypeId: String, name: String?, note: String?)
    suspend fun changeSessionPosition(sessionId: String, date: LocalDate, orderInDay: Int)
    suspend fun setOperationalState(sessionId: String, state: SessionOperationalState)
    suspend fun recalculateObjectives(sessionId: String)

    suspend fun addExercise(sessionId: String, exerciseId: String)
    suspend fun deleteExercise(sessionExerciseId: String)
    suspend fun reorderExercises(sessionId: String, orderedSessionExerciseIds: List<String>)
    suspend fun updateExerciseMeta(sessionExerciseId: String, restSeconds: Int?, note: String?, incompleteReason: String?)
    suspend fun finalizeExercise(sessionExerciseId: String)

    suspend fun addSet(sessionExerciseId: String)
    suspend fun deleteSet(setId: String)
    suspend fun updateSetActual(setId: String, actualLoad: Double?, actualMeasurement: Int?, rir: Int?)
    suspend fun updateSetTargets(setId: String, targetLoad: Double?, targetMeasurement: Int?, loadMode: LoadMode, measurementUnit: MeasurementUnit)
    suspend fun updateSetRest(setId: String, restOverrideSeconds: Int?)
    suspend fun fulfillSet(setId: String)
    suspend fun finalizeSession(sessionId: String)

    suspend fun createRoutine(name: String, suggestedSessionTypeId: String?, description: String?): String
    suspend fun updateRoutine(routineId: String, name: String, suggestedSessionTypeId: String?, description: String?)
    suspend fun deleteRoutine(routineId: String)
    suspend fun addRoutineExercise(routineId: String, exerciseId: String)
    suspend fun deleteRoutineExercise(routineId: String, exerciseId: String)
    suspend fun reorderRoutineExercises(routineId: String, orderedExerciseIds: List<String>)
}
