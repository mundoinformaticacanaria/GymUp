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
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class RoomBackupDataSource(
    context: Context,
    private val database: GymUpDatabase,
    private val preferences: UserPreferencesRepository,
    // Test seam for proving Room's transaction stays open until image capture finishes.
    private val exportAfterRoomRead: suspend () -> Unit = {},
    // Test seam: simulate a staging failure after syncing image bytes, before Room mutation.
    private val afterStagedImageSync: (String) -> Unit = {},
    // Deterministic seam for directory fsync failures at the rename boundaries.
    private val imageDirectorySync: (File) -> Unit = ::syncBackupImageDirectory,
    private val importFault: (BackupReplacePhase) -> Unit = {},
) : BackupDataSource {
    private val backupDao = database.backupDao()
    private val imageStore = UserExerciseImageStore(context.applicationContext, afterStagedImageSync, imageDirectorySync)

    override suspend fun exportSnapshot(): BackupSnapshot = database.withTransaction {
        // Keep Room's read transaction open through the image capture. Previously Room
        // was released before reading files, allowing metadata and image bytes to diverge
        // when a concurrent Room writer changed the image references.
        // This does NOT serialize direct image-file or DataStore writes: a global domain
        // barrier remains mandatory before enabling crash-recovery imports.
        val theme = preferences.currentThemeModeForBackup()
        val data =
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
        check(BackupDataCodec.validate(data) == BackupDataValidationResult.Valid) {
            "El estado local no puede serializarse como backup v1"
        }
        exportAfterRoomRead()
        val userImages = data.exerciseImages
            .filter { it.sourceType == USER_IMAGE_SOURCE }
            .associate { image -> image.storageKey to imageStore.readRequired(image.storageKey) }
        BackupSnapshot(
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
        val previousTheme = preferences.currentThemeModeForBackup()
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
                // Keep Room uncommitted until images and preferences have been activated.
                importFault(BackupReplacePhase.BEFORE_IMAGES_ACTIVATED)
                stagedImages.activate()
                importFault(BackupReplacePhase.AFTER_IMAGES_ACTIVATED)
                preferences.setThemeMode(ThemeMode.valueOf(data.preferences.themeMode))
                importFault(BackupReplacePhase.AFTER_PREFERENCES_UPDATED)
            }
        } catch (error: Throwable) {
            // Room rolls back its transaction; restore the other stores even on cancellation.
            withContext(NonCancellable) {
                runCatching { stagedImages.rollback() }.onFailure(error::addSuppressed)
                runCatching { preferences.setThemeMode(previousTheme) }.onFailure(error::addSuppressed)
            }
            throw error
        }
        // Cleanup is best-effort: never turn a committed import into a false failure.
        stagedImages.complete()
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

private class UserExerciseImageStore(
    context: Context,
    private val afterStagedImageSync: (String) -> Unit,
    private val syncDirectory: (File) -> Unit,
) {
    private val parentDir = context.filesDir
    private val rootDir = File(parentDir, DIRECTORY)

    fun readRequired(storageKey: String): ByteArray {
        // A corrupted Room reference must not turn an export (or recovery pre-image)
        // into a read outside the private image store. Do not follow symlinks, even
        // dangling ones, and reject the image directory itself if it is a symlink.
        require(storageKey.matches(Regex("^user:[0-9a-fA-F-]{36}$"))) {
            "storage_key de imagen personalizada no válido"
        }
        check(!Files.isSymbolicLink(rootDir.toPath())) {
            "El directorio de imágenes personalizadas es un enlace simbólico"
        }
        val file = fileFor(rootDir, storageKey)
        check(!Files.isSymbolicLink(file.toPath()) && file.isFile) {
            "Imagen personalizada ausente o insegura: $storageKey"
        }
        return file.readBytes()
    }

    fun stageReplacement(images: Map<String, ByteArray>): StagedImageReplacement {
        val staging = File(parentDir, "$DIRECTORY-import-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "No se pudo preparar la importación de imágenes" }
        try {
            images.forEach { (storageKey, bytes) ->
                require(storageKey.matches(Regex("^user:[0-9a-fA-F-]{36}$"))) { "storage_key no válido" }
                val target = fileFor(staging, storageKey)
                // Sync staged image bytes before the journal can later become COMMITTED.
                // This is necessary but not sufficient for power-loss durability: directory
                // rename metadata still needs an explicit durability strategy/validation.
                FileOutputStream(target).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                afterStagedImageSync(storageKey)
                check(target.length() == bytes.size.toLong()) { "No se pudo escribir $storageKey" }
            }
            // Persist the staging directory entries and its parent before activation.
            // File byte fsync alone does not make newly created image names durable.
            syncDirectory(staging)
            syncDirectory(parentDir)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
        return StagedImageReplacement(rootDir, staging, syncDirectory)
    }

    private fun fileFor(directory: File, storageKey: String): File =
        File(directory, storageKey.removePrefix("user:"))

    companion object {
        private const val DIRECTORY = "exercise-images"
    }
}

/** Deterministic fault points for the non-atomic Room/files/DataStore import path. */
enum class BackupReplacePhase { BEFORE_IMAGES_ACTIVATED, AFTER_IMAGES_ACTIVATED, AFTER_PREFERENCES_UPDATED }

/** Sync metadata after image directory mutations; failures propagate before Room commits. */
private fun syncBackupImageDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

private class StagedImageReplacement(
    private val current: File,
    private val staged: File,
    private val syncDirectory: (File) -> Unit,
) {
    // Unique path: never delete another operation's preserved user images.
    private val previous = File(current.parentFile, "${current.name}-previous-${UUID.randomUUID()}")
    private var oldMoved = false
    private var activated = false
    private var finished = false

    fun activate() {
        check(!finished && !activated) { "La sustitución de imágenes ya se activó" }
        if (current.exists()) {
            check(current.renameTo(previous)) { "No se pudieron preservar las imágenes actuales" }
            oldMoved = true
            // Preserve the old directory entry before publishing the replacement.
            syncDirectory(current.parentFile)
        }
        check(staged.renameTo(current)) { "No se pudieron activar las imágenes importadas" }
        // Mark active BEFORE syncing: if fsync fails, rollback must remove the new
        // directory and restore the preserved previous one.
        activated = true
        syncDirectory(current.parentFile)
    }

    fun rollback() {
        check(!finished) { "La sustitución de imágenes ya terminó" }
        // Preserve the previous directory if deletion/restoration fails; never erase it.
        if (activated && current.exists()) {
            check(current.deleteRecursively()) { "No se pudieron retirar las imágenes importadas" }
        }
        if (oldMoved) {
            check(previous.renameTo(current)) { "No se pudieron recuperar las imágenes anteriores" }
        }
        // Recovery is not complete until the restored directory entry is synced.
        syncDirectory(current.parentFile)
        staged.deleteRecursively()
        finished = true
    }

    fun complete() {
        check(activated && !finished) { "La sustitución de imágenes no está activada" }
        // A failed cleanup leaves a recoverable copy rather than reporting an import failure.
        previous.deleteRecursively()
        finished = true
    }
}
