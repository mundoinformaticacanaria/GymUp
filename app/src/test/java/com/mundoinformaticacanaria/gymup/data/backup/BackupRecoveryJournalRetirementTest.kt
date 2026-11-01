package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Retirement must never discard a different journal substituted after the first inspection. */
class BackupRecoveryJournalRetirementTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `retirement refuses a different prepared journal swapped after committed inspection`() {
        val active = File(temp.root, "recovery")
        val held = File(temp.root, "recovery-held")
        val foreignRoot = File(temp.root, "foreign-prepared")
        val original = byteArrayOf(11, 12, 13)
        val foreign = byteArrayOf(21, 22, 23)
        BackupRecoveryJournal(foreignRoot).prepare(foreign)

        var swapped = false
        val journal = BackupRecoveryJournal(
            active,
            beforeCommittedRetirement = {
                assertEquals(active.absolutePath, it.absolutePath)
                assertTrue(active.renameTo(held))
                assertTrue(foreignRoot.renameTo(active))
                swapped = true
            },
        )
        journal.prepare(original)
        journal.markCommitted()

        assertTrue(runCatching { journal.cleanupCommitted() }.isFailure)
        assertTrue(swapped)
        assertFalse(File(temp.root, "recovery.retired").exists())
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(held).inspect())
        assertArrayEquals(
            foreign,
            (BackupRecoveryJournal(active).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )

        // Only the synthetic fixture repairs the swap; the real committed journal can retire.
        assertTrue(active.renameTo(foreignRoot))
        assertTrue(held.renameTo(active))
        BackupRecoveryJournal(active).cleanupCommitted()
        assertFalse(active.exists())
        assertArrayEquals(
            foreign,
            (BackupRecoveryJournal(foreignRoot).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
    }

    @Test
    fun `retirement refuses another committed journal with different preimage`() {
        val active = File(temp.root, "recovery")
        val held = File(temp.root, "recovery-held")
        val foreignRoot = File(temp.root, "foreign-committed")
        val original = byteArrayOf(31, 32, 33)
        val foreign = byteArrayOf(41, 42, 43)
        BackupRecoveryJournal(foreignRoot).apply {
            prepare(foreign)
            markCommitted()
        }

        var swapped = false
        val journal = BackupRecoveryJournal(
            active,
            beforeCommittedRetirement = {
                assertTrue(active.renameTo(held))
                assertTrue(foreignRoot.renameTo(active))
                swapped = true
            },
        )
        journal.prepare(original)
        journal.markCommitted()

        assertTrue(runCatching { journal.cleanupCommitted() }.isFailure)
        assertTrue(swapped)
        assertFalse(File(temp.root, "recovery.retired").exists())
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(active).inspect())
        assertArrayEquals(original, File(held, "previous.zip").readBytes())
        assertArrayEquals(foreign, File(active, "previous.zip").readBytes())

        assertTrue(active.renameTo(foreignRoot))
        assertTrue(held.renameTo(active))
        BackupRecoveryJournal(active).cleanupCommitted()
        assertFalse(active.exists())
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(foreignRoot).inspect())
        assertArrayEquals(foreign, File(foreignRoot, "previous.zip").readBytes())
    }

    @Test
    fun `retirement refuses an identical committed journal swapped after inspection`() {
        val active = File(temp.root, "recovery")
        val held = File(temp.root, "recovery-held")
        val foreignRoot = File(temp.root, "foreign-committed")
        val sameArchive = byteArrayOf(71, 72, 73)
        BackupRecoveryJournal(foreignRoot).apply {
            prepare(sameArchive)
            markCommitted()
        }
        val journal = BackupRecoveryJournal(
            active,
            beforeCommittedRetirement = {
                assertTrue(active.renameTo(held))
                assertTrue(foreignRoot.renameTo(active))
            },
        )
        journal.prepare(sameArchive)
        journal.markCommitted()

        // Content and COMMITTED markers match; only the directory identity differs.
        assertTrue(runCatching { journal.cleanupCommitted() }.isFailure)
        assertFalse(File(temp.root, "recovery.retired").exists())
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(held).inspect())
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(active).inspect())
        assertArrayEquals(sameArchive, File(held, "previous.zip").readBytes())
        assertArrayEquals(sameArchive, File(active, "previous.zip").readBytes())
    }
}
