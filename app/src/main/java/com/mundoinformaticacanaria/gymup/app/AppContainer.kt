package com.mundoinformaticacanaria.gymup.app

import android.content.Context
import com.mundoinformaticacanaria.gymup.BuildConfig
import com.mundoinformaticacanaria.gymup.data.backup.BackupManager
import com.mundoinformaticacanaria.gymup.data.backup.RoomBackupDataSource
import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupManager
import com.mundoinformaticacanaria.gymup.data.cleanup.RoomHistoricalCleanupStore
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageManager
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.preferences.UserPreferencesRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomCatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomHistoryRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomMasterCatalogRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomRoutineRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomSessionRepository
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.HistoryRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRepository
import com.mundoinformaticacanaria.gymup.domain.repository.SessionRepository
import com.mundoinformaticacanaria.gymup.domain.usecase.FilterRoutineExercisesUseCase
import com.mundoinformaticacanaria.gymup.domain.usecase.SaveRoutineUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface AppContainer {
    val userPreferencesRepository: UserPreferencesRepository
    val masterCatalogRepository: MasterCatalogRepository
    val exerciseCatalogRepository: ExerciseCatalogRepository
    val catalogMaintenanceRepository: CatalogMaintenanceRepository
    val exerciseImageManager: ExerciseImageManager
    val sessionRepository: SessionRepository
    val routineRepository: RoutineRepository
    val saveRoutineUseCase: SaveRoutineUseCase
    val filterRoutineExercisesUseCase: FilterRoutineExercisesUseCase
    val historyRepository: HistoryRepository
    val historicalCleanupManager: HistoricalCleanupManager
    val backupManager: BackupManager
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = GymUpDatabase.create(appContext)
    override val userPreferencesRepository = UserPreferencesRepository(appContext)
    override val masterCatalogRepository: MasterCatalogRepository = RoomMasterCatalogRepository(database.masterDataDao())
    override val exerciseCatalogRepository: ExerciseCatalogRepository = RoomExerciseCatalogRepository(database.exerciseDao())
    override val catalogMaintenanceRepository: CatalogMaintenanceRepository = RoomCatalogMaintenanceRepository(appContext, database)
    override val exerciseImageManager = ExerciseImageManager(appContext, database.exerciseDao())
    override val sessionRepository: SessionRepository = RoomSessionRepository(database)
    override val routineRepository: RoutineRepository = RoomRoutineRepository(database)
    override val saveRoutineUseCase = SaveRoutineUseCase(routineRepository)
    override val filterRoutineExercisesUseCase = FilterRoutineExercisesUseCase()
    override val historyRepository: HistoryRepository = RoomHistoryRepository(database)
    override val historicalCleanupManager = HistoricalCleanupManager(RoomHistoricalCleanupStore(database))
    override val backupManager = BackupManager(
        dataSource = RoomBackupDataSource(appContext, database, userPreferencesRepository),
        appVersion = BuildConfig.VERSION_NAME,
    )

    init {
        applicationScope.launch { DatabaseSeeder(appContext, database).seedIfNeeded() }
    }
}
