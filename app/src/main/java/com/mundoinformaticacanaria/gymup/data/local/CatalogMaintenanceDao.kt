package com.mundoinformaticacanaria.gymup.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogMaintenanceDao {
    @Query("SELECT * FROM session_types WHERE is_active = 1 ORDER BY normalized_name")
    fun observeSessionTypes(): Flow<List<SessionTypeEntity>>

    @Query("SELECT * FROM muscle_groups WHERE is_active = 1 ORDER BY normalized_name")
    fun observeMuscleGroups(): Flow<List<MuscleGroupEntity>>

    @Query("SELECT * FROM equipment WHERE is_active = 1 ORDER BY normalized_name")
    fun observeEquipment(): Flow<List<EquipmentEntity>>

    @Query("SELECT * FROM session_types WHERE id = :id LIMIT 1")
    suspend fun getSessionType(id: String): SessionTypeEntity?

    @Query("SELECT * FROM muscle_groups WHERE id = :id LIMIT 1")
    suspend fun getMuscleGroup(id: String): MuscleGroupEntity?

    @Query("SELECT * FROM equipment WHERE id = :id LIMIT 1")
    suspend fun getEquipment(id: String): EquipmentEntity?

    @Query("SELECT * FROM session_types WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun findSessionType(normalizedName: String): SessionTypeEntity?

    @Query("SELECT * FROM muscle_groups WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun findMuscleGroup(normalizedName: String): MuscleGroupEntity?

    @Query("SELECT * FROM equipment WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun findEquipment(normalizedName: String): EquipmentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessionType(item: SessionTypeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMuscleGroup(item: MuscleGroupEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEquipment(item: EquipmentEntity)

    @Update suspend fun updateSessionType(item: SessionTypeEntity)
    @Update suspend fun updateMuscleGroup(item: MuscleGroupEntity)
    @Update suspend fun updateEquipment(item: EquipmentEntity)

    @Query("SELECT * FROM exercises WHERE id = :id LIMIT 1")
    suspend fun getExercise(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE normalized_name_es = :normalizedName LIMIT 1")
    suspend fun findExerciseByNameEs(normalizedName: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE normalized_name_en = :normalizedName LIMIT 1")
    suspend fun findExerciseByNameEn(normalizedName: String): ExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExercise(item: ExerciseEntity)

    @Update suspend fun updateExercise(item: ExerciseEntity)

    @Query("UPDATE exercises SET is_active = 0 WHERE id = :id")
    suspend fun deactivateExercise(id: String)

    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteExercise(id: String)

    @Query("SELECT * FROM exercise_images WHERE exercise_id = :exerciseId ORDER BY position")
    suspend fun getExerciseImages(exerciseId: String): List<ExerciseImageEntity>

    @Query("SELECT COUNT(*) FROM session_exercises WHERE exercise_id = :exerciseId")
    suspend fun countHistoricalReferences(exerciseId: String): Int

    @Query("SELECT COUNT(*) FROM routine_exercises WHERE exercise_id = :exerciseId")
    suspend fun countRoutineReferences(exerciseId: String): Int

    @Query("DELETE FROM routine_exercises WHERE exercise_id = :exerciseId")
    suspend fun removeRoutineReferences(exerciseId: String)
}
