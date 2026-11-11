package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Deterministic checks for regular-file substitution between fsync and publication. */
class BackupRecoveryJournalPublicationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `preparing journal refuses same-sized regular preimage swapped after fsync`() {
        val directory = File(temp.root, "recovery")
        val saved = File(temp.root, "original-synced-preimage")
        val expected = byteArrayOf(1, 2, 3)
        var replaced = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeTrustedPublish = { temporary ->
                if (temporary.name == "previous.zip.tmp" && !replaced) {
                    assertTrue(temporary.renameTo(saved))
                    temporary.writeBytes(byteArrayOf(9, 8, 7))
                    replaced = true
                }
            },
        )

        assertTrue(runCatching { journal.prepare(expected) }.isFailure)
        assertTrue(replaced)
        assertFalse(directory.exists())
        assertArrayEquals(expected, saved.readBytes())
        assertArrayEquals(
            byteArrayOf(9, 8, 7),
            File(temp.root, "recovery.preparing/previous.zip.tmp").readBytes(),
        )
    }

    @Test
    fun `committed marker refuses same-sized regular replacement after fsync`() {
        val directory = File(temp.root, "recovery")
        val saved = File(temp.root, "original-synced-marker")
        val previous = byteArrayOf(4, 5, 6)
        var committing = false
        var replaced = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeTrustedPublish = { temporary ->
                if (committing && temporary.name == "state.tmp" && !replaced) {
                    assertTrue(temporary.renameTo(saved))
                    val changed = saved.readBytes()
                    changed[2] = 'X'.code.toByte()
                    temporary.writeBytes(changed)
                    replaced = true
                }
            },
        )
        journal.prepare(previous)
        committing = true

        assertTrue(runCatching { journal.markCommitted() }.isFailure)
        assertTrue(replaced)
        assertTrue(saved.isFile)
        assertArrayEquals(
            previous,
            (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
    }
}
