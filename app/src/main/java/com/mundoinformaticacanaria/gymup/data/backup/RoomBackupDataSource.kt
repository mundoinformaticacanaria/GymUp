package com.mundoinformaticacanaria.gymup.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.core.util.normalizeName
import com.mundoinformaticacanaria.gymup.data.local.EquipmentEntity
import com.mundoinformaticacanaria.gymup.data.local.ExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.local.MuscleGroupEntity
import com.mundoinformaticacanaria.gymup.data.local.RoutineEntity
import com.mundoinformaticacanaria.gymup.data.local.RoutineExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionExerciseEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionSetEntity
import com.mundoinformaticacanaria.gymup.data.local.SessionTypeEntity
import com.mundoinformaticacanaria.gymup.data.preferences.UserPreferencesRepository
import com.mundoinformaticacanaria.gymup.domain.backup.BackupDataCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupDataV1
import com.mundoinformaticacanaria.gymup.domain.backup.BackupDataValidationResult
import com.mundoinformaticacanaria.gymup.domain.backup.BackupExercise
import com.mundoinformaticacanaria.gymup.domain.backup.BackupExerciseImage
import com.mundoinformaticacanaria.gymup.domain.backup.BackupNamedMaster
import com.mundoinformaticacanaria.gymup.domain.backup.BackupPreferences
import com.mundoinformaticacanaria.gymup.domain.backup.BackupRoutine
import com.mundoinformaticacanaria.gymup.domain.backup.BackupRoutineExercise
import com.mundoinformaticacanaria.gymup.domain.backup.BackupSession
import com.mundoinformaticacanaria.gymup.domain.backup.BackupSessionExercise
import com.mundoinformaticacanaria.gymup.domain.backup.BackupSessionSet
import com.mundoinformaticacanaria.gymup.domain.backup.BackupSessionType
import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot
import java.io.File
import java.util.UUID

