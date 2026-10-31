package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveCodec
import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BarrierProtectedJournaledBackupDataSourceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `export waits for domain writer and reads its completed mutation`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val source = MutableSource(snapshot("before"))
        val backup = wrapper(source, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            barrier.withDomainWrite {
                entered.complete(Unit)
                release.await()
                source.current = snapshot("after")
            }
        }
        entered.await()
        val exporting = async(start = CoroutineStart.UNDISPATCHED) { backup.exportSnapshot() }
        assertFalse("Export must not capture a partially written domain", exporting.isCompleted)

        release.complete(Unit)
        writer.await()
        assertArrayEquals(snapshot("after").data, exporting.await().data)
    }

    @Test
    fun `domain writer waits until journaled replacement is fully finished`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = MutableSource(snapshot("before"), entered, release)
        val backup = wrapper(source, barrier)
        val events = mutableListOf<String>()

        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            backup.replaceAll(snapshot("after"))
            events += "import-finished"
        }
        entered.await()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            barrier.withDomainWrite { events += "domain-write" }
        }
        assertFalse("Domain writer must wait for the journal commit", writer.isCompleted)

        release.complete(Unit)
        importing.await()
        writer.await()
        assertEquals(listOf("import-finished", "domain-write"), events)
        assertArrayEquals(snapshot("after").data, source.current.data)
    }

    @Test
    fun `cancelling import while domain writer holds barrier does not mutate data`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val source = MutableSource(snapshot("before"))
        val backup = wrapper(source, barrier)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            barrier.withDomainWrite {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            backup.replaceAll(snapshot("after"))
        }
        assertFalse(importing.isCompleted)
        importing.cancelAndJoin()
        release.complete(Unit)
        writer.await()

        assertArrayEquals(snapshot("before").data, source.current.data)
        assertEquals(
            BackupRecoveryJournal.State.Absent,
            BackupRecoveryJournal(File(temp.root, "journal")).inspect(),
        )
    }

    @Test
    fun `failed rollback blocks cooperating writers and exports until recovery succeeds`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val source = object : BackupDataSource {
            var canRestore = false
            override suspend fun exportSnapshot(): BackupSnapshot = snapshot("before")
            override suspend fun replaceAll(snapshot: BackupSnapshot) {
                if (!canRestore || snapshot.data.contentEquals("after".encodeToByteArray())) {
                    error("Simulated replacement/rollback failure")
                }
            }
        }
        val backup = wrapper(source, barrier)

        assertTrue(runCatching { backup.replaceAll(snapshot("after")) }.isFailure)
        assertTrue(runCatching { barrier.withDomainWrite { "unsafe" } }.isFailure)
        assertTrue(runCatching { backup.exportSnapshot() }.isFailure)
        assertTrue(runCatching { backup.replaceAll(snapshot("after")) }.isFailure)

        source.canRestore = true
        backup.recoverBeforeDataAccess()
        assertEquals("allowed", barrier.withDomainWrite { "allowed" })
        assertArrayEquals(snapshot("before").data, backup.exportSnapshot().data)
    }

    @Test
    fun `failed import with successful rollback does not block subsequent writes`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val source = object : BackupDataSource {
            var current = snapshot("before")
            override suspend fun exportSnapshot(): BackupSnapshot = current
            override suspend fun replaceAll(snapshot: BackupSnapshot) {
                if (snapshot.data.contentEquals("after".encodeToByteArray())) {
                    error("Simulated import failure")
                }
                current = snapshot
            }
        }
        val backup = wrapper(source, barrier)

        assertTrue(runCatching { backup.replaceAll(snapshot("after")) }.isFailure)
        assertArrayEquals(snapshot("before").data, source.current.data)
        assertEquals("allowed", barrier.withDomainWrite { "allowed" })
        assertArrayEquals(snapshot("before").data, backup.exportSnapshot().data)
    }

    @Test
    fun `cancelling hot import after mutation restores preimage before releasing writers`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val replacementEntered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val source = object : BackupDataSource {
            var current = snapshot("before")

            override suspend fun exportSnapshot(): BackupSnapshot = current

            override suspend fun replaceAll(snapshot: BackupSnapshot) {
                current = snapshot
                if (snapshot.data.contentEquals("after".encodeToByteArray())) {
                    replacementEntered.complete(Unit)
                    neverRelease.await()
                }
            }
        }
        val backup = wrapper(source, barrier)
        val importing = async(start = CoroutineStart.UNDISPATCHED) {
            backup.replaceAll(snapshot("after"))
        }
        replacementEntered.await()
        assertArrayEquals(snapshot("after").data, source.current.data)

        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            barrier.withDomainWrite {
                assertArrayEquals(snapshot("before").data, source.current.data)
                "allowed"
            }
        }
        assertFalse("Writer must wait until cancellation rollback completes", writer.isCompleted)

        importing.cancelAndJoin()
        assertArrayEquals(snapshot("before").data, source.current.data)
        assertEquals("allowed", writer.await())
        assertEquals(
            BackupRecoveryJournal.State.Absent,
            BackupRecoveryJournal(File(temp.root, "journal")).inspect(),
        )
    }

    @Test
    fun `failed startup recovery keeps domain writes blocked until a successful retry`() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val source = object : BackupDataSource {
            var allowRestore = false
            var current = snapshot("partially-restored")

            override suspend fun exportSnapshot(): BackupSnapshot = current

            override suspend fun replaceAll(snapshot: BackupSnapshot) {
                if (!allowRestore) error("Simulated startup replay failure")
                current = snapshot
            }
        }
        val journal = BackupRecoveryJournal(File(temp.root, "journal"))
        journal.prepare(BackupArchiveCodec.create(snapshot("before"), "1.0-test"))
        val backup = wrapper(source, barrier)

        assertTrue(runCatching { backup.recoverBeforeDataAccess() }.isFailure)
        assertTrue(journal.inspect() is BackupRecoveryJournal.State.Prepared)
        assertTrue("Failed replay must not reopen writes", runCatching {
            barrier.withDomainWrite { "unsafe" }
        }.isFailure)
        assertTrue("Failed replay must not expose a backup", runCatching {
            backup.exportSnapshot()
        }.isFailure)

        source.allowRestore = true
        backup.recoverBeforeDataAccess()
        assertArrayEquals(snapshot("before").data, source.current.data)
        assertEquals("allowed", barrier.withDomainWrite { "allowed" })
        assertArrayEquals(snapshot("before").data, backup.exportSnapshot().data)
    }

    private fun wrapper(
        source: BackupDataSource,
        barrier: BackupDomainWriteBarrier,
    ): BarrierProtectedJournaledBackupDataSource =
        BarrierProtectedJournaledBackupDataSource(
            JournaledBackupDataSource(
                source,
                BackupRecoveryCoordinator(BackupRecoveryJournal(File(temp.root, "journal"))),
                "1.0-test",
            ),
            barrier,
        )

    private fun snapshot(value: String) = BackupSnapshot(value.encodeToByteArray())

    private class MutableSource(
        var current: BackupSnapshot,
        private val entered: CompletableDeferred<Unit>? = null,
        private val release: CompletableDeferred<Unit>? = null,
    ) : BackupDataSource {
        override suspend fun exportSnapshot(): BackupSnapshot = current

        override suspend fun replaceAll(snapshot: BackupSnapshot) {
            entered?.complete(Unit)
            release?.await()
            current = snapshot
        }
    }
}
