package com.mundoinformaticacanaria.gymup.data.repository

import com.mundoinformaticacanaria.gymup.data.local.ExerciseDao
import com.mundoinformaticacanaria.gymup.data.local.ExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.MasterDataDao
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogItem
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogItem
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMasterCatalogRepository(private val dao: MasterDataDao) : MasterCatalogRepository {
    override fun observeSessionTypes(): Flow<List<CatalogItem>> = dao.observeActiveSessionTypes().map { items -> items.map { CatalogItem(it.id, it.name) } }
    override fun observeMuscleGroups(): Flow<List<CatalogItem>> = dao.observeActiveMuscleGroups().map { items -> items.map { CatalogItem(it.id, it.name) } }
    override fun observeEquipment(): Flow<List<CatalogItem>> = dao.observeActiveEquipment().map { items -> items.map { CatalogItem(it.id, it.name) } }
}

class RoomExerciseCatalogRepository(private val dao: ExerciseDao) : ExerciseCatalogRepository {
    override fun observeActiveExercises(): Flow<List<ExerciseCatalogItem>> = dao.observeActive().map { items -> items.map(ExerciseEntity::toDomain) }
    override suspend fun findByNormalizedName(normalizedName: String): ExerciseCatalogItem? = dao.findByNormalizedName(normalizedName)?.toDomain()
    override suspend fun setFavorite(exerciseId: String, favorite: Boolean) { dao.setFavorite(exerciseId, favorite) }
}

private fun ExerciseEntity.toDomain(): ExerciseCatalogItem = ExerciseCatalogItem(id, nameEs, nameEn, muscleGroupId, equipmentId, defaultLoadMode, defaultMeasurementUnit, rirRequired, isFavorite)
