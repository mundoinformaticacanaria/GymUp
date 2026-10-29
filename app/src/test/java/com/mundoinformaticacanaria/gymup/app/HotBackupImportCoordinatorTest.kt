package com.mundoinformaticacanaria.gymup.app

import com.mundoinformaticacanaria.gymup.data.backup.BackupImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
    fun `two Activity readers must both dispose before hot import mutates domain`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var imports = 0
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = {
                    imports++
                    BackupImportResult.Imported(0)
                },
                recover = {},
            )
            val firstActivity = requireNotNull(coordinator.registerReader())
            val secondActivity = requireNotNull(coordinator.registerReader())
            assertTrue(coordinator.start(byteArrayOf(1)))
            // The first Activity's SideEffect must not release the second reader.
            firstActivity.close()
            coordinator.readersDisposed()
            assertEquals(HotBackupImportCoordinator.State.ClosingReaders, coordinator.state.value)
            assertEquals(0, imports)

            secondActivity.close()
            val done = withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            } as HotBackupImportCoordinator.State.Completed
            assertEquals(1, imports)
            assertEquals(1L, done.generation)
            // A duplicate close must not decrement the count of a new generation.
            secondActivity.close()
            val newActivity = requireNotNull(coordinator.registerReader())
            assertTrue(coordinator.start(byteArrayOf(2)))
            coordinator.readersDisposed()
            assertEquals(1, imports)
            newActivity.close()
            withTimeout(2000) {
                coordinator.state.first {
                    it is HotBackupImportCoordinator.State.Completed && it.generation == 2L
                }
            }
            assertEquals(2, imports)
        } finally {
            appScope.cancel()
        }
    }


    @Test
    fun `late Activity cannot acquire a reader lease after closing begins`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var imports = 0
            val releaseImport = CompletableDeferred<Unit>()
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = {
                    imports++
                    releaseImport.await()
                    BackupImportResult.Imported(0)
                },
                recover = {},
            )
            val existing = requireNotNull(coordinator.registerReader())
            assertTrue(coordinator.start(byteArrayOf(1)))
            // A stale Activity may attempt to compose before it receives the
            // ClosingReaders state. It must never create a new Room reader.
            assertNull(coordinator.registerReader())
            existing.close()
            coordinator.readersDisposed()
            assertEquals(HotBackupImportCoordinator.State.Importing, coordinator.state.value)
            assertEquals(1, imports)
            assertNull(coordinator.registerReader())
            releaseImport.complete(Unit)
            withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            }
            val nextGeneration = coordinator.registerReader()
            assertNotNull(nextGeneration)
            nextGeneration!!.close()
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun `cancelling screen scope does not cancel application import`(): Unit = runBlocking {
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
            val screen = launch(start = CoroutineStart.UNDISPATCHED) {
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
    fun `second import requires a new disposal handshake and advances UI generation`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var imports = 0
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = {
                    imports++
                    BackupImportResult.Imported(imports)
                },
                recover = {},
            )
            assertTrue(coordinator.start(byteArrayOf(1)))
            coordinator.readersDisposed()
            val first = withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            } as HotBackupImportCoordinator.State.Completed
            assertEquals(1L, first.generation)
            assertTrue(coordinator.start(byteArrayOf(2)))
            assertEquals(HotBackupImportCoordinator.State.ClosingReaders, coordinator.state.value)
            assertEquals(1, imports)
            coordinator.readersDisposed()
            val second = withTimeout(2000) {
                coordinator.state.first {
                    it is HotBackupImportCoordinator.State.Completed && it.generation == 2L
                }
            } as HotBackupImportCoordinator.State.Completed
            assertEquals(2, imports)
            assertEquals(
                HotBackupImportCoordinator.Outcome.Result(BackupImportResult.Imported(2)),
                second.outcome,
            )
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
    @Test
    fun `recreation before disposal acknowledgement cannot bypass the reader gate`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val originalScreenScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var imports = 0
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = {
                    imports++
                    BackupImportResult.Imported(0)
                },
                recover = {},
            )

            assertTrue(coordinator.start(byteArrayOf(1)))
            // Model an Activity/screen being destroyed before it can acknowledge
            // disposal. Cancelling its scope must not release the application gate.
            originalScreenScope.cancel()
            assertEquals(HotBackupImportCoordinator.State.ClosingReaders, coordinator.state.value)
            assertEquals(0, imports)
            assertFalse(coordinator.start(byteArrayOf(2)))

            // Only the replacement UI's explicit acknowledgement may begin import.
            coordinator.readersDisposed()
            val completed = withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            } as HotBackupImportCoordinator.State.Completed
            assertEquals(1, imports)
            assertEquals(1L, completed.generation)
        } finally {
            originalScreenScope.cancel()
            appScope.cancel()
        }
    }

    @Test
    fun `recreated screen does not reopen readers while recovery is pending`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val finishRecovery = CompletableDeferred<Unit>()
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = { error("failure after replacement") },
                recover = { finishRecovery.await() },
            )

            assertTrue(coordinator.start(byteArrayOf(1)))
            coordinator.readersDisposed()
            assertEquals(HotBackupImportCoordinator.State.Recovering, coordinator.state.value)
            assertFalse(coordinator.start(byteArrayOf(2)))
            // An Activity recreated here observes Recovering, not Completed/Idle.
            finishRecovery.complete(Unit)
            val completed = withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            } as HotBackupImportCoordinator.State.Completed
            assertEquals(
                HotBackupImportCoordinator.Outcome.Failed("failure after replacement"),
                completed.outcome,
            )
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun `blocked recovery and its retry never grant Activity reader leases`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val releaseRetry = CompletableDeferred<Unit>()
            var recoveryAttempts = 0
            val coordinator = HotBackupImportCoordinator(
                scope = appScope,
                importArchive = { error("injected import failure") },
                recover = {
                    recoveryAttempts++
                    if (recoveryAttempts == 1) error("injected recovery failure")
                    releaseRetry.await()
                },
            )
            val originalActivity = requireNotNull(coordinator.registerReader())
            assertTrue(coordinator.start(byteArrayOf(1)))
            assertNull(coordinator.registerReader())
            originalActivity.close()
            withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Blocked }
            }
            // A recreated Activity must not read a partially restored Room/DataStore.
            assertNull(coordinator.registerReader())
            assertFalse(coordinator.start(byteArrayOf(2)))

            assertTrue(coordinator.retryRecovery())
            assertEquals(HotBackupImportCoordinator.State.Recovering, coordinator.state.value)
            assertNull(coordinator.registerReader())
            releaseRetry.complete(Unit)
            val completed = withTimeout(2000) {
                coordinator.state.first { it is HotBackupImportCoordinator.State.Completed }
            } as HotBackupImportCoordinator.State.Completed
            assertEquals(2, recoveryAttempts)
            assertEquals(
                HotBackupImportCoordinator.Outcome.Failed("injected import failure"),
                completed.outcome,
            )
            requireNotNull(coordinator.registerReader()).close()
        } finally {
            appScope.cancel()
        }
    }

}
