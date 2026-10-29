package com.mundoinformaticacanaria.gymup.app

import com.mundoinformaticacanaria.gymup.data.backup.BackupImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotBackupImportCoordinatorTest {
    @Test
    fun `import cannot touch domain before UI disposal acknowledgement`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = {
                    calls++
                    BackupImportResult.Imported(2)
                },
                recover = {},
            )
            assertTrue(coordinator.start(byteArrayOf(1)))
            assertEquals(HotBackupImportCoordinator.State.ClosingReaders, coordinator.state.value)
            assertEquals(0, calls)
            coordinator.readersDisposed()
            val done = withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            } as HotBackupImportCoordinator.State.Completed
            assertEquals(1, calls)
            assertEquals(1L, done.generation)
            assertEquals(
                HotBackupImportCoordinator.Outcome.Result(BackupImportResult.Imported(2)),
                done.outcome,
            )
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun `cancelling screen scope does not cancel application import`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = {
                    entered.complete(Unit)
                    release.await()
                    BackupImportResult.Imported(0)
                },
                recover = {},
            )
            val screen = launch {
                assertTrue(coordinator.start(byteArrayOf(1)))
                awaitCancellation()
            }
            coordinator.readersDisposed()
            entered.await()
            screen.cancelAndJoin()
            assertEquals(HotBackupImportCoordinator.State.Importing, coordinator.state.value)
            release.complete(Unit)
            withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            }
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun `failed import recovers before readers can reopen and rejects duplicates`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = { error("injected mutation failure") },
                recover = { release.await() },
            )
            assertTrue(coordinator.start(byteArrayOf(1)))
            assertFalse(coordinator.start(byteArrayOf(2)))
            coordinator.readersDisposed()
            assertEquals(HotBackupImportCoordinator.State.Recovering, coordinator.state.value)
            release.complete(Unit)
            val done = withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            } as HotBackupImportCoordinator.State.Completed
            assertEquals(
                HotBackupImportCoordinator.Outcome.Failed("injected mutation failure"),
                done.outcome,
            )
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun `failed recovery remains blocked until successful explicit retry`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var attempts = 0
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = { error("original import error") },
                recover = {
                    if (++attempts == 1) error("journal inaccessible")
                },
            )
            assertTrue(coordinator.start(byteArrayOf(1)))
            coordinator.readersDisposed()
            withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Blocked }
            }
            assertFalse(coordinator.start(byteArrayOf(2)))
            assertTrue(coordinator.retryRecovery())
            val done = withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            } as HotBackupImportCoordinator.State.Completed
            assertEquals(2, attempts)
            assertEquals(
                HotBackupImportCoordinator.Outcome.Failed("original import error"),
                done.outcome,
            )
        } finally {
            appScope.cancel()
        }
    }
}
