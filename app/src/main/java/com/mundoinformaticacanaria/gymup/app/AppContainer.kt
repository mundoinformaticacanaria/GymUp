package com.mundoinformaticacanaria.gymup.app

import android.content.Context
import com.mundoinformaticacanaria.gymup.BuildConfig
import com.mundoinformaticacanaria.gymup.data.backup.BackupManager
import com.mundoinformaticacanaria.gymup.data.backup.BackupRecoveryCoordinator
import com.mundoinformaticacanaria.gymup.data.backup.BackupRecoveryJournal
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedJournaledBackupDataSource
import com.mundoinformaticacanaria.gymup.data.backup.JournaledBackupDataSource
import com.mundoinformaticacanaria.gymup.data.backup.BackupDomainWriteBarrier
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedCatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedHistoricalCleanupStore
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedRoutineRepository
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedSessionRepository
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
import java.io.File

interface AppContainer {
    /** Must succeed before UI, seeding or other domain access is exposed. */
    suspend fun awaitReady()
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
    private val database = GymUpDatabase.create(appContext)
    // All exposed domain writers, import/export and startup share this process-local barrier.
    // This is not a cross-process or power-loss durability guarantee.
    private val domainWriteBarrier = BackupDomainWriteBarrier()
    // The backup delegate must stay unwrapped: journal recovery holds the barrier externally.
    private val backupPreferencesRepository = UserPreferencesRepository(appContext)
    override val userPreferencesRepository = UserPreferencesRepository(
        appContext,
        domainWriteBarrier = domainWriteBarrier,
    )
    override val masterCatalogRepository: MasterCatalogRepository = RoomMasterCatalogRepository(database.masterDataDao())
    override val exerciseCatalogRepository: ExerciseCatalogRepository =
        BarrierProtectedExerciseCatalogRepository(RoomExerciseCatalogRepository(database.exerciseDao()), domainWriteBarrier)
    override val catalogMaintenanceRepository: CatalogMaintenanceRepository =
        BarrierProtectedCatalogMaintenanceRepository(RoomCatalogMaintenanceRepository(appContext, database), domainWriteBarrier)
    override val exerciseImageManager = ExerciseImageManager(
        appContext,
        database.exerciseDao(),
        domainWriteBarrier = domainWriteBarrier,
    )
    override val sessionRepository: SessionRepository =
        BarrierProtectedSessionRepository(RoomSessionRepository(database), domainWriteBarrier)
    override val routineRepository: RoutineRepository =
        BarrierProtectedRoutineRepository(RoomRoutineRepository(database), domainWriteBarrier)
    override val saveRoutineUseCase = SaveRoutineUseCase(routineRepository)
    override val filterRoutineExercisesUseCase = FilterRoutineExercisesUseCase()
    override val historyRepository: HistoryRepository = RoomHistoryRepository(database)
    override val historicalCleanupManager = HistoricalCleanupManager(
        BarrierProtectedHistoricalCleanupStore(RoomHistoricalCleanupStore(database), domainWriteBarrier),
    )
    private val journaledBackupDataSource = JournaledBackupDataSource(
        delegate = RoomBackupDataSource(appContext, database, backupPreferencesRepository),
        coordinator = BackupRecoveryCoordinator(
            BackupRecoveryJournal(File(appContext.filesDir, "backup-recovery")),
        ),
        appVersion = BuildConfig.VERSION_NAME,
    )
    private val guardedBackupDataSource = BarrierProtectedJournaledBackupDataSource(
        journaledBackupDataSource,
        domainWriteBarrier,
    )
    private val startupGate = BackupStartupGate(
        // Both callbacks use RAW adapters because BackupStartupGate owns the exclusive barrier.
        recover = { journaledBackupDataSource.recoverBeforeDataAccess() },
        seed = { DatabaseSeeder(appContext, database).seedIfNeeded() },
        writeBarrier = domainWriteBarrier,
    )

    override suspend fun awaitReady() {
        startupGate.awaitReady()
    }

    override val backupManager = BackupManager(
        dataSource = guardedBackupDataSource,
        appVersion = BuildConfig.VERSION_NAME,
    )
}
