package com.mundoinformaticacanaria.gymup.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BackupDao {
    @Query("SELECT * FROM session_types ORDER BY id") suspend fun getSessionTypes(): List<SessionTypeEntity>
    @Query("SELECT * FROM muscle_groups ORDER BY id") suspend fun getMuscleGroups(): List<MuscleGroupEntity>
    @Query("SELECT * FROM equipment ORDER BY id") suspend fun getEquipment(): List<EquipmentEntity>
    @Query("SELECT * FROM exercises ORDER BY id") suspend fun getExercises(): List<ExerciseEntity>
    @Query("SELECT * FROM exercise_images ORDER BY exercise_id, position") suspend fun getExerciseImages(): List<ExerciseImageEntity>
    @Query("SELECT * FROM routines ORDER BY id") suspend fun getRoutines(): List<RoutineEntity>
    @Query("SELECT * FROM routine_exercises ORDER BY routine_id, position") suspend fun getRoutineExercises(): List<RoutineExerciseEntity>
    @Query("SELECT * FROM sessions WHERE order_in_day > 0 ORDER BY session_date_epoch_day, order_in_day") suspend fun getSessions(): List<SessionEntity>
    @Query("SELECT * FROM session_exercises ORDER BY session_id, position") suspend fun getSessionExercises(): List<SessionExerciseEntity>
    @Query("SELECT * FROM session_sets ORDER BY session_exercise_id, position") suspend fun getSessionSets(): List<SessionSetEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSessionTypes(items: List<SessionTypeEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMuscleGroups(items: List<MuscleGroupEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEquipment(items: List<EquipmentEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertExercises(items: List<ExerciseEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertExerciseImages(items: List<ExerciseImageEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRoutines(items: List<RoutineEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRoutineExercises(items: List<RoutineExerciseEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSessions(items: List<SessionEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSessionExercises(items: List<SessionExerciseEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSessionSets(items: List<SessionSetEntity>)

    @Query("DELETE FROM session_sets") suspend fun deleteSessionSets()
    @Query("DELETE FROM session_exercises") suspend fun deleteSessionExercises()
    @Query("DELETE FROM sessions") suspend fun deleteSessions()
    @Query("DELETE FROM routine_exercises") suspend fun deleteRoutineExercises()
    @Query("DELETE FROM routines") suspend fun deleteRoutines()
    @Query("DELETE FROM exercise_images") suspend fun deleteExerciseImages()
    @Query("DELETE FROM exercises") suspend fun deleteExercises()
    @Query("DELETE FROM equipment") suspend fun deleteEquipment()
    @Query("DELETE FROM muscle_groups") suspend fun deleteMuscleGroups()
    @Query("DELETE FROM session_types") suspend fun deleteSessionTypes()
}
