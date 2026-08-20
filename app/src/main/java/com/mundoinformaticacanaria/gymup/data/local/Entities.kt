package com.mundoinformaticacanaria.gymup.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @androidx.room.PrimaryKey
    val key: String,
    val value: String,
)

@Entity(tableName = "session_types", indices = [Index(value = ["normalized_name"], unique = true)])
data class SessionTypeEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "is_protected_other") val isProtectedOther: Boolean = false,
)

@Entity(tableName = "muscle_groups", indices = [Index(value = ["normalized_name"], unique = true)])
data class MuscleGroupEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)

@Entity(tableName = "equipment", indices = [Index(value = ["normalized_name"], unique = true)])
data class EquipmentEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)

@Entity(
    tableName = "exercises",
    foreignKeys = [
        ForeignKey(entity = MuscleGroupEntity::class, parentColumns = ["id"], childColumns = ["muscle_group_id"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = EquipmentEntity::class, parentColumns = ["id"], childColumns = ["equipment_id"], onDelete = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["normalized_name_es"], unique = true),
        Index(value = ["normalized_name_en"], unique = true),
        Index(value = ["muscle_group_id", "is_active"]),
        Index(value = ["equipment_id"]),
        Index(value = ["is_favorite", "is_active"]),
    ],
)
data class ExerciseEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "name_es") val nameEs: String,
    @ColumnInfo(name = "normalized_name_es") val normalizedNameEs: String,
    @ColumnInfo(name = "name_en") val nameEn: String,
    @ColumnInfo(name = "normalized_name_en") val normalizedNameEn: String,
    @ColumnInfo(name = "muscle_group_id") val muscleGroupId: String,
    @ColumnInfo(name = "equipment_id") val equipmentId: String?,
    @ColumnInfo(name = "default_load_mode") val defaultLoadMode: LoadMode,
    @ColumnInfo(name = "default_measurement_unit") val defaultMeasurementUnit: MeasurementUnit,
    @ColumnInfo(name = "rir_required") val rirRequired: Boolean = true,
    @ColumnInfo(name = "initial_set_count") val initialSetCount: Int?,
    @ColumnInfo(name = "initial_load") val initialLoad: Double?,
    @ColumnInfo(name = "initial_measurement") val initialMeasurement: Int?,
    val description: String?,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)

@Entity(
    tableName = "exercise_images",
    foreignKeys = [ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exercise_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["exercise_id", "position"], unique = true)],
)
data class ExerciseImageEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val position: Int,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "storage_key") val storageKey: String,
    @ColumnInfo(name = "original_source_url") val originalSourceUrl: String?,
    val author: String?,
    val license: String?,
)

@Entity(
    tableName = "routines",
    foreignKeys = [ForeignKey(entity = SessionTypeEntity::class, parentColumns = ["id"], childColumns = ["suggested_session_type_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["suggested_session_type_id"])],
)
data class RoutineEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "suggested_session_type_id") val suggestedSessionTypeId: String?,
    val description: String?,
)

@Entity(
    tableName = "routine_exercises",
    primaryKeys = ["routine_id", "exercise_id"],
    foreignKeys = [
        ForeignKey(entity = RoutineEntity::class, parentColumns = ["id"], childColumns = ["routine_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exercise_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["routine_id", "position"], unique = true), Index(value = ["exercise_id"])],
)
data class RoutineExerciseEntity(
    @ColumnInfo(name = "routine_id") val routineId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val position: Int,
)

@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(entity = SessionTypeEntity::class, parentColumns = ["id"], childColumns = ["session_type_id"], onDelete = ForeignKey.NO_ACTION)],
    indices = [
        Index(value = ["session_date_epoch_day", "order_in_day"], unique = true),
        Index(value = ["session_date_epoch_day", "operational_state"]),
        Index(value = ["session_date_epoch_day", "execution_result"]),
        Index(value = ["session_type_id"]),
    ],
)
data class SessionEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "session_date_epoch_day") val sessionDateEpochDay: Long,
    @ColumnInfo(name = "order_in_day") val orderInDay: Int,
    @ColumnInfo(name = "session_type_id") val sessionTypeId: String,
    @ColumnInfo(name = "session_type_name_snapshot") val sessionTypeNameSnapshot: String,
    val name: String,
    @ColumnInfo(name = "is_auto_name") val isAutoName: Boolean,
    @ColumnInfo(name = "general_note") val generalNote: String?,
    @ColumnInfo(name = "operational_state") val operationalState: SessionOperationalState,
    @ColumnInfo(name = "execution_result") val executionResult: SessionExecutionResult,
)

@Entity(
    tableName = "session_exercises",
    foreignKeys = [
        ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exercise_id"], onDelete = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["session_id", "exercise_id"], unique = true),
        Index(value = ["session_id", "position"], unique = true),
        Index(value = ["exercise_id", "session_id"]),
    ],
)
data class SessionExerciseEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val position: Int,
    @ColumnInfo(name = "exercise_name_es_snapshot") val exerciseNameEsSnapshot: String,
    @ColumnInfo(name = "exercise_name_en_snapshot") val exerciseNameEnSnapshot: String,
    @ColumnInfo(name = "muscle_group_name_snapshot") val muscleGroupNameSnapshot: String,
    @ColumnInfo(name = "equipment_name_snapshot") val equipmentNameSnapshot: String?,
    @ColumnInfo(name = "default_load_mode_snapshot") val defaultLoadModeSnapshot: LoadMode,
    @ColumnInfo(name = "default_measurement_unit_snapshot") val defaultMeasurementUnitSnapshot: MeasurementUnit,
    @ColumnInfo(name = "rir_required_snapshot") val rirRequiredSnapshot: Boolean,
    @ColumnInfo(name = "description_snapshot") val descriptionSnapshot: String?,
    @ColumnInfo(name = "exercise_rest_seconds") val exerciseRestSeconds: Int?,
    val note: String?,
    @ColumnInfo(name = "incomplete_reason") val incompleteReason: String?,
    @ColumnInfo(name = "is_finalized") val isFinalized: Boolean = false,
)

@Entity(
    tableName = "session_sets",
    foreignKeys = [ForeignKey(entity = SessionExerciseEntity::class, parentColumns = ["id"], childColumns = ["session_exercise_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["session_exercise_id", "position"], unique = true)],
)
data class SessionSetEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "session_exercise_id") val sessionExerciseId: String,
    val position: Int,
    @ColumnInfo(name = "load_mode") val loadMode: LoadMode,
    @ColumnInfo(name = "measurement_unit") val measurementUnit: MeasurementUnit,
    @ColumnInfo(name = "target_load") val targetLoad: Double?,
    @ColumnInfo(name = "actual_load") val actualLoad: Double?,
    @ColumnInfo(name = "target_measurement") val targetMeasurement: Int?,
    @ColumnInfo(name = "actual_measurement") val actualMeasurement: Int?,
    val rir: Int?,
    @ColumnInfo(name = "rest_override_seconds") val restOverrideSeconds: Int?,
    @ColumnInfo(name = "actual_confirmed") val actualConfirmed: Boolean = false,
)
