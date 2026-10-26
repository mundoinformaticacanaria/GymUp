package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRecoveryJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `prepared pre-image survives component reconstruction and cannot be overwritten`() {
        val directory = File(temp.root, "recovery")
        val bytes = byteArrayOf(10, 20, 30, 40)
        val journal = BackupRecoveryJournal(directory)
        assertEquals(BackupRecoveryJournal.State.Absent, journal.inspect())

        journal.prepare(bytes)
        val reconstructed = BackupRecoveryJournal(directory)
        val prepared = reconstructed.inspect() as BackupRecoveryJournal.State.Prepared
        assertArrayEquals(bytes, prepared.previousArchive)
        assertTrue(runCatching { reconstructed.prepare(byteArrayOf(99)) }.isFailure)
        assertTrue(runCatching { reconstructed.cleanupCommitted() }.isFailure)
        assertArrayEquals(bytes, (reconstructed.inspect() as BackupRecoveryJournal.State.Prepared).previousArchive)
    }

    @Test
    fun `corrupt or unknown journal fails closed and preserves previous archive`() {
        val directory = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(directory)
        journal.prepare(byteArrayOf(1, 2, 3))
        val archive = File(directory, "previous.zip")
        archive.writeBytes(byteArrayOf(9, 8, 7))
        assertTrue(runCatching { BackupRecoveryJournal(directory).inspect() }.isFailure)
        assertTrue(runCatching { journal.cleanupCommitted() }.isFailure)
        assertTrue(archive.exists())

        File(directory, "state").writeText("2 PREPARED " + "a".repeat(64) + "\n")
        assertTrue(runCatching { BackupRecoveryJournal(directory).inspect() }.isFailure)
        assertTrue(archive.exists())
    }

    @Test
    fun `committed journal can be cleaned after restart without restoring old data`() {
        val directory = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(directory)
        journal.prepare(byteArrayOf(5, 6, 7))
        journal.markCommitted()
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(directory).inspect())
        BackupRecoveryJournal(directory).cleanupCommitted()
        assertFalse(directory.exists())
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(directory).inspect())
    }

    @Test
    fun `oversized state marker fails closed without removing previous archive`() {
        val directory = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(directory)
        journal.prepare(byteArrayOf(1, 2, 3))
        File(directory, "state").writeText("x".repeat(1024))
        assertTrue(runCatching { journal.inspect() }.isFailure)
        assertTrue(runCatching { journal.cleanupCommitted() }.isFailure)
        assertTrue(File(directory, "previous.zip").exists())
    }

    @Test
    fun `partial preparation never looks like a safe empty journal`() {
        val directory = File(temp.root, "recovery")
        assertTrue(directory.mkdir())
        File(directory, "previous.zip").writeBytes(byteArrayOf(1))
        assertTrue(runCatching { BackupRecoveryJournal(directory).inspect() }.isFailure)
        assertTrue(runCatching { BackupRecoveryJournal(directory).prepare(byteArrayOf(2)) }.isFailure)
        assertTrue(File(directory, "previous.zip").exists())
    }
    @Test
    fun `crash during unpublished preparation is cleaned without touching active data`() {
        val directory = File(temp.root, "recovery")
        val preparing = File(temp.root, "recovery.preparing")
        assertTrue(preparing.mkdir())
        File(preparing, "previous.zip").writeBytes(byteArrayOf(1, 2, 3))
        val journal = BackupRecoveryJournal(directory)
        // PREPARED has not been published: the import cannot have mutated domain data.
        assertEquals(BackupRecoveryJournal.State.Absent, journal.inspect())
        assertTrue(runCatching { journal.prepare(byteArrayOf(4)) }.isFailure)
        journal.cleanupAbandonedPreparation()
        assertFalse(preparing.exists())
        journal.prepare(byteArrayOf(4, 5))
        assertArrayEquals(
            byteArrayOf(4, 5),
            (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
    }

    @Test
    fun `unpublished preparation cannot be discarded while active journal exists`() {
        val directory = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(directory)
        val original = byteArrayOf(6, 7, 8)
        journal.prepare(original)
        val preparing = File(temp.root, "recovery.preparing")
        assertTrue(preparing.mkdir())
        File(preparing, "previous.zip").writeBytes(byteArrayOf(99))
        assertTrue(runCatching { journal.cleanupAbandonedPreparation() }.isFailure)
        assertTrue(preparing.exists())
        assertArrayEquals(
            original,
            (journal.inspect() as BackupRecoveryJournal.State.Prepared).previousArchive,
        )
    }

}
