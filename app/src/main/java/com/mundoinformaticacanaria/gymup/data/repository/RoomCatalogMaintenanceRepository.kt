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
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomCatalogMaintenanceRepository(
    context: Context,
    private val database: GymUpDatabase,
    // Deterministic seams for failure testing; production uses filesystem operations.
    private val removeImageFile: (File) -> Boolean = File::delete,
    private val syncImageDirectory: (File) -> Unit = ::syncDirectoryMetadata,
) : CatalogMaintenanceRepository {
    private val dao: CatalogMaintenanceDao = database.catalogMaintenanceDao()
    private val filesDirectory = context.applicationContext.filesDir
    private val imageDirectory = File(filesDirectory, "exercise-images")

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
        val validated = validateExerciseInput(input, excludingId = null, current = null)
        val id = UUID.randomUUID().toString()
        dao.insertExercise(validated.toEntity(id = id, favorite = false, active = true))
        return id
    }

    override suspend fun updateExercise(exerciseId: String, input: ExerciseMaintenanceInput) {
        val current = requireNotNull(dao.getExercise(exerciseId)) { "Ejercicio inexistente" }
        val validated = validateExerciseInput(input, excludingId = exerciseId, current = current)
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
            // Validate all user-image references before committing the Room deletion.
            // Corrupt or legacy keys must never resolve outside the private image directory
            // or silently skip cleanup after the exercise and routine links are gone.
            val imageStorageKeys = dao.getExerciseImages(exerciseId)
                .filter { it.sourceType == "USER" }
                .map { validatedUserImageStorageKey(it.storageKey) }
            // The schema does not enforce unique storage_key across image rows. Never
            // remove bytes still referenced by another image, even in corrupt/legacy data.
            imageStorageKeys.forEach { storageKey ->
                require(dao.countUserImageStorageKeyReferences(storageKey) == 1) {
                    "La imagen personalizada tiene referencias duplicadas: baja cancelada"
                }
            }
            if (routineReferences > 0) dao.removeRoutineReferences(exerciseId)
            dao.deleteExercise(exerciseId)
            DeletionExecution(
                ExerciseDeletionResult.Deleted(removedRoutineReferences = routineReferences),
                imageStorageKeys,
            )
        }
        // Room has already committed. Never report success when private image cleanup fails:
        // this would silently leave orphan files. A durable journal is still required to
        // recover an interrupted delete and to handle multi-image partial cleanup.
        execution.userImageStorageKeys.forEach { storageKey ->
            // Recheck the directory itself before touching image bytes. A symlink
            // at the root could otherwise redirect cleanup outside app-private storage.
            requireSafeImageDirectory()
            val file = File(imageDirectory, storageKey.removePrefix("user:"))
            // File.exists() follows symlinks: a dangling link reports false and must
            // not be silently skipped after Room has already committed the deletion.
            if (Files.isSymbolicLink(file.toPath())) {
                throw IOException("La ruta de imagen del ejercicio es un enlace simbólico")
            }
            if (file.exists()) {
                // A corrupt on-disk entry must not turn image cleanup into directory removal.
                if (!file.isFile) {
                    throw IOException("La ruta de imagen del ejercicio no es un archivo regular")
                }
                if (!removeImageFile(file) || file.exists()) {
                    throw IOException("No se pudo eliminar el archivo de imagen del ejercicio; la baja en Room ya se confirmó")
                }
                syncImageDirectory(imageDirectory)
            }
        }
        return execution.result
    }

    private fun validatedUserImageStorageKey(storageKey: String): String {
        require(storageKey.startsWith("user:")) {
            "Referencia de imagen personalizada sin prefijo válido: baja cancelada"
        }
        val fileName = storageKey.removePrefix("user:")
        require(
            fileName.isNotBlank() && fileName != "." && fileName != ".." &&
                fileName.none { it == '/' || it == '\\' || it == '\u0000' },
        ) {
            "Ruta de imagen personalizada no válida: baja cancelada"
        }
        // The directory root must also be safe; checking only the final image entry
        // would follow a symlinked exercise-images root into an unrelated directory.
        requireSafeImageDirectory()
        // Validate filesystem object type before Room commits the exercise deletion.
        // A missing image is tolerated (legacy/incomplete state), but an existing directory
        // or symbolic link must not be deleted under the guise of an image.
        val imageFile = File(imageDirectory, fileName)
        // Check the link itself before exists(): a dangling symlink has exists() == false.
        require(!Files.isSymbolicLink(imageFile.toPath()) && (!imageFile.exists() || imageFile.isFile)) {
            "La ruta de imagen personalizada no es un archivo regular: baja cancelada"
        }
        return storageKey
    }

    private fun requireSafeImageDirectory() {
        // A safe final exercise-images path is insufficient when filesDir itself
        // is a symlink: deleting an image could otherwise reach external bytes.
        // Check before the Room transaction commits and again before each delete.
        require(Files.isDirectory(filesDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "El directorio privado de la aplicación no es seguro: baja cancelada"
        }
        require(!Files.isSymbolicLink(imageDirectory.toPath())) {
            "El directorio de imágenes es un enlace simbólico: baja cancelada"
        }
        require(!imageDirectory.exists() || imageDirectory.isDirectory) {
            "La ruta de imágenes no es un directorio: baja cancelada"
        }
    }

    private suspend fun validateExerciseInput(
        input: ExerciseMaintenanceInput,
        excludingId: String?,
        current: ExerciseEntity?,
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
        val keepsCurrentGroup = current?.muscleGroupId == input.muscleGroupId
        require(group.isActive || keepsCurrentGroup) {
            "No se puede asignar un grupo muscular desactivado a un ejercicio nuevo"
        }
        input.equipmentId?.let { equipmentId ->
            val equipment = requireNotNull(dao.getEquipment(equipmentId)) { "Equipo inexistente" }
            val keepsCurrentEquipment = current?.equipmentId == equipmentId
            require(equipment.isActive || keepsCurrentEquipment) {
                "No se puede asignar un equipo desactivado a un ejercicio nuevo"
            }
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

    private companion object {
        fun syncDirectoryMetadata(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
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
