package com.mundoinformaticacanaria.gymup.domain.repository

import kotlinx.coroutines.flow.Flow

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

interface RoutineRepository {
    fun observeRoutines(): Flow<List<RoutineSummary>>

    suspend fun getRoutineDetail(routineId: String): RoutineDetail?
    suspend fun saveRoutine(
        routineId: String?,
        name: String,
        suggestedSessionTypeId: String?,
        description: String?,
        orderedExerciseIds: List<String>,
    ): String
    suspend fun deleteRoutine(routineId: String)
}
