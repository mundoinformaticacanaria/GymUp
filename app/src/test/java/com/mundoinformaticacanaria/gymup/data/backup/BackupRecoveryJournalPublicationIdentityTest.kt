package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A byte-identical regular replacement is not the fsynced file we created. */
class BackupRecoveryJournalPublicationIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `prepare refuses byte-identical regular temp replacement after fsync`() {
        val directory = File(temp.root, "recovery")
        val originalTemp = File(temp.root, "original-preimage-temp")
        val previous = byteArrayOf(10, 20, 30)
        var swapped = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeTrustedPublish = { temporary ->
                if (!swapped && temporary.name == "previous.zip.tmp") {
                    assertTrue(temporary.renameTo(originalTemp))
                    temporary.writeBytes(originalTemp.readBytes())
                    swapped = true
                }
            },
        )

        assertTrue(runCatching { journal.prepare(previous) }.isFailure)
        assertTrue(swapped)
        assertFalse(directory.exists())
        assertArrayEquals(previous, originalTemp.readBytes())
        assertArrayEquals(
            previous,
            File(temp.root, "recovery.preparing/previous.zip.tmp").readBytes(),
        )
    }

    @Test
    fun `commit refuses byte-identical regular marker replacement after fsync`() {
        val directory = File(temp.root, "recovery")
        val originalTemp = File(temp.root, "original-marker-temp")
        val previous = byteArrayOf(40, 50, 60)
        var committing = false
        var swapped = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeTrustedPublish = { temporary ->
                if (committing && !swapped && temporary.name == "state.tmp") {
                    assertTrue(temporary.renameTo(originalTemp))
                    temporary.writeBytes(originalTemp.readBytes())
                    swapped = true
                }
            },
        )
        journal.prepare(previous)
        committing = true

        assertTrue(runCatching { journal.markCommitted() }.isFailure)
        assertTrue(swapped)
        assertArrayEquals(
            previous,
            (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
        assertArrayEquals(originalTemp.readBytes(), File(directory, "state.tmp").readBytes())

        // Repair only the synthetic swap and prove the original PREPARED journal can finish.
        assertTrue(File(directory, "state.tmp").delete())
        journal.markCommitted()
        assertTrue(BackupRecoveryJournal(directory).inspect() is BackupRecoveryJournal.State.Committed)
        journal.cleanupCommitted()
        assertFalse(directory.exists())
        assertTrue(originalTemp.isFile)
    }
}
