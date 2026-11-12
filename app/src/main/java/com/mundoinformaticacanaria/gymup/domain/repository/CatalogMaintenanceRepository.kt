package com.mundoinformaticacanaria.gymup.domain.repository

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import kotlinx.coroutines.flow.Flow

enum class MasterCatalogKind {
    SESSION_TYPE,
    MUSCLE_GROUP,
    EQUIPMENT,
}

data class MasterMaintenanceItem(
    val id: String,
    val name: String,
    val protected: Boolean = false,
)

data class ExerciseMaintenanceData(
    val id: String,
    val nameEs: String,
    val nameEn: String,
    val muscleGroupId: String,
    val equipmentId: String?,
    val loadMode: LoadMode,
    val measurementUnit: MeasurementUnit,
    val rirRequired: Boolean,
    val initialSetCount: Int?,
    val initialLoad: Double?,
    val initialMeasurement: Int?,
    val description: String?,
    val isFavorite: Boolean,
    val isActive: Boolean,
)

data class ExerciseMaintenanceInput(
    val nameEs: String,
    val nameEn: String,
    val muscleGroupId: String,
    val equipmentId: String?,
    val loadMode: LoadMode,
    val measurementUnit: MeasurementUnit,
    val rirRequired: Boolean,
    val initialSetCount: Int?,
    val initialLoad: Double?,
    val initialMeasurement: Int?,
    val description: String?,
)

data class ExerciseDeletionPreview(
    val historicalReferences: Int,
    val routineReferences: Int,
) {
    val willDeactivate: Boolean get() = historicalReferences > 0
    val requiresRoutineConfirmation: Boolean get() = historicalReferences == 0 && routineReferences > 0
}

sealed interface ExerciseDeletionResult {
    data object Deactivated : ExerciseDeletionResult
    data class Deleted(val removedRoutineReferences: Int) : ExerciseDeletionResult
}

class RoutineRemovalConfirmationRequired(val routineReferences: Int) :
    IllegalStateException("El ejercicio está presente en $routineReferences rutina(s)")

interface CatalogMaintenanceRepository {
    fun observeMasters(kind: MasterCatalogKind): Flow<List<MasterMaintenanceItem>>

    suspend fun createMaster(kind: MasterCatalogKind, name: String): String
    suspend fun renameMaster(kind: MasterCatalogKind, id: String, name: String)
    suspend fun deactivateMaster(kind: MasterCatalogKind, id: String)

    suspend fun getExercise(exerciseId: String): ExerciseMaintenanceData?
    suspend fun createExercise(input: ExerciseMaintenanceInput): String
    suspend fun updateExercise(exerciseId: String, input: ExerciseMaintenanceInput)
    suspend fun previewExerciseDeletion(exerciseId: String): ExerciseDeletionPreview
    suspend fun deleteExercise(exerciseId: String, confirmRoutineRemoval: Boolean): ExerciseDeletionResult
}
