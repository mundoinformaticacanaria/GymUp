package com.mundoinformaticacanaria.gymup.domain.repository

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import kotlinx.coroutines.flow.Flow

data class CatalogItem(val id: String, val name: String)

data class ExerciseCatalogItem(
    val id: String,
    val nameEs: String,
    val nameEn: String,
    val muscleGroupId: String,
    val equipmentId: String?,
    val loadMode: LoadMode,
    val measurementUnit: MeasurementUnit,
    val rirRequired: Boolean,
    val isFavorite: Boolean,
)

interface MasterCatalogRepository {
    fun observeSessionTypes(): Flow<List<CatalogItem>>
    fun observeMuscleGroups(): Flow<List<CatalogItem>>
    fun observeEquipment(): Flow<List<CatalogItem>>
}

interface ExerciseCatalogRepository {
    fun observeActiveExercises(): Flow<List<ExerciseCatalogItem>>
    suspend fun findByNormalizedName(normalizedName: String): ExerciseCatalogItem?
    suspend fun setFavorite(exerciseId: String, favorite: Boolean)
}
