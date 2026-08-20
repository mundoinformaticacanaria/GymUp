package com.mundoinformaticacanaria.gymup.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataDao {
    @Query("SELECT value FROM app_metadata WHERE `key` = :key LIMIT 1") suspend fun getValue(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(entity: AppMetadataEntity)
}

@Dao
interface MasterDataDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSessionTypes(items: List<SessionTypeEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMuscleGroups(items: List<MuscleGroupEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEquipment(items: List<EquipmentEntity>)
    @Query("SELECT * FROM session_types WHERE is_active = 1 ORDER BY normalized_name") fun observeActiveSessionTypes(): Flow<List<SessionTypeEntity>>
    @Query("SELECT * FROM muscle_groups WHERE is_active = 1 ORDER BY normalized_name") fun observeActiveMuscleGroups(): Flow<List<MuscleGroupEntity>>
    @Query("SELECT * FROM equipment WHERE is_active = 1 ORDER BY normalized_name") fun observeActiveEquipment(): Flow<List<EquipmentEntity>>
    @Query("SELECT * FROM session_types ORDER BY normalized_name") suspend fun getSessionTypes(): List<SessionTypeEntity>
    @Query("SELECT * FROM muscle_groups ORDER BY normalized_name") suspend fun getMuscleGroups(): List<MuscleGroupEntity>
    @Query("SELECT * FROM equipment ORDER BY normalized_name") suspend fun getEquipment(): List<EquipmentEntity>
}

@Dao
interface ExerciseDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(items: List<ExerciseEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(item: ExerciseEntity)
    @Query("SELECT COUNT(*) FROM exercises") suspend fun count(): Int
    @Query("SELECT * FROM exercises WHERE is_active = 1 ORDER BY normalized_name_es") fun observeActive(): Flow<List<ExerciseEntity>>
    @Query("SELECT * FROM exercises ORDER BY normalized_name_es") suspend fun getAll(): List<ExerciseEntity>
    @Query("SELECT * FROM exercises WHERE id = :id LIMIT 1") suspend fun getById(id: String): ExerciseEntity?
    @Query("SELECT * FROM exercises WHERE normalized_name_es = :name OR normalized_name_en = :name LIMIT 1") suspend fun findByNormalizedName(name: String): ExerciseEntity?
    @Query("UPDATE exercises SET is_active = 0 WHERE id = :id") suspend fun deactivate(id: String)
    @Query("UPDATE exercises SET is_favorite = :favorite WHERE id = :id") suspend fun setFavorite(id: String, favorite: Boolean)
    @Query("DELETE FROM exercises WHERE id = :id") suspend fun deleteById(id: String)
}

@Dao
interface RoutineDao {
    @Query("SELECT COUNT(*) FROM routine_exercises WHERE exercise_id = :exerciseId") suspend fun countExerciseReferences(exerciseId: String): Int
    @Query("DELETE FROM routine_exercises WHERE exercise_id = :exerciseId") suspend fun removeExerciseReferences(exerciseId: String)
}