class RoomBackupDataSource(
    context: Context,
    private val database: GymUpDatabase,
    private val preferences: UserPreferencesRepository,
) : BackupDataSource {
    private val backupDao = database.backupDao()
    private val imageStore = UserExerciseImageStore(context.applicationContext)

    override suspend fun exportSnapshot(): BackupSnapshot {
        val theme = preferences.currentThemeMode()
        val data = database.withTransaction {
            BackupDataV1(
                sessionTypes = backupDao.getSessionTypes().map { it.toBackup() },
                muscleGroups = backupDao.getMuscleGroups().map { it.toBackup() },
                equipment = backupDao.getEquipment().map { it.toBackup() },
                exercises = backupDao.getExercises().map { it.toBackup() },
                exerciseImages = backupDao.getExerciseImages().map { it.toBackup() },
                routines = backupDao.getRoutines().map { it.toBackup() },
                routineExercises = backupDao.getRoutineExercises().map { it.toBackup() },
                sessions = backupDao.getSessions().map { it.toBackup() },
                sessionExercises = backupDao.getSessionExercises().map { it.toBackup() },
                sessionSets = backupDao.getSessionSets().map { it.toBackup() },
                preferences = BackupPreferences(theme.name),
            )
        }
        check(BackupDataCodec.validate(data) == BackupDataValidationResult.Valid) {
            "El estado local no puede serializarse como backup v1"
        }
        val userImages = data.exerciseImages
            .filter { it.sourceType == USER_IMAGE_SOURCE }
            .associate { image -> image.storageKey to imageStore.readRequired(image.storageKey) }
        return BackupSnapshot(
            data = BackupDataCodec.encode(data).encodeToByteArray(),
            images = userImages,
        )
    }

    override suspend fun replaceAll(snapshot: BackupSnapshot) {
        val data = BackupDataCodec.decode(snapshot.data.decodeToString()).getOrElse {
            throw IllegalArgumentException("data.json no es JSON v1 válido", it)
        }
        when (val validation = BackupDataCodec.validate(data)) {
            BackupDataValidationResult.Valid -> Unit
            is BackupDataValidationResult.Invalid -> throw IllegalArgumentException(
                "data.json no válido: ${validation.reason}",
            )
        }

        val expectedImageKeys = data.exerciseImages
            .filter { it.sourceType == USER_IMAGE_SOURCE }
            .map { it.storageKey }
            .toSet()
        require(snapshot.images.keys == expectedImageKeys) {
            "Las imágenes del ZIP no coinciden con exercise_images"
        }
        val stagedImages = imageStore.stageReplacement(snapshot.images)

        try {
            database.withTransaction {
                backupDao.deleteSessionSets()
                backupDao.deleteSessionExercises()
                backupDao.deleteSessions()
                backupDao.deleteRoutineExercises()
                backupDao.deleteRoutines()
                backupDao.deleteExerciseImages()
                backupDao.deleteExercises()
                backupDao.deleteEquipment()
                backupDao.deleteMuscleGroups()
                backupDao.deleteSessionTypes()

                backupDao.insertSessionTypes(data.sessionTypes.map { it.toEntity() })
                backupDao.insertMuscleGroups(data.muscleGroups.map { it.toMuscleGroupEntity() })
                backupDao.insertEquipment(data.equipment.map { it.toEquipmentEntity() })
                backupDao.insertExercises(data.exercises.map { it.toEntity() })
                backupDao.insertExerciseImages(data.exerciseImages.map { it.toEntity() })
                backupDao.insertRoutines(data.routines.map { it.toEntity() })
                backupDao.insertRoutineExercises(data.routineExercises.map { it.toEntity() })
                backupDao.insertSessions(data.sessions.map { it.toEntity() })
                backupDao.insertSessionExercises(data.sessionExercises.map { it.toEntity() })
                backupDao.insertSessionSets(data.sessionSets.map { it.toEntity() })
            }
            preferences.setThemeMode(ThemeMode.valueOf(data.preferences.themeMode))
            stagedImages.commit()
        } catch (error: Throwable) {
            stagedImages.discard()
            throw error
        }
    }

    private fun SessionTypeEntity.toBackup() = BackupSessionType(id, name, isActive, isProtectedOther)
    private fun MuscleGroupEntity.toBackup() = BackupNamedMaster(id, name, isActive)
    private fun EquipmentEntity.toBackup() = BackupNamedMaster(id, name, isActive)
    private fun ExerciseEntity.toBackup() = BackupExercise(
        id = id,
        nameEs = nameEs,
        nameEn = nameEn,
        muscleGroupId = muscleGroupId,
        equipmentId = equipmentId,
        defaultLoadMode = defaultLoadMode.name,
        defaultMeasurementUnit = defaultMeasurementUnit.name,
        rirRequired = rirRequired,
        initialSetCount = initialSetCount,
        initialLoad = initialLoad,
        initialMeasurement = initialMeasurement,
        description = description,
        isFavorite = isFavorite,
        isActive = isActive,
    )
    private fun ExerciseImageEntity.toBackup() = BackupExerciseImage(
        id, exerciseId, position, sourceType, storageKey, originalSourceUrl, author, license,
    )
    private fun RoutineEntity.toBackup() = BackupRoutine(id, name, suggestedSessionTypeId, description)
    private fun RoutineExerciseEntity.toBackup() = BackupRoutineExercise(routineId, exerciseId, position)
    private fun SessionEntity.toBackup() = BackupSession(
        id = id,
        dateEpochDay = sessionDateEpochDay,
        orderInDay = orderInDay,
        sessionTypeId = sessionTypeId,
        sessionTypeNameSnapshot = sessionTypeNameSnapshot,
        name = name,
        isAutoName = isAutoName,
        generalNote = generalNote,
        operationalState = operationalState.name,
        executionResult = executionResult.name,
    )
    private fun SessionExerciseEntity.toBackup() = BackupSessionExercise(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        position = position,
        nameEsSnapshot = exerciseNameEsSnapshot,
        nameEnSnapshot = exerciseNameEnSnapshot,
        muscleGroupSnapshot = muscleGroupNameSnapshot,
        equipmentSnapshot = equipmentNameSnapshot,
        loadModeSnapshot = defaultLoadModeSnapshot.name,
        measurementUnitSnapshot = defaultMeasurementUnitSnapshot.name,
        rirRequiredSnapshot = rirRequiredSnapshot,
        descriptionSnapshot = descriptionSnapshot,
        exerciseRestSeconds = exerciseRestSeconds,
        note = note,
        incompleteReason = incompleteReason,
        isFinalized = isFinalized,
    )
    private fun SessionSetEntity.toBackup() = BackupSessionSet(
        id = id,
        sessionExerciseId = sessionExerciseId,
        position = position,
        loadMode = loadMode.name,
        measurementUnit = measurementUnit.name,
        targetLoad = targetLoad,
        actualLoad = actualLoad,
        targetMeasurement = targetMeasurement,
        actualMeasurement = actualMeasurement,
        rir = rir,
        restOverrideSeconds = restOverrideSeconds,
        actualConfirmed = actualConfirmed,
    )

    private fun BackupSessionType.toEntity() = SessionTypeEntity(
        id = id,
        name = name,
        normalizedName = normalizeName(name),
        isActive = isActive,
        isProtectedOther = isProtectedOther,
    )
    private fun BackupNamedMaster.toMuscleGroupEntity() = MuscleGroupEntity(id, name, normalizeName(name), isActive)
    private fun BackupNamedMaster.toEquipmentEntity() = EquipmentEntity(id, name, normalizeName(name), isActive)
    private fun BackupExercise.toEntity() = ExerciseEntity(
        id = id,
        nameEs = nameEs,
        normalizedNameEs = normalizeName(nameEs),
        nameEn = nameEn,
        normalizedNameEn = normalizeName(nameEn),
        muscleGroupId = muscleGroupId,
        equipmentId = equipmentId,
        defaultLoadMode = LoadMode.valueOf(defaultLoadMode),
        defaultMeasurementUnit = MeasurementUnit.valueOf(defaultMeasurementUnit),
        rirRequired = rirRequired,
        initialSetCount = initialSetCount,
        initialLoad = initialLoad,
        initialMeasurement = initialMeasurement,
        description = description,
        isFavorite = isFavorite,
        isActive = isActive,
    )
    private fun BackupExerciseImage.toEntity() = ExerciseImageEntity(
        id, exerciseId, position, sourceType, storageKey, originalSourceUrl, author, license,
    )
    private fun BackupRoutine.toEntity() = RoutineEntity(id, name, suggestedSessionTypeId, description)
    private fun BackupRoutineExercise.toEntity() = RoutineExerciseEntity(routineId, exerciseId, position)
    private fun BackupSession.toEntity() = SessionEntity(
        id = id,
        sessionDateEpochDay = dateEpochDay,
        orderInDay = orderInDay,
        sessionTypeId = sessionTypeId,
        sessionTypeNameSnapshot = sessionTypeNameSnapshot,
        name = name,
        isAutoName = isAutoName,
        generalNote = generalNote,
        operationalState = SessionOperationalState.valueOf(operationalState),
        executionResult = SessionExecutionResult.valueOf(executionResult),
    )
    private fun BackupSessionExercise.toEntity() = SessionExerciseEntity(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        position = position,
        exerciseNameEsSnapshot = nameEsSnapshot,
        exerciseNameEnSnapshot = nameEnSnapshot,
        muscleGroupNameSnapshot = muscleGroupSnapshot,
        equipmentNameSnapshot = equipmentSnapshot,
        defaultLoadModeSnapshot = LoadMode.valueOf(loadModeSnapshot),
        defaultMeasurementUnitSnapshot = MeasurementUnit.valueOf(measurementUnitSnapshot),
        rirRequiredSnapshot = rirRequiredSnapshot,
        descriptionSnapshot = descriptionSnapshot,
        exerciseRestSeconds = exerciseRestSeconds,
        note = note,
        incompleteReason = incompleteReason,
        isFinalized = isFinalized,
    )
    private fun BackupSessionSet.toEntity() = SessionSetEntity(
        id = id,
        sessionExerciseId = sessionExerciseId,
        position = position,
        loadMode = LoadMode.valueOf(loadMode),
        measurementUnit = MeasurementUnit.valueOf(measurementUnit),
        targetLoad = targetLoad,
        actualLoad = actualLoad,
        targetMeasurement = targetMeasurement,
        actualMeasurement = actualMeasurement,
        rir = rir,
        restOverrideSeconds = restOverrideSeconds,
        actualConfirmed = actualConfirmed,
    )

    companion object {
        private const val USER_IMAGE_SOURCE = "USER"
    }
}

