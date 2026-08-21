package com.mundoinformaticacanaria.gymup.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.mundoinformaticacanaria.gymup.core.util.normalizeName
import com.mundoinformaticacanaria.gymup.data.local.CatalogMaintenanceDao
import com.mundoinformaticacanaria.gymup.data.local.EquipmentEntity
import com.mundoinformaticacanaria.gymup.data.local.ExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.MuscleGroupEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionTypeEntity
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionPreview
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionResult
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceData
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceInput
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogKind
import com.mundoinformaticacanaria.gymup.domain.repository.MasterMaintenanceItem
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRemovalConfirmationRequired
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomCatalogMaintenanceRepository(
    context: Context,
    private val database: GymUpDatabase,
) : CatalogMaintenanceRepository {
    private val dao: CatalogMaintenanceDao = database.catalogMaintenanceDao()
    private val imageDirectory = File(context.applicationContext.filesDir, "exercise-images")

    override fun observeMasters(kind: MasterCatalogKind): Flow<List<MasterMaintenanceItem>> = when (kind) {
        MasterCatalogKind.SESSION_TYPE -> dao.observeSessionTypes().map { items ->
            items.map { MasterMaintenanceItem(it.id, it.name, it.isProtectedOther) }
        }
        MasterCatalogKind.MUSCLE_GROUP -> dao.observeMuscleGroups().map { items ->
            items.map { MasterMaintenanceItem(it.id, it.name) }
        }
        MasterCatalogKind.EQUIPMENT -> dao.observeEquipment().map { items ->
            items.map { MasterMaintenanceItem(it.id, it.name) }
        }
    }

    override suspend fun createMaster(kind: MasterCatalogKind, name: String): String {
        val cleanName = validateMasterName(name)
        val normalized = normalizeName(cleanName)
        ensureMasterNameAvailable(kind, normalized, excludingId = null)
        val id = UUID.randomUUID().toString()
        when (kind) {
            MasterCatalogKind.SESSION_TYPE -> dao.insertSessionType(
                SessionTypeEntity(id, cleanName, normalized, isActive = true, isProtectedOther = false),
            )
            MasterCatalogKind.MUSCLE_GROUP -> dao.insertMuscleGroup(
                MuscleGroupEntity(id, cleanName, normalized, isActive = true),
            )
            MasterCatalogKind.EQUIPMENT -> dao.insertEquipment(
                EquipmentEntity(id, cleanName, normalized, isActive = true),
            )
        }
        return id
    }

    override suspend fun renameMaster(kind: MasterCatalogKind, id: String, name: String) {
        val cleanName = validateMasterName(name)
        val normalized = normalizeName(cleanName)
        ensureMasterNameAvailable(kind, normalized, excludingId = id)
        when (kind) {
            MasterCatalogKind.SESSION_TYPE -> {
                val current = requireNotNull(dao.getSessionType(id)) { "Tipo de sesión inexistente" }
                require(!current.isProtectedOther) { "El tipo de sesión Otro está protegido y no puede renombrarse" }
                dao.updateSessionType(current.copy(name = cleanName, normalizedName = normalized))
            }
            MasterCatalogKind.MUSCLE_GROUP -> {
                val current = requireNotNull(dao.getMuscleGroup(id)) { "Grupo muscular inexistente" }
                dao.updateMuscleGroup(current.copy(name = cleanName, normalizedName = normalized))
            }
            MasterCatalogKind.EQUIPMENT -> {
                val current = requireNotNull(dao.getEquipment(id)) { "Equipo inexistente" }
                dao.updateEquipment(current.copy(name = cleanName, normalizedName = normalized))
            }
        }
    }

    override suspend fun deactivateMaster(kind: MasterCatalogKind, id: String) {
        when (kind) {
            MasterCatalogKind.SESSION_TYPE -> {
                val current = requireNotNull(dao.getSessionType(id)) { "Tipo de sesión inexistente" }
                require(!current.isProtectedOther) { "El tipo de sesión Otro está protegido y no puede desactivarse" }
                dao.updateSessionType(current.copy(isActive = false))
            }
            MasterCatalogKind.MUSCLE_GROUP -> {
                val current = requireNotNull(dao.getMuscleGroup(id)) { "Grupo muscular inexistente" }
                dao.updateMuscleGroup(current.copy(isActive = false))
            }
            MasterCatalogKind.EQUIPMENT -> {
                val current = requireNotNull(dao.getEquipment(id)) { "Equipo inexistente" }
                dao.updateEquipment(current.copy(isActive = false))
            }
        }
    }

    override suspend fun getExercise(exerciseId: String): ExerciseMaintenanceData? =
        dao.getExercise(exerciseId)?.toMaintenanceData()

    override suspend fun createExercise(input: ExerciseMaintenanceInput): String {
        val validated = validateExerciseInput(input, excludingId = null)
        val id = UUID.randomUUID().toString()
        dao.insertExercise(validated.toEntity(id = id, favorite = false, active = true))
        return id
    }

    override suspend fun updateExercise(exerciseId: String, input: ExerciseMaintenanceInput) {
        val current = requireNotNull(dao.getExercise(exerciseId)) { "Ejercicio inexistente" }
        val validated = validateExerciseInput(input, excludingId = exerciseId)
        dao.updateExercise(
            validated.toEntity(
                id = exerciseId,
                favorite = current.isFavorite,
                active = current.isActive,
            ),
        )
    }

    override suspend fun previewExerciseDeletion(exerciseId: String): ExerciseDeletionPreview =
        database.withTransaction {
            requireNotNull(dao.getExercise(exerciseId)) { "Ejercicio inexistente" }
            ExerciseDeletionPreview(
                historicalReferences = dao.countHistoricalReferences(exerciseId),
                routineReferences = dao.countRoutineReferences(exerciseId),
            )
        }

    override suspend fun deleteExercise(
        exerciseId: String,
        confirmRoutineRemoval: Boolean,
    ): ExerciseDeletionResult {
        val execution = database.withTransaction {
            requireNotNull(dao.getExercise(exerciseId)) { "Ejercicio inexistente" }
            val historicalReferences = dao.countHistoricalReferences(exerciseId)
            if (historicalReferences > 0) {
                dao.deactivateExercise(exerciseId)
                return@withTransaction DeletionExecution(ExerciseDeletionResult.Deactivated, emptyList())
            }

            val routineReferences = dao.countRoutineReferences(exerciseId)
            if (routineReferences > 0 && !confirmRoutineRemoval) {
                throw RoutineRemovalConfirmationRequired(routineReferences)
            }
            val imageStorageKeys = dao.getExerciseImages(exerciseId)
                .filter { it.sourceType == "USER" && it.storageKey.startsWith("user:") }
                .map { it.storageKey }
            if (routineReferences > 0) dao.removeRoutineReferences(exerciseId)
            dao.deleteExercise(exerciseId)
            DeletionExecution(
                ExerciseDeletionResult.Deleted(removedRoutineReferences = routineReferences),
                imageStorageKeys,
            )
        }
        execution.userImageStorageKeys.forEach { storageKey ->
            File(imageDirectory, storageKey.removePrefix("user:")).delete()
        }
        return execution.result
    }

    private suspend fun validateExerciseInput(
        input: ExerciseMaintenanceInput,
        excludingId: String?,
    ): ValidatedExerciseInput {
        val nameEs = input.nameEs.trim()
        val nameEn = input.nameEn.trim()
        require(nameEs.isNotBlank()) { "El nombre en español es obligatorio" }
        require(nameEn.isNotBlank()) { "El nombre en inglés es obligatorio" }
        val normalizedEs = normalizeName(nameEs)
        val normalizedEn = normalizeName(nameEn)
        val duplicateEs = dao.findExerciseByNameEs(normalizedEs)
        require(duplicateEs == null || duplicateEs.id == excludingId) { "Ya existe un ejercicio con ese nombre en español" }
        val duplicateEn = dao.findExerciseByNameEn(normalizedEn)
        require(duplicateEn == null || duplicateEn.id == excludingId) { "Ya existe un ejercicio con ese nombre en inglés" }

        val group = requireNotNull(dao.getMuscleGroup(input.muscleGroupId)) { "Grupo muscular inexistente" }
        require(group.isActive) { "El grupo muscular está desactivado" }
        input.equipmentId?.let { equipmentId ->
            val equipment = requireNotNull(dao.getEquipment(equipmentId)) { "Equipo inexistente" }
            require(equipment.isActive) { "El equipo está desactivado" }
        }
        require(input.initialSetCount == null || input.initialSetCount > 0) { "Las series iniciales deben ser mayores que 0" }
        require(input.initialLoad == null || input.initialLoad >= 0.0) { "La carga inicial no puede ser negativa" }
        require(input.initialMeasurement == null || input.initialMeasurement >= 0) { "La medición inicial no puede ser negativa" }

        return ValidatedExerciseInput(
            nameEs = nameEs,
            normalizedEs = normalizedEs,
            nameEn = nameEn,
            normalizedEn = normalizedEn,
            input = input,
        )
    }

    private suspend fun ensureMasterNameAvailable(
        kind: MasterCatalogKind,
        normalizedName: String,
        excludingId: String?,
    ) {
        val existingId = when (kind) {
            MasterCatalogKind.SESSION_TYPE -> dao.findSessionType(normalizedName)?.id
            MasterCatalogKind.MUSCLE_GROUP -> dao.findMuscleGroup(normalizedName)?.id
            MasterCatalogKind.EQUIPMENT -> dao.findEquipment(normalizedName)?.id
        }
        require(existingId == null || existingId == excludingId) { "Ya existe un elemento con ese nombre" }
    }

    private fun validateMasterName(name: String): String {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "El nombre es obligatorio" }
        return cleanName
    }

    private data class ValidatedExerciseInput(
        val nameEs: String,
        val normalizedEs: String,
        val nameEn: String,
        val normalizedEn: String,
        val input: ExerciseMaintenanceInput,
    ) {
        fun toEntity(id: String, favorite: Boolean, active: Boolean): ExerciseEntity = ExerciseEntity(
            id = id,
            nameEs = nameEs,
            normalizedNameEs = normalizedEs,
            nameEn = nameEn,
            normalizedNameEn = normalizedEn,
            muscleGroupId = input.muscleGroupId,
            equipmentId = input.equipmentId,
            defaultLoadMode = input.loadMode,
            defaultMeasurementUnit = input.measurementUnit,
            rirRequired = input.rirRequired,
            initialSetCount = input.initialSetCount,
            initialLoad = input.initialLoad,
            initialMeasurement = input.initialMeasurement,
            description = input.description?.trim()?.takeIf { it.isNotEmpty() },
            isFavorite = favorite,
            isActive = active,
        )
    }

    private data class DeletionExecution(
        val result: ExerciseDeletionResult,
        val userImageStorageKeys: List<String>,
    )
}

private fun ExerciseEntity.toMaintenanceData(): ExerciseMaintenanceData = ExerciseMaintenanceData(
    id = id,
    nameEs = nameEs,
    nameEn = nameEn,
    muscleGroupId = muscleGroupId,
    equipmentId = equipmentId,
    loadMode = defaultLoadMode,
    measurementUnit = defaultMeasurementUnit,
    rirRequired = rirRequired,
    initialSetCount = initialSetCount,
    initialLoad = initialLoad,
    initialMeasurement = initialMeasurement,
    description = description,
    isFavorite = isFavorite,
    isActive = isActive,
)
