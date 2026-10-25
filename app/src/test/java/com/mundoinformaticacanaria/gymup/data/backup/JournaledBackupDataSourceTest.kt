package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.domain.backup.BackupSnapshot
import java.io.File
import kotlinx.coroutines.runBlocking
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

    private fun wrapper(source: BackupDataSource, directory: File): JournaledBackupDataSource =
        JournaledBackupDataSource(
            source,
            BackupRecoveryCoordinator(BackupRecoveryJournal(directory)),
            "1.0-test",
        )

    private fun snapshot(value: String) = BackupSnapshot(value.encodeToByteArray())

    private class MutableSource(var current: BackupSnapshot) : BackupDataSource {
        var failNextWrites = 0

        override suspend fun exportSnapshot(): BackupSnapshot = current

        override suspend fun replaceAll(snapshot: BackupSnapshot) {
            current = snapshot
            if (failNextWrites > 0) {
                failNextWrites--
                error("Simulated failure after replacing data")
            }
        }
    }
}
