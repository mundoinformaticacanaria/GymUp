package com.mundoinformaticacanaria.gymup.app

import android.content.Context
import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupManager
import com.mundoinformaticacanaria.gymup.data.cleanup.RoomHistoricalCleanupStore
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.preferences.UserPreferencesRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomHistoryRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomMasterCatalogRepository
import com.mundoinformaticacanaria.gymup.data.repository.RoomTrainingRepository
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.HistoryRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface AppContainer {
    val userPreferencesRepository: UserPreferencesRepository
    val masterCatalogRepository: MasterCatalogRepository
    val exerciseCatalogRepository: ExerciseCatalogRepository
    val trainingRepository: TrainingRepository
    val historyRepository: HistoryRepository
    val historicalCleanupManager: HistoricalCleanupManager
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = GymUpDatabase.create(appContext)
    override val userPreferencesRepository = UserPreferencesRepository(appContext)
    override val masterCatalogRepository: MasterCatalogRepository = RoomMasterCatalogRepository(database.masterDataDao())
    override val exerciseCatalogRepository: ExerciseCatalogRepository = RoomExerciseCatalogRepository(database.exerciseDao())
    override val trainingRepository: TrainingRepository = RoomTrainingRepository(database)
    override val historyRepository: HistoryRepository = RoomHistoryRepository(database)
    override val historicalCleanupManager = HistoricalCleanupManager(RoomHistoricalCleanupStore(database))

    init {
        applicationScope.launch { DatabaseSeeder(appContext, database).seedIfNeeded() }
    }
}
