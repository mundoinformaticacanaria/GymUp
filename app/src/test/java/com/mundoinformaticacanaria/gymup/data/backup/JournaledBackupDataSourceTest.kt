package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournaledBackupDataSourceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `successful import replaces data and cleans committed journal`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val source = MutableSource(snapshot("before"))
        val journaled = wrapper(source, dir)

        journaled.replaceAll(snapshot("after"))

        assertArrayEquals(snapshot("after").data, source.current.data)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
        journaled.recoverBeforeDataAccess()
        assertArrayEquals(snapshot("after").data, source.current.data)
    }

    @Test
    fun `failure after mutating delegate restores previous snapshot before next import`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val source = MutableSource(snapshot("before"))
        source.failNextWrites = 1
        val journaled = wrapper(source, dir)

        assertTrue(runCatching { journaled.replaceAll(snapshot("after")) }.isFailure)
        assertArrayEquals(snapshot("before").data, source.current.data)
        // Failed import leaves COMMITTED for idempotent cleanup on the next startup.
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(dir).inspect())

        wrapper(source, dir).recoverBeforeDataAccess()
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
        wrapper(source, dir).replaceAll(snapshot("new"))
        assertArrayEquals(snapshot("new").data, source.current.data)
    }

    @Test
    fun `failed rollback blocks imports until next startup restores saved preimage`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val source = MutableSource(snapshot("before"))
        source.failNextWrites = 2
        val journaled = wrapper(source, dir)

        assertTrue(runCatching { journaled.replaceAll(snapshot("after")) }.isFailure)
        assertArrayEquals(snapshot("before").data,
            (BackupRecoveryJournal(dir).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive.let { archive ->
                    val result = com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveCodec
                        .readAndValidate(archive)
                    (result as com.mundoinformaticacanaria.gymup.domain.backup.BackupArchiveReadResult.Valid)
                        .snapshot.data
                })
        assertTrue(runCatching { journaled.replaceAll(snapshot("another")) }.isFailure)

        // Simulate process reconstruction; the third write is allowed and restores the preimage.
        wrapper(source, dir).recoverBeforeDataAccess()
        assertArrayEquals(snapshot("before").data, source.current.data)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }

    @Test
    fun `export fails closed while prepared recovery is pending`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val source = MutableSource(snapshot("possibly-partial"))
        BackupRecoveryJournal(dir).prepare(byteArrayOf(1, 2, 3))
        val result = runCatching { wrapper(source, dir).exportSnapshot() }
        assertTrue(result.isFailure)
        assertEquals(0, source.exportCount)
        assertTrue(BackupRecoveryJournal(dir).inspect() is BackupRecoveryJournal.State.Prepared)
    }

    @Test
    fun `export waits for replacement on same coordinator and reads new snapshot`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : BackupDataSource {
            var current = snapshot("before")
            override suspend fun exportSnapshot(): BackupSnapshot = current
            override suspend fun replaceAll(snapshot: BackupSnapshot) {
                entered.complete(Unit)
                release.await()
                current = snapshot
            }
        }
        val journaled = wrapper(source, dir)
        val importing = async { journaled.replaceAll(snapshot("after")) }
        entered.await()
        val exporting = async { journaled.exportSnapshot() }
        yield()
        assertTrue("Export must wait for replacement", !exporting.isCompleted)
        release.complete(Unit)
        importing.await()
        assertArrayEquals(snapshot("after").data, exporting.await().data)
    }

    @Test
    fun `export on another coordinator waits for in-flight replacement at same journal path`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : BackupDataSource {
            var current = snapshot("before")
            override suspend fun exportSnapshot(): BackupSnapshot = current
            override suspend fun replaceAll(snapshot: BackupSnapshot) {
                entered.complete(Unit)
                release.await()
                current = snapshot
            }
        }
        // Two distinct wrappers previously used independent mutexes over the same journal.
        val importer = wrapper(source, dir)
        val exporter = wrapper(source, File(temp.root, "./recovery"))
        val importing = async { importer.replaceAll(snapshot("after")) }
        entered.await()
        val exporting = async(start = CoroutineStart.UNDISPATCHED) { exporter.exportSnapshot() }
        yield()
        assertTrue("Another coordinator must not read a partial replacement", !exporting.isCompleted)
        release.complete(Unit)
        importing.await()
        assertArrayEquals(snapshot("after").data, exporting.await().data)
    }

    @Test
    fun `startup recovery on another coordinator waits for in-flight replacement`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : BackupDataSource {
            var current = snapshot("before")
            override suspend fun exportSnapshot(): BackupSnapshot = current
            override suspend fun replaceAll(snapshot: BackupSnapshot) {
                entered.complete(Unit)
                release.await()
                current = snapshot
            }
        }
        val importer = wrapper(source, dir)
        val recovering = wrapper(source, File(temp.root, "./recovery"))
        val importing = async { importer.replaceAll(snapshot("after")) }
        entered.await()
        val recovery = async(start = CoroutineStart.UNDISPATCHED) { recovering.recoverBeforeDataAccess() }
        yield()
        assertTrue("Recovery must not race a second coordinator's import", !recovery.isCompleted)
        release.complete(Unit)
        importing.await()
        recovery.await()
        assertArrayEquals(snapshot("after").data, source.current.data)
    }

    private fun wrapper(source: BackupDataSource, directory: File): JournaledBackupDataSource =
        JournaledBackupDataSource(
            source,
            BackupRecoveryCoordinator(BackupRecoveryJournal(directory)),
            "1.0-test",
        )

    private fun snapshot(value: String) = BackupSnapshot(value.encodeToByteArray())

    private class MutableSource(var current: BackupSnapshot) : BackupDataSource {
        var failNextWrites = 0
        var exportCount = 0

        override suspend fun exportSnapshot(): BackupSnapshot {
            exportCount++
            return current
        }

        override suspend fun replaceAll(snapshot: BackupSnapshot) {
            current = snapshot
            if (failNextWrites > 0) {
                failNextWrites--
                error("Simulated failure after replacing data")
            }
        }
    }
}
