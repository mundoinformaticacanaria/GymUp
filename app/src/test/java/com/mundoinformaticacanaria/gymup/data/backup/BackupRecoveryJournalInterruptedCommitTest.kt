package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unexpected temporary markers must never be traversed or silently removed. */
class BackupRecoveryJournalInterruptedCommitTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `interrupted commit refuses temporary marker replaced by a directory after inspect`() {
        val directory = File(temp.root, "recovery")
        val previous = byteArrayOf(11, 22, 33)
        val marker = File(directory, "state.tmp")
        val sentinel = File(marker, "keep.txt")
        var injected = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeInterruptedCommitCleanup = {
                assertEquals(marker.absolutePath, it.absolutePath)
                assertTrue(marker.mkdir())
                sentinel.writeText("do not delete")
                injected = true
            },
        )
        journal.prepare(previous)

        assertTrue(runCatching { journal.markCommitted() }.isFailure)
        assertTrue(injected)
        assertEquals("do not delete", sentinel.readText())
        assertTrue(marker.isDirectory)
        assertArrayEquals(
            previous,
            (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )

        // Only the test fixture repairs the unexpected directory.
        assertTrue(sentinel.delete())
        assertTrue(marker.delete())
        BackupRecoveryJournal(directory).markCommitted()
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(directory).inspect())
        BackupRecoveryJournal(directory).cleanupCommitted()
        assertFalse(directory.exists())
    }
}