private class UserExerciseImageStore(context: Context) {
    private val parentDir = context.filesDir
    private val rootDir = File(parentDir, DIRECTORY)

    fun readRequired(storageKey: String): ByteArray = fileFor(rootDir, storageKey).let { file ->
        require(file.isFile) { "Falta la imagen personalizada $storageKey" }
        file.readBytes()
    }

    fun stageReplacement(images: Map<String, ByteArray>): StagedImageReplacement {
        val staging = File(parentDir, "$DIRECTORY-import-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "No se pudo preparar la importación de imágenes" }
        try {
            images.forEach { (storageKey, bytes) ->
                require(storageKey.matches(Regex("^user:[0-9a-fA-F-]{36}$"))) { "storage_key no válido" }
                val target = fileFor(staging, storageKey)
                target.writeBytes(bytes)
                check(target.length() == bytes.size.toLong()) { "No se pudo escribir $storageKey" }
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
        return StagedImageReplacement(rootDir, staging)
    }

    private fun fileFor(directory: File, storageKey: String): File =
        File(directory, storageKey.removePrefix("user:"))

    companion object {
        private const val DIRECTORY = "exercise-images"
    }
}

private class StagedImageReplacement(
    private val current: File,
    private val staged: File,
) {
    private var finished = false

    fun commit() {
        check(!finished) { "La sustitución de imágenes ya terminó" }
        val previous = File(current.parentFile, "${current.name}-previous")
        previous.deleteRecursively()
        if (current.exists() && !current.renameTo(previous)) {
            throw IllegalStateException("No se pudieron preservar las imágenes actuales")
        }
        if (!staged.renameTo(current)) {
            if (previous.exists()) previous.renameTo(current)
            throw IllegalStateException("No se pudieron activar las imágenes importadas")
        }
        previous.deleteRecursively()
        finished = true
    }

    fun discard() {
        if (!finished) staged.deleteRecursively()
        finished = true
    }
}
