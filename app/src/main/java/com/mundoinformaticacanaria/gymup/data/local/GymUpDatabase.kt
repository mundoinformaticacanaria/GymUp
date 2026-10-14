package com.mundoinformaticacanaria.gymup.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        AppMetadataEntity::class,
        SessionTypeEntity::class,
        MuscleGroupEntity::class,
        EquipmentEntity::class,
        ExerciseEntity::class,
        ExerciseImageEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        SessionEntity::class,
        SessionExerciseEntity::class,
        SessionSetEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class GymUpDatabase : RoomDatabase() {
    abstract fun metadataDao(): MetadataDao
    abstract fun masterDataDao(): MasterDataDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun routineDao(): RoutineDao
    abstract fun trainingDao(): TrainingDao
    abstract fun backupDao(): BackupDao
    abstract fun catalogMaintenanceDao(): CatalogMaintenanceDao

    companion object {
        const val DATABASE_NAME = "gymup.db"

        fun create(context: Context): GymUpDatabase =
            Room.databaseBuilder(context.applicationContext, GymUpDatabase::class.java, DATABASE_NAME).build()
    }
}
