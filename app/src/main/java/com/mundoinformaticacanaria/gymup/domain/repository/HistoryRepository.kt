package com.mundoinformaticacanaria.gymup.domain.repository

import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class ExerciseSearchMetadata(
    val lastExecutionDate: LocalDate?,
    val lastExecutionOrderInDay: Int?,
    val inRoutine: Boolean,
)

data class ExerciseHistorySet(
    val position: Int,
    val loadMode: LoadMode,
    val actualLoad: Double?,
    val measurementUnit: MeasurementUnit,
    val actualMeasurement: Int?,
    val rir: Int?,
)

data class ExerciseHistoryExecution(
    val sessionId: String,
    val sessionName: String,
    val date: LocalDate,
    val orderInDay: Int,
    val status: ExerciseExecutionStatus,
    val sets: List<ExerciseHistorySet>,
)

data class ExerciseHistory(
    val exerciseId: String,
    val nameEs: String,
    val nameEn: String,
    val executions: List<ExerciseHistoryExecution>,
)

interface HistoryRepository {
    fun observeExerciseSearchMetadata(): Flow<Map<String, ExerciseSearchMetadata>>
    fun observeSessionTypeIds(): Flow<Map<String, String>>
    suspend fun getExerciseHistory(exerciseId: String, limit: Int = 10): ExerciseHistory?
}
