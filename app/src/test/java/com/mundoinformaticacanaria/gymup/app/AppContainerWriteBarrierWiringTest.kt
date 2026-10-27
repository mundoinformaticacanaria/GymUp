package com.mundoinformaticacanaria.gymup.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedCatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedRoutineRepository
import com.mundoinformaticacanaria.gymup.data.backup.BarrierProtectedSessionRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression for the first production composition step of #56.
 *
 * These assertions prevent accidental exposure of raw interface-based Room writers.
 * They do NOT certify global isolation: image mutations, theme preferences and startup/UI
 * are still unguarded, and the journaled backup is deliberately not installed.
 */
@RunWith(RobolectricTestRunner::class)
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
}
