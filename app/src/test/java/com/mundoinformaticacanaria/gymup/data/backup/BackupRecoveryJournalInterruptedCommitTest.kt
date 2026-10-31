package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import java.nio.file.Files
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

    @Test
    fun `interrupted commit refuses a symlink marker inserted after inspect`() {
        val directory = File(temp.root, "recovery")
        val previous = byteArrayOf(41, 42, 43)
        val marker = File(directory, "state.tmp")
        val outside = File(temp.root, "external-marker")
        outside.writeText("must survive")
        var injected = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeInterruptedCommitCleanup = {
                assertEquals(marker.absolutePath, it.absolutePath)
                Files.createSymbolicLink(marker.toPath(), outside.toPath())
                injected = true
            },
        )
        journal.prepare(previous)

        assertTrue(runCatching { journal.markCommitted() }.isFailure)
        assertTrue(injected)
        assertTrue(Files.isSymbolicLink(marker.toPath()))
        assertEquals("must survive", outside.readText())
        assertArrayEquals(
            previous,
            (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )

        // Repair only the synthetic fixture; a subsequent normal commit must succeed.
        assertTrue(marker.delete())
        BackupRecoveryJournal(directory).markCommitted()
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(directory).inspect())
    }

    @Test
    fun `interrupted regular marker is retired before a fresh committed marker is published`() {
        val directory = File(temp.root, "recovery")
        val previous = byteArrayOf(51, 52, 53)
        val marker = File(directory, "state.tmp")
        var injected = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeInterruptedCommitCleanup = {
                assertEquals(marker.absolutePath, it.absolutePath)
                marker.writeText("incomplete previous attempt")
                injected = true
            },
        )
        journal.prepare(previous)

        journal.markCommitted()
        assertTrue(injected)
        assertFalse(marker.exists())
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(directory).inspect())
        BackupRecoveryJournal(directory).cleanupCommitted()
        assertFalse(directory.exists())
    }
}
