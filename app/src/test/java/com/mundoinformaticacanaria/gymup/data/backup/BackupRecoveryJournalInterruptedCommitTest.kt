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

    @Test
    fun `interrupted commit refuses journal root swapped to a symlink after inspect`() {
        val directory = File(temp.root, "recovery")
        val heldRoot = File(temp.root, "recovery-held")
        val outside = File(temp.root, "external-root")
        assertTrue(outside.mkdir())
        val outsideMarker = File(outside, "state.tmp")
        outsideMarker.writeText("external marker must survive")
        val previous = byteArrayOf(61, 62, 63)
        var injected = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeInterruptedCommitCleanup = { marker ->
                assertEquals(File(directory, "state.tmp").absolutePath, marker.absolutePath)
                assertTrue(directory.renameTo(heldRoot))
                Files.createSymbolicLink(directory.toPath(), outside.toPath())
                injected = true
            },
        )
        journal.prepare(previous)

        assertTrue(runCatching { journal.markCommitted() }.isFailure)
        assertTrue(injected)
        assertTrue(Files.isSymbolicLink(directory.toPath()))
        assertEquals("external marker must survive", outsideMarker.readText())
        assertArrayEquals(
            previous,
            (BackupRecoveryJournal(heldRoot).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )

        // Restore only the synthetic fixture; the journal must remain recoverable.
        assertTrue(directory.delete())
        assertTrue(heldRoot.renameTo(directory))
        BackupRecoveryJournal(directory).markCommitted()
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(directory).inspect())
        BackupRecoveryJournal(directory).cleanupCommitted()
        assertFalse(directory.exists())
        assertEquals("external marker must survive", outsideMarker.readText())
    }


    @Test
    fun `interrupted commit rejects another regular prepared journal after inspect`() {
        val directory = File(temp.root, "recovery")
        val heldRoot = File(temp.root, "recovery-held")
        val foreignRoot = File(temp.root, "foreign-prepared")
        val original = byteArrayOf(71, 72, 73)
        val foreign = byteArrayOf(81, 82, 83)
        val foreignMarker = File(foreignRoot, "state.tmp")
        BackupRecoveryJournal(foreignRoot).prepare(foreign)
        foreignMarker.writeText("foreign marker must survive")
        var injected = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeInterruptedCommitCleanup = {
                assertTrue(directory.renameTo(heldRoot))
                assertTrue(foreignRoot.renameTo(directory))
                injected = true
            },
        )
        journal.prepare(original)

        assertTrue(runCatching { journal.markCommitted() }.isFailure)
        assertTrue(injected)
        assertEquals("foreign marker must survive", File(directory, "state.tmp").readText())
        assertArrayEquals(
            foreign,
            (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
        assertArrayEquals(
            original,
            (BackupRecoveryJournal(heldRoot).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )

        // Restore both test fixtures explicitly; only the original journal may be committed.
        assertTrue(directory.renameTo(foreignRoot))
        assertTrue(heldRoot.renameTo(directory))
        BackupRecoveryJournal(directory).markCommitted()
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(directory).inspect())
        assertEquals("foreign marker must survive", foreignMarker.readText())
        assertArrayEquals(
            foreign,
            (BackupRecoveryJournal(foreignRoot).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
    }

}
