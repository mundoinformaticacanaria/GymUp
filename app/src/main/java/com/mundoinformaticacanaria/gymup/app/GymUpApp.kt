package com.mundoinformaticacanaria.gymup.app

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.data.backup.BackupImportResult
import com.mundoinformaticacanaria.gymup.core.ui.GymUpTheme
import com.mundoinformaticacanaria.gymup.feature.exercises.ExerciseEditorScreen
import com.mundoinformaticacanaria.gymup.feature.exercises.ExerciseHistoryScreen
import com.mundoinformaticacanaria.gymup.feature.exercises.ExercisesScreen
import com.mundoinformaticacanaria.gymup.feature.history.HistoryScreen
import com.mundoinformaticacanaria.gymup.feature.home.HomeScreen
import com.mundoinformaticacanaria.gymup.feature.routines.RoutineEditorScreen
import com.mundoinformaticacanaria.gymup.feature.routines.RoutinesScreen
import com.mundoinformaticacanaria.gymup.feature.sessions.NewSessionScreen
import com.mundoinformaticacanaria.gymup.feature.sessions.SessionDetailScreen
import com.mundoinformaticacanaria.gymup.feature.sessions.SessionsScreen
import com.mundoinformaticacanaria.gymup.feature.settings.CatalogMaintenanceScreen
import com.mundoinformaticacanaria.gymup.feature.settings.SettingsScreen

private sealed interface StartupState {
    data object Loading : StartupState
    data object Ready : StartupState
    data class Failed(val message: String) : StartupState
}

/**
 * Never compose a repository consumer until durable recovery and seeding have succeeded.
 * A corrupt PREPARED journal leaves the UI closed; retry does not bypass recovery.
 */
@Composable
fun GymUpApp(container: AppContainer) {
    var attempt by remember(container) { mutableIntStateOf(0) }
    var state by remember(container) { mutableStateOf<StartupState>(StartupState.Loading) }
    val hotImportCoordinator = container.hotBackupImportCoordinator
    val hotImportState = hotImportCoordinator?.state?.collectAsStateWithLifecycle()?.value
        ?: HotBackupImportCoordinator.State.Idle
    var acknowledgedGeneration by rememberSaveable { mutableStateOf(0L) }

    LaunchedEffect(container, attempt) {
        state = StartupState.Loading
        state = try {
            container.awaitReady()
            StartupState.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            StartupState.Failed(error.message ?: "No se pudo recuperar el almacenamiento local")
        }
    }

    when (val current = state) {
        StartupState.Ready -> when (val hot = hotImportState) {
            HotBackupImportCoordinator.State.Idle -> key(0L) {
                GymUpReadyApp(container, generation = 0L)
            }
            HotBackupImportCoordinator.State.ClosingReaders -> {
                // SideEffect runs after Compose applies disposal of the previous NavHost.
                // Never acknowledge from the button or while repository readers remain mounted.
                SideEffect { hotImportCoordinator?.readersDisposed() }
                HotBackupGateScreen("Cerrando las vistas antes de importar…")
            }
            HotBackupImportCoordinator.State.Importing ->
                HotBackupGateScreen("Importando y protegiendo los datos…")
            HotBackupImportCoordinator.State.Recovering ->
                HotBackupGateScreen("Recuperando los datos anteriores…")
            is HotBackupImportCoordinator.State.Blocked ->
                HotBackupGateScreen(
                    "No se pueden mostrar los datos hasta completar la recuperación. ${hot.message}",
                    onRetry = { hotImportCoordinator?.retryRecovery() },
                )
            is HotBackupImportCoordinator.State.Completed -> {
                // A fresh composition/nav graph must not reuse stale Room flows or ViewModels.
                key(hot.generation) { GymUpReadyApp(container, generation = hot.generation) }
                if (acknowledgedGeneration != hot.generation) {
                    val message = when (val outcome = hot.outcome) {
                        is HotBackupImportCoordinator.Outcome.Failed -> outcome.message
                        is HotBackupImportCoordinator.Outcome.Result -> when (val result = outcome.value) {
                            is BackupImportResult.Imported ->
                                "Backup importado correctamente (${result.imageCount} imagen(es))."
                            is BackupImportResult.Rejected -> result.reason
                        }
                    }
                    GymUpTheme(themeMode = ThemeMode.SYSTEM) {
                        AlertDialog(
                            onDismissRequest = { acknowledgedGeneration = hot.generation },
                            title = { Text("Resultado de la importación") },
                            text = { Text(message) },
                            confirmButton = {
                                Button(onClick = { acknowledgedGeneration = hot.generation }) {
                                    Text("Aceptar")
                                }
                            },
                        )
                    }
                }
            }
        }
        StartupState.Loading -> GymUpTheme(themeMode = ThemeMode.SYSTEM) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Preparando los datos de GymUp…")
            }
        }
        is StartupState.Failed -> GymUpTheme(themeMode = ThemeMode.SYSTEM) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No se pueden mostrar los datos hasta completar la recuperación.")
                Spacer(Modifier.height(12.dp))
                Text(current.message)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { attempt++ }) { Text("Reintentar recuperación") }
            }
        }
    }
}

