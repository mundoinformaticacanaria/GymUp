package com.mundoinformaticacanaria.gymup.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.core.ui.GymUpTheme
import com.mundoinformaticacanaria.gymup.feature.home.HomeScreen
import com.mundoinformaticacanaria.gymup.feature.placeholder.PlaceholderScreen
import com.mundoinformaticacanaria.gymup.feature.routines.RoutinesScreen
import com.mundoinformaticacanaria.gymup.feature.sessions.NewSessionScreen
import com.mundoinformaticacanaria.gymup.feature.sessions.SessionDetailScreen
import com.mundoinformaticacanaria.gymup.feature.sessions.SessionsScreen
import com.mundoinformaticacanaria.gymup.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun GymUpApp(container: AppContainer) {
    val userPreferencesRepository = container.userPreferencesRepository
    val themeMode by userPreferencesRepository.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    GymUpTheme(themeMode = themeMode) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    onNewSession = { navController.navigate(Routes.NEW_SESSION) },
                    onSessions = { navController.navigate(Routes.SESSIONS) },
                    onRoutines = { navController.navigate(Routes.ROUTINES) },
                    onExercises = { navController.navigate(Routes.EXERCISES) },
                    onHistory = { navController.navigate(Routes.HISTORY) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.NEW_SESSION) {
                NewSessionScreen(
                    trainingRepository = container.trainingRepository,
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
                    trainingRepository = container.trainingRepository,
                    onNewSession = { navController.navigate(Routes.NEW_SESSION) },
                    onOpenSession = { navController.navigate(Routes.sessionDetail(it)) },
                    onBack = navController::popBackStack,
                )
            }
            composable("${Routes.SESSION_DETAIL}/{sessionId}") { entry ->
                val sessionId = requireNotNull(entry.arguments?.getString("sessionId"))
                SessionDetailScreen(
                    sessionId = sessionId,
                    trainingRepository = container.trainingRepository,
                    masterCatalogRepository = container.masterCatalogRepository,
                    exerciseCatalogRepository = container.exerciseCatalogRepository,
                    onBack = navController::popBackStack,
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable(Routes.ROUTINES) {
                RoutinesScreen(
                    trainingRepository = container.trainingRepository,
                    masterCatalogRepository = container.masterCatalogRepository,
                    exerciseCatalogRepository = container.exerciseCatalogRepository,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.EXERCISES) {
                PlaceholderScreen(title = "Ejercicios", onBack = navController::popBackStack)
            }
            composable(Routes.HISTORY) {
                PlaceholderScreen(title = "Histórico", onBack = navController::popBackStack)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    currentMode = themeMode,
                    onThemeModeSelected = { mode -> scope.launch { userPreferencesRepository.setThemeMode(mode) } },
                    onBack = navController::popBackStack,
                )
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val NEW_SESSION = "session/new"
    const val SESSION_DETAIL = "session/detail"
    const val SESSIONS = "sessions"
    const val ROUTINES = "routines"
    const val EXERCISES = "exercises"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    fun sessionDetail(id: String): String = "$SESSION_DETAIL/$id"
}
