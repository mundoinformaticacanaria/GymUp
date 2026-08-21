package com.mundoinformaticacanaria.gymup.domain.backup

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.core.util.normalizeName
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupDataV1(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("session_types") val sessionTypes: List<BackupSessionType>,
    @SerialName("muscle_groups") val muscleGroups: List<BackupNamedMaster>,
    val equipment: List<BackupNamedMaster>,
    val exercises: List<BackupExercise>,
    @SerialName("exercise_images") val exerciseImages: List<BackupExerciseImage>,
    val routines: List<BackupRoutine>,
    @SerialName("routine_exercises") val routineExercises: List<BackupRoutineExercise>,
    val sessions: List<BackupSession>,
    @SerialName("session_exercises") val sessionExercises: List<BackupSessionExercise>,
    @SerialName("session_sets") val sessionSets: List<BackupSessionSet>,
    val preferences: BackupPreferences,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class BackupNamedMaster(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class BackupSessionType(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("is_protected_other") val isProtectedOther: Boolean,
)

@Serializable
data class BackupExercise(
    val id: String,
    @SerialName("name_es") val nameEs: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("muscle_group_id") val muscleGroupId: String,
    @SerialName("equipment_id") val equipmentId: String?,
    @SerialName("default_load_mode") val defaultLoadMode: String,
    @SerialName("default_measurement_unit") val defaultMeasurementUnit: String,
    @SerialName("rir_required") val rirRequired: Boolean,
    @SerialName("initial_set_count") val initialSetCount: Int?,
    @SerialName("initial_load") val initialLoad: Double?,
    @SerialName("initial_measurement") val initialMeasurement: Int?,
    val description: String?,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class BackupExerciseImage(
    val id: String,
    @SerialName("exercise_id") val exerciseId: String,
    val position: Int,
    @SerialName("source_type") val sourceType: String,
    @SerialName("storage_key") val storageKey: String,
    @SerialName("original_source_url") val originalSourceUrl: String?,
    val author: String?,
    val license: String?,
)

@Serializable
data class BackupRoutine(
    val id: String,
    val name: String,
    @SerialName("suggested_session_type_id") val suggestedSessionTypeId: String?,
    val description: String?,
)

@Serializable
data class BackupRoutineExercise(
    @SerialName("routine_id") val routineId: String,
    @SerialName("exercise_id") val exerciseId: String,
    val position: Int,
)

@Serializable
data class BackupSession(
    val id: String,
    @SerialName("date_epoch_day") val dateEpochDay: Long,
    @SerialName("order_in_day") val orderInDay: Int,
    @SerialName("session_type_id") val sessionTypeId: String,
    @SerialName("session_type_name_snapshot") val sessionTypeNameSnapshot: String,
    val name: String,
    @SerialName("is_auto_name") val isAutoName: Boolean,
    @SerialName("general_note") val generalNote: String?,
    @SerialName("operational_state") val operationalState: String,
    @SerialName("execution_result") val executionResult: String,
)

@Serializable
data class BackupSessionExercise(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("exercise_id") val exerciseId: String,
    val position: Int,
    @SerialName("name_es_snapshot") val nameEsSnapshot: String,
    @SerialName("name_en_snapshot") val nameEnSnapshot: String,
    @SerialName("muscle_group_snapshot") val muscleGroupSnapshot: String,
    @SerialName("equipment_snapshot") val equipmentSnapshot: String?,
    @SerialName("load_mode_snapshot") val loadModeSnapshot: String,
    @SerialName("measurement_unit_snapshot") val measurementUnitSnapshot: String,
    @SerialName("rir_required_snapshot") val rirRequiredSnapshot: Boolean,
    @SerialName("description_snapshot") val descriptionSnapshot: String?,
    @SerialName("exercise_rest_seconds") val exerciseRestSeconds: Int?,
    val note: String?,
    @SerialName("incomplete_reason") val incompleteReason: String?,
    @SerialName("is_finalized") val isFinalized: Boolean,
)

@Serializable
data class BackupSessionSet(
    val id: String,
    @SerialName("session_exercise_id") val sessionExerciseId: String,
    val position: Int,
    @SerialName("load_mode") val loadMode: String,
    @SerialName("measurement_unit") val measurementUnit: String,
    @SerialName("target_load") val targetLoad: Double?,
    @SerialName("actual_load") val actualLoad: Double?,
    @SerialName("target_measurement") val targetMeasurement: Int?,
    @SerialName("actual_measurement") val actualMeasurement: Int?,
    val rir: Int?,
    @SerialName("rest_override_seconds") val restOverrideSeconds: Int?,
    @SerialName("actual_confirmed") val actualConfirmed: Boolean,
)

@Serializable
data class BackupPreferences(
    @SerialName("theme_mode") val themeMode: String,
)

sealed interface BackupDataValidationResult {
    data object Valid : BackupDataValidationResult
    data class Invalid(val reason: String) : BackupDataValidationResult
}

object BackupDataCodec {
    private val json = Json {
        prettyPrint = true
        explicitNulls = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(data: BackupDataV1): String = json.encodeToString(data)

    fun decode(raw: String): Result<BackupDataV1> = runCatching { json.decodeFromString(raw) }

    fun validate(data: BackupDataV1): BackupDataValidationResult {
        fun invalid(reason: String) = BackupDataValidationResult.Invalid(reason)
        if (data.schemaVersion != BackupDataV1.SCHEMA_VERSION) return invalid("schema_version no soportado: ${data.schemaVersion}")

        val sessionTypeIds = data.sessionTypes.map { it.id }.toSet()
        val muscleGroupIds = data.muscleGroups.map { it.id }.toSet()
        val equipmentIds = data.equipment.map { it.id }.toSet()
        val exerciseIds = data.exercises.map { it.id }.toSet()
        val routineIds = data.routines.map { it.id }.toSet()
        val sessionIds = data.sessions.map { it.id }.toSet()
        val sessionExerciseIds = data.sessionExercises.map { it.id }.toSet()

        if (sessionTypeIds.size != data.sessionTypes.size) return invalid("IDs duplicados en session_types")
        if (muscleGroupIds.size != data.muscleGroups.size) return invalid("IDs duplicados en muscle_groups")
        if (equipmentIds.size != data.equipment.size) return invalid("IDs duplicados en equipment")
        if (exerciseIds.size != data.exercises.size) return invalid("IDs duplicados en exercises")
        if (routineIds.size != data.routines.size) return invalid("IDs duplicados en routines")
        if (sessionIds.size != data.sessions.size) return invalid("IDs duplicados en sessions")
        if (sessionExerciseIds.size != data.sessionExercises.size) return invalid("IDs duplicados en session_exercises")
        if (data.sessionSets.map { it.id }.toSet().size != data.sessionSets.size) return invalid("IDs duplicados en session_sets")
        if (data.exerciseImages.map { it.id }.toSet().size != data.exerciseImages.size) return invalid("IDs duplicados en exercise_images")

        val allUuidValues = buildList {
            addAll(sessionTypeIds)
            addAll(muscleGroupIds)
            addAll(equipmentIds)
            addAll(exerciseIds)
            addAll(routineIds)
            addAll(sessionIds)
            addAll(sessionExerciseIds)
            addAll(data.sessionSets.map { it.id })
            addAll(data.exerciseImages.map { it.id })
        }
        val invalidUuid = allUuidValues.firstOrNull { runCatching { UUID.fromString(it) }.isFailure }
        if (invalidUuid != null) return invalid("UUID no válido: $invalidUuid")

        fun uniqueNormalized(names: List<String>, label: String): BackupDataValidationResult? {
            if (names.any { it.isBlank() }) return invalid("Nombre vacío en $label")
            if (names.map(::normalizeName).toSet().size != names.size) return invalid("Nombres duplicados normalizados en $label")
            return null
        }
        uniqueNormalized(data.sessionTypes.map { it.name }, "session_types")?.let { return it }
        uniqueNormalized(data.muscleGroups.map { it.name }, "muscle_groups")?.let { return it }
        uniqueNormalized(data.equipment.map { it.name }, "equipment")?.let { return it }
        uniqueNormalized(data.exercises.map { it.nameEs }, "exercises.name_es")?.let { return it }
        uniqueNormalized(data.exercises.map { it.nameEn }, "exercises.name_en")?.let { return it }

        for (exercise in data.exercises) {
            if (exercise.muscleGroupId !in muscleGroupIds) return invalid("Grupo inexistente en ejercicio ${exercise.id}")
            if (exercise.equipmentId != null && exercise.equipmentId !in equipmentIds) return invalid("Equipo inexistente en ejercicio ${exercise.id}")
            if (runCatching { LoadMode.valueOf(exercise.defaultLoadMode) }.isFailure) return invalid("LoadMode no válido en ejercicio ${exercise.id}")
            if (runCatching { MeasurementUnit.valueOf(exercise.defaultMeasurementUnit) }.isFailure) return invalid("MeasurementUnit no válida en ejercicio ${exercise.id}")
            if (exercise.initialSetCount != null && exercise.initialSetCount <= 0) return invalid("initial_set_count no válido en ${exercise.id}")
            if (exercise.initialLoad != null && exercise.initialLoad < 0) return invalid("initial_load negativo en ${exercise.id}")
            if (exercise.initialMeasurement != null && exercise.initialMeasurement < 0) return invalid("initial_measurement negativo en ${exercise.id}")
        }

        for (image in data.exerciseImages) {
            if (image.exerciseId !in exerciseIds) return invalid("Imagen huérfana ${image.id}")
            if (image.position !in 1..3) return invalid("Posición de imagen no válida ${image.id}")
            if (image.sourceType !in setOf("SEED", "USER")) return invalid("source_type no válido ${image.id}")
            if (image.storageKey.isBlank()) return invalid("storage_key vacío ${image.id}")
            if (image.sourceType == "USER" && !image.storageKey.matches(Regex("^user:[0-9a-fA-F-]{36}$"))) {
                return invalid("storage_key USER no válido ${image.id}")
            }
        }
        if (data.exerciseImages.groupBy { it.exerciseId }.any { (_, images) -> images.map { it.position }.toSet().size != images.size }) {
            return invalid("Posiciones de imagen duplicadas")
        }

        for (routine in data.routines) {
            if (routine.name.isBlank()) return invalid("Rutina sin nombre ${routine.id}")
            if (routine.suggestedSessionTypeId != null && routine.suggestedSessionTypeId !in sessionTypeIds) {
                return invalid("Tipo sugerido inexistente en rutina ${routine.id}")
            }
        }
        for (item in data.routineExercises) {
            if (item.routineId !in routineIds || item.exerciseId !in exerciseIds) return invalid("Referencia inválida en routine_exercises")
            if (item.position <= 0) return invalid("Posición de rutina no válida")
        }
        if (data.routineExercises.groupBy { it.routineId }.any { (_, rows) ->
                rows.map { it.position }.toSet().size != rows.size || rows.map { it.exerciseId }.toSet().size != rows.size
            }
        ) return invalid("Duplicados en routine_exercises")

        for (session in data.sessions) {
            if (session.sessionTypeId !in sessionTypeIds) return invalid("Tipo inexistente en sesión ${session.id}")
            if (session.orderInDay <= 0) return invalid("order_in_day no válido en sesión ${session.id}")
            if (session.name.isBlank() || session.sessionTypeNameSnapshot.isBlank()) return invalid("Snapshot de sesión incompleto ${session.id}")
            if (runCatching { SessionOperationalState.valueOf(session.operationalState) }.isFailure) return invalid("Estado operativo no válido ${session.id}")
            if (runCatching { SessionExecutionResult.valueOf(session.executionResult) }.isFailure) return invalid("Resultado no válido ${session.id}")
        }
        if (data.sessions.groupBy { it.dateEpochDay }.any { (_, rows) -> rows.map { it.orderInDay }.toSet().size != rows.size }) {
            return invalid("order_in_day duplicado para una fecha")
        }

        for (exercise in data.sessionExercises) {
            if (exercise.sessionId !in sessionIds || exercise.exerciseId !in exerciseIds) return invalid("Referencia inválida en session_exercises ${exercise.id}")
            if (exercise.position <= 0) return invalid("Posición no válida en session_exercises ${exercise.id}")
            if (runCatching { LoadMode.valueOf(exercise.loadModeSnapshot) }.isFailure) return invalid("LoadMode snapshot no válido ${exercise.id}")
            if (runCatching { MeasurementUnit.valueOf(exercise.measurementUnitSnapshot) }.isFailure) return invalid("MeasurementUnit snapshot no válida ${exercise.id}")
            if (exercise.exerciseRestSeconds != null && exercise.exerciseRestSeconds < 0) return invalid("Descanso negativo ${exercise.id}")
        }
        if (data.sessionExercises.groupBy { it.sessionId }.any { (_, rows) ->
                rows.map { it.position }.toSet().size != rows.size || rows.map { it.exerciseId }.toSet().size != rows.size
            }
        ) return invalid("Duplicados en session_exercises")

        for (set in data.sessionSets) {
            if (set.sessionExerciseId !in sessionExerciseIds) return invalid("Serie huérfana ${set.id}")
            if (set.position <= 0) return invalid("Posición de serie no válida ${set.id}")
            if (runCatching { LoadMode.valueOf(set.loadMode) }.isFailure) return invalid("LoadMode no válido en serie ${set.id}")
            if (runCatching { MeasurementUnit.valueOf(set.measurementUnit) }.isFailure) return invalid("MeasurementUnit no válida en serie ${set.id}")
            if (listOf(set.targetLoad, set.actualLoad).any { it != null && it < 0 }) return invalid("Carga negativa en serie ${set.id}")
            if (listOf(set.targetMeasurement, set.actualMeasurement).any { it != null && it < 0 }) return invalid("Medición negativa en serie ${set.id}")
            if (set.rir != null && set.rir !in 0..2) return invalid("RIR no válido en serie ${set.id}")
            if (set.restOverrideSeconds != null && set.restOverrideSeconds < 0) return invalid("Descanso negativo en serie ${set.id}")
        }
        if (data.sessionSets.groupBy { it.sessionExerciseId }.any { (_, rows) -> rows.map { it.position }.toSet().size != rows.size }) {
            return invalid("Posiciones duplicadas en session_sets")
        }

        if (runCatching { ThemeMode.valueOf(data.preferences.themeMode) }.isFailure) return invalid("theme_mode no válido")
        return BackupDataValidationResult.Valid
    }
}