@Composable
private fun HotBackupGateScreen(message: String, onRetry: (() -> Unit)? = null) {
    GymUpTheme(themeMode = ThemeMode.SYSTEM) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (onRetry == null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
            }
            Text(message)
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("Reintentar recuperación") }
            }
        }
    }
}

@Composable
private fun GymUpReadyApp(container: AppContainer, generation: Long) {
    val appViewModel: AppViewModel = viewModel(
        key = "gymup-app-$generation",
        factory = AppViewModel.Factory(container.userPreferencesRepository),
    )
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    GymUpTheme(themeMode = themeMode) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.navigationBarsPadding(),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    sessionRepository = container.sessionRepository,
                    onNewSession = { navController.navigate(Routes.NEW_SESSION) },
                    onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                    onSessions = { navController.navigate(Routes.SESSIONS) },
                    onRoutines = { navController.navigate(Routes.ROUTINES) },
                    onExercises = { navController.navigate(Routes.EXERCISES) },
                    onHistory = { navController.navigate(Routes.HISTORY) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.NEW_SESSION) {
                NewSessionScreen(
                    sessionRepository = container.sessionRepository,
                    routineRepository = container.routineRepository,
                    masterCatalogRepository = container.masterCatalogRepository,
                    exerciseCatalogRepository = container.exerciseCatalogRepository,
                    onCreated = { sessionId ->
                        navController.navigate(Routes.sessionDetail(sessionId)) {
                            popUpTo(Routes.NEW_SESSION) { inclusive = true }
                        }
                    },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.SESSIONS) {
                SessionsScreen(
                    sessionRepository = container.sessionRepository,
                    onNewSession = { navController.navigate(Routes.NEW_SESSION) },
                    onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                    onBack = navController::popBackStack,
                )
            }
            composable("${Routes.SESSION_DETAIL}/{sessionId}") { entry ->
                val sessionId = requireNotNull(entry.arguments?.getString("sessionId"))
                SessionDetailScreen(
                    sessionId = sessionId,
                    sessionRepository = container.sessionRepository,
                    masterCatalogRepository = container.masterCatalogRepository,
                    exerciseCatalogRepository = container.exerciseCatalogRepository,
                    exerciseImageManager = container.exerciseImageManager,
                    onBack = navController::popBackStack,
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable(Routes.ROUTINES) {
                RoutinesScreen(
                    routineRepository = container.routineRepository,
                    onNewRoutine = { navController.navigate(Routes.NEW_ROUTINE) },
                    onOpenRoutine = { navController.navigate(Routes.routineEditor(it)) },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.NEW_ROUTINE) {
                RoutineEditorScreen(
                    routineId = null,
                    routineRepository = container.routineRepository,
                    masterCatalogRepository = container.masterCatalogRepository,
                    exerciseCatalogRepository = container.exerciseCatalogRepository,
                    saveRoutineUseCase = container.saveRoutineUseCase,
                    filterRoutineExercisesUseCase = container.filterRoutineExercisesUseCase,
                    onFinished = navController::popBackStack,
                    onBack = navController::popBackStack,
                )
            }
            composable("${Routes.ROUTINE_DETAIL}/{routineId}") { entry ->
                val routineId = requireNotNull(entry.arguments?.getString("routineId"))
                RoutineEditorScreen(
                    routineId = routineId,
                    routineRepository = container.routineRepository,
                    masterCatalogRepository = container.masterCatalogRepository,
                    exerciseCatalogRepository = container.exerciseCatalogRepository,
                    saveRoutineUseCase = container.saveRoutineUseCase,
                    filterRoutineExercisesUseCase = container.filterRoutineExercisesUseCase,
                    onFinished = navController::popBackStack,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.EXERCISES) {
                ExercisesScreen(
                    exerciseCatalogRepository = container.exerciseCatalogRepository,
                    masterCatalogRepository = container.masterCatalogRepository,
                    historyRepository = container.historyRepository,
                    exerciseImageManager = container.exerciseImageManager,
                    onNewExercise = { navController.navigate(Routes.exerciseEditor(null)) },
                    onEditExercise = { navController.navigate(Routes.exerciseEditor(it)) },
                    onOpenHistory = { navController.navigate(Routes.exerciseHistory(it)) },
                    onBack = navController::popBackStack,
                )
            }
            composable("${Routes.EXERCISE_EDITOR}/{exerciseId}") { entry ->
                val rawId = requireNotNull(entry.arguments?.getString("exerciseId"))
                ExerciseEditorScreen(
                    exerciseId = rawId.takeUnless { it == Routes.NEW_EXERCISE_ID },
                    maintenanceRepository = container.catalogMaintenanceRepository,
                    masterCatalogRepository = container.masterCatalogRepository,
                    onSaved = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                    onBack = navController::popBackStack,
                )
            }
            composable("${Routes.EXERCISE_HISTORY}/{exerciseId}") { entry ->
                val exerciseId = requireNotNull(entry.arguments?.getString("exerciseId"))
                ExerciseHistoryScreen(
                    exerciseId = exerciseId,
                    historyRepository = container.historyRepository,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    sessionRepository = container.sessionRepository,
                    masterCatalogRepository = container.masterCatalogRepository,
                    historyRepository = container.historyRepository,
                    onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    currentMode = themeMode,
                    backupManager = container.backupManager,
                    onImportConfirmed = { archive -> hotImportCoordinatorStart(container, archive) },
                    historicalCleanupManager = container.historicalCleanupManager,
                    onThemeModeSelected = appViewModel::selectThemeMode,
                    onCatalogMaintenance = { navController.navigate(Routes.CATALOGS) },
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.CATALOGS) {
                CatalogMaintenanceScreen(
                    repository = container.catalogMaintenanceRepository,
                    onBack = navController::popBackStack,
                )
            }
        }
    }
}

private fun hotImportCoordinatorStart(container: AppContainer, archive: ByteArray): Boolean =
    container.hotBackupImportCoordinator?.start(archive) ?: false

private object Routes {
    const val HOME = "home"
    const val NEW_SESSION = "session/new"
    const val SESSION_DETAIL = "session/detail"
    const val SESSIONS = "sessions"
    const val ROUTINES = "routines"
    const val NEW_ROUTINE = "routine/new"
    const val ROUTINE_DETAIL = "routine"
    const val EXERCISES = "exercises"
    const val EXERCISE_EDITOR = "exercise/edit"
    const val EXERCISE_HISTORY = "exercise/history"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CATALOGS = "settings/catalogs"
    const val NEW_EXERCISE_ID = "new"

    fun sessionDetail(id: String): String = "$SESSION_DETAIL/$id"
    fun routineEditor(id: String): String = "$ROUTINE_DETAIL/$id"
    fun exerciseEditor(id: String?): String = "$EXERCISE_EDITOR/${id ?: NEW_EXERCISE_ID}"
    fun exerciseHistory(id: String): String = "$EXERCISE_HISTORY/$id"
}
