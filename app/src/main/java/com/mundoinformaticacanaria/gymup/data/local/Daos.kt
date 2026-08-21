package com.mundoinformaticacanaria.gymup.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ExerciseUsageRow(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "session_date_epoch_day") val sessionDateEpochDay: Long,
    @ColumnInfo(name = "order_in_day") val orderInDay: Int,
)

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
    @Query("SELECT * FROM session_types WHERE id = :id LIMIT 1") suspend fun getSessionTypeById(id: String): SessionTypeEntity?
    @Query("SELECT * FROM muscle_groups WHERE id = :id LIMIT 1") suspend fun getMuscleGroupById(id: String): MuscleGroupEntity?
    @Query("SELECT * FROM equipment WHERE id = :id LIMIT 1") suspend fun getEquipmentById(id: String): EquipmentEntity?
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
    @Query("SELECT * FROM routines ORDER BY name COLLATE NOCASE") fun observeRoutines(): Flow<List<RoutineEntity>>
    @Query("SELECT * FROM routines WHERE id = :id LIMIT 1") suspend fun getById(id: String): RoutineEntity?
    @Query("SELECT * FROM routine_exercises WHERE routine_id = :routineId ORDER BY position") suspend fun getExercises(routineId: String): List<RoutineExerciseEntity>
    @Query("SELECT DISTINCT exercise_id FROM routine_exercises") fun observeRoutineExerciseIds(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRoutine(item: RoutineEntity)
    @Update suspend fun updateRoutine(item: RoutineEntity)
    @Delete suspend fun deleteRoutine(item: RoutineEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertExercise(item: RoutineExerciseEntity)
    @Update suspend fun updateExercise(item: RoutineExerciseEntity)
    @Delete suspend fun deleteExercise(item: RoutineExerciseEntity)
    @Query("SELECT COUNT(*) FROM routine_exercises WHERE exercise_id = :exerciseId") suspend fun countExerciseReferences(exerciseId: String): Int
    @Query("DELETE FROM routine_exercises WHERE exercise_id = :exerciseId") suspend fun removeExerciseReferences(exerciseId: String)
}

@Dao
interface TrainingDao {
    // order_in_day <= 0 is reserved for transient in-transaction parking while reordering.
    @Query("SELECT * FROM sessions WHERE order_in_day > 0 ORDER BY session_date_epoch_day DESC, order_in_day DESC") fun observeSessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE session_date_epoch_day = :epochDay AND order_in_day > 0 ORDER BY order_in_day") fun observeSessionsForDate(epochDay: Long): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1") suspend fun getSession(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE session_date_epoch_day = :epochDay AND order_in_day > 0 ORDER BY order_in_day") suspend fun getSessionsForDate(epochDay: Long): List<SessionEntity>
    @Query("SELECT COALESCE(MAX(order_in_day), 0) FROM sessions WHERE session_date_epoch_day = :epochDay AND order_in_day > 0") suspend fun maxOrderInDay(epochDay: Long): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSession(item: SessionEntity)
    @Update suspend fun updateSession(item: SessionEntity)
    @Delete suspend fun deleteSession(item: SessionEntity)

    @Query("SELECT * FROM session_exercises WHERE session_id = :sessionId ORDER BY position") suspend fun getSessionExercises(sessionId: String): List<SessionExerciseEntity>
    @Query("SELECT * FROM session_exercises WHERE id = :id LIMIT 1") suspend fun getSessionExercise(id: String): SessionExerciseEntity?
    @Query("SELECT * FROM session_exercises WHERE session_id = :sessionId AND exercise_id = :exerciseId LIMIT 1") suspend fun getSessionExerciseByExercise(sessionId: String, exerciseId: String): SessionExerciseEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSessionExercise(item: SessionExerciseEntity)
    @Update suspend fun updateSessionExercise(item: SessionExerciseEntity)
    @Delete suspend fun deleteSessionExercise(item: SessionExerciseEntity)

    @Query("SELECT * FROM session_sets WHERE session_exercise_id = :sessionExerciseId ORDER BY position") suspend fun getSets(sessionExerciseId: String): List<SessionSetEntity>
    @Query("SELECT * FROM session_sets WHERE id = :id LIMIT 1") suspend fun getSet(id: String): SessionSetEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSet(item: SessionSetEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSets(items: List<SessionSetEntity>)
    @Update suspend fun updateSet(item: SessionSetEntity)
    @Delete suspend fun deleteSet(item: SessionSetEntity)
    @Query("DELETE FROM session_sets WHERE session_exercise_id = :sessionExerciseId") suspend fun deleteSetsForExercise(sessionExerciseId: String)

    @Query(
        """
        SELECT se.* FROM session_exercises se
        INNER JOIN sessions s ON s.id = se.session_id
        WHERE se.exercise_id = :exerciseId
          AND s.order_in_day > 0
          AND (s.session_date_epoch_day < :epochDay
               OR (s.session_date_epoch_day = :epochDay AND s.order_in_day < :orderInDay))
        ORDER BY s.session_date_epoch_day DESC, s.order_in_day DESC
        """,
    )
    suspend fun getPriorExerciseExecutions(exerciseId: String, epochDay: Long, orderInDay: Int): List<SessionExerciseEntity>

    @Query(
        """
        SELECT se.exercise_id AS exercise_id,
               s.session_date_epoch_day AS session_date_epoch_day,
               s.order_in_day AS order_in_day
        FROM session_exercises se
        INNER JOIN sessions s ON s.id = se.session_id
        WHERE s.order_in_day > 0
          AND EXISTS (
              SELECT 1 FROM session_sets ss
              WHERE ss.session_exercise_id = se.id AND ss.actual_confirmed = 1
          )
        ORDER BY s.session_date_epoch_day DESC, s.order_in_day DESC
        """,
    )
    fun observeRealExerciseUsages(): Flow<List<ExerciseUsageRow>>

    @Query(
        """
        SELECT se.* FROM session_exercises se
        INNER JOIN sessions s ON s.id = se.session_id
        WHERE se.exercise_id = :exerciseId
          AND s.order_in_day > 0
          AND EXISTS (
              SELECT 1 FROM session_sets ss
              WHERE ss.session_exercise_id = se.id AND ss.actual_confirmed = 1
          )
        ORDER BY s.session_date_epoch_day DESC, s.order_in_day DESC
        LIMIT :limit
        """,
    )
    suspend fun getValidExerciseExecutions(exerciseId: String, limit: Int): List<SessionExerciseEntity>

    @Query("SELECT COUNT(*) FROM session_exercises WHERE exercise_id = :exerciseId") suspend fun countHistoricalExerciseReferences(exerciseId: String): Int

    @Query("SELECT COUNT(*) FROM sessions WHERE session_date_epoch_day < :cutoffEpochDay")
    suspend fun countSessionsBefore(cutoffEpochDay: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM session_exercises se
        INNER JOIN sessions s ON s.id = se.session_id
        WHERE s.session_date_epoch_day < :cutoffEpochDay
        """,
    )
    suspend fun countSessionExercisesBefore(cutoffEpochDay: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM session_sets ss
        INNER JOIN session_exercises se ON se.id = ss.session_exercise_id
        INNER JOIN sessions s ON s.id = se.session_id
        WHERE s.session_date_epoch_day < :cutoffEpochDay
        """,
    )
    suspend fun countSessionSetsBefore(cutoffEpochDay: Long): Int

    @Query("DELETE FROM sessions WHERE session_date_epoch_day < :cutoffEpochDay")
    suspend fun deleteSessionsBefore(cutoffEpochDay: Long): Int
}
