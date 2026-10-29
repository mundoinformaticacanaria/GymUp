package com.mundoinformaticacanaria.gymup.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.mundoinformaticacanaria.gymup.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 15/16 instrumentation for #56: real Activity/Compose lifecycles, not a JVM model.
 *
 * Uses an intentionally invalid archive. BackupManager validates before replaceAll, so this
 * test does not replace any Room data, preferences or user images. It runs only in the
 * instrumentation application's sandbox and never launches a candidate APK workflow.
 *
 * A stopped Activity must retain its reader lease until its composition is actually disposed.
 * An acknowledgement from another Activity must not allow import to pass that lease.
 */
class HotBackupImportActivityDeviceTest {
    @Test
    fun stoppedSecondActivityKeepsImportClosedUntilItsCompositionDisposes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val first = ActivityScenario.launch(MainActivity::class.java)
        var second: ActivityScenario<MainActivity>? = null
        try {
            lateinit var container: AppContainer
            first.onActivity { activity ->
                container = (activity.application as GymUpApplication).container
            }
            runBlocking { withTimeout(20_000) { container.awaitReady() } }
            instrumentation.waitForIdleSync()

            second = ActivityScenario.launch(MainActivity::class.java)
            second.onActivity { activity ->
                assertTrue(
                    (activity.application as GymUpApplication).container === container,
                )
            }
            instrumentation.waitForIdleSync()

            // The second tree remains composed, but lifecycle collection is stopped.
            // It must still count as a reader when the first Activity is disposed.
            second.moveToState(Lifecycle.State.CREATED)
            val coordinator = requireNotNull(container.hotBackupImportCoordinator)
            assertTrue(coordinator.start(byteArrayOf(1, 2, 3)))
            assertEquals(
                HotBackupImportCoordinator.State.ClosingReaders,
                coordinator.state.value,
            )

            first.close()
            // Even an early/duplicate acknowledgement must not bypass the stopped reader.
            coordinator.readersDisposed()
            assertEquals(
                HotBackupImportCoordinator.State.ClosingReaders,
                coordinator.state.value,
            )

            // Resuming the second Activity applies the ClosingReaders composition,
            // releases its lease, and permits validation of the invalid archive.
            second.moveToState(Lifecycle.State.RESUMED)
            val completed = runBlocking {
                withTimeout(20_000) {
                    coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
                }
            } as HotBackupImportCoordinator.State.Completed
            assertTrue(
                "An invalid archive must never replace domain data",
                completed.outcome !is HotBackupImportCoordinator.Outcome.Result ||
                    (completed.outcome as HotBackupImportCoordinator.Outcome.Result).value
                        !is com.mundoinformaticacanaria.gymup.data.backup.BackupImportResult.Imported,
            )
        } finally {
            second?.close()
            first.close()
        }
    }
}
