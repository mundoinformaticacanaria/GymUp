package com.mundoinformaticacanaria.gymup.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedCatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedRoutineRepository
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedSessionRepository
import com.mundoinformaticacanaria.gymup.data.backup.BackupImportResult
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the first production composition step of #56.
 *
 * These assertions prevent accidental exposure of raw interface-based Room writers.
 * Production also installs the shared barrier in image mutations, preferences and backup,
 * and gates startup before UI/seeding. These tests do NOT prove process-death durability,
 * cross-process isolation, or that every possible direct DAO access is guarded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppContainerWriteBarrierWiringTest {
    @Test
    fun exposedRepositoryMutationsUseBarrierAdapters() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DefaultAppContainer(context)

        assertTrue(container.sessionRepository is BarrierProtectedSessionRepository)
        assertTrue(container.routineRepository is BarrierProtectedRoutineRepository)
        assertTrue(container.exerciseCatalogRepository is BarrierProtectedExerciseCatalogRepository)
        assertTrue(container.catalogMaintenanceRepository is BarrierProtectedCatalogMaintenanceRepository)
    }

    @Test
    fun productionBackupRestoresPreferencesThroughSharedBarrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DefaultAppContainer(context)
        container.awaitReady()
        try {
            container.userPreferencesRepository.setThemeMode(ThemeMode.DARK)
            val archive = container.backupManager.export()
            container.userPreferencesRepository.setThemeMode(ThemeMode.LIGHT)

            assertTrue(container.backupManager.importReplaceAll(archive) is BackupImportResult.Imported)
            assertTrue(container.userPreferencesRepository.currentThemeMode() == ThemeMode.DARK)
        } finally {
            container.userPreferencesRepository.setThemeMode(ThemeMode.SYSTEM)
        }
    }

    @Test
    fun journaledBackupIsAvailableOnlyAfterStartupGateCompletes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DefaultAppContainer(context)

        container.awaitReady()
        val archive = container.backupManager.export()
        assertTrue(archive.isNotEmpty())
        assertTrue(container.backupManager.importReplaceAll(archive) is BackupImportResult.Imported)
        // Recovery/seeding is idempotent after the first successful startup.
        container.awaitReady()
    }
}
