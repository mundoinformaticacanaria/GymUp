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


    @Test
    fun `committed journal with damaged archive fails closed and is not discarded`() {
        val directory = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(directory)
        journal.prepare(byteArrayOf(1, 2, 3))
        journal.markCommitted()
        File(directory, "previous.zip").writeBytes(byteArrayOf(3, 2, 1))

        assertTrue(runCatching { BackupRecoveryJournal(directory).inspect() }.isFailure)
        assertTrue(runCatching { BackupRecoveryJournal(directory).cleanupCommitted() }.isFailure)
        assertTrue(directory.exists())
        assertTrue(File(directory, "previous.zip").exists())
    }

    @Test
    fun `committed journal missing archive fails closed and preserves marker`() {
        val directory = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(directory)
        journal.prepare(byteArrayOf(4, 5, 6))
        journal.markCommitted()
        assertTrue(File(directory, "previous.zip").delete())

        assertTrue(runCatching { BackupRecoveryJournal(directory).inspect() }.isFailure)
        assertTrue(runCatching { BackupRecoveryJournal(directory).cleanupCommitted() }.isFailure)
        assertTrue(File(directory, "state").exists())
    }

    @Test
    fun `journal syncs directory entries before reporting prepared and committed`() {
        val directory = File(temp.root, "recovery")
        val seen = mutableListOf<String>()
        val journal = BackupRecoveryJournal(directory) { seen += it.canonicalPath }

        journal.prepare(byteArrayOf(1, 2, 3))
        assertEquals(
            listOf(
                temp.root.canonicalPath, // creation of .preparing
                File(temp.root, "recovery.preparing").canonicalPath, // previous.zip rename
                File(temp.root, "recovery.preparing").canonicalPath, // state rename
                temp.root.canonicalPath, // publish PREPARED directory
            ),
            seen,
        )
        assertTrue(journal.inspect() is BackupRecoveryJournal.State.Prepared)

        journal.markCommitted()
        assertEquals(directory.canonicalPath, seen.last()) // state.tmp -> state
        assertEquals(BackupRecoveryJournal.State.Committed, journal.inspect())

        journal.cleanupCommitted()
        assertEquals(temp.root.canonicalPath, seen.last()) // retired directory deletion
        assertEquals(BackupRecoveryJournal.State.Absent, journal.inspect())
    }

    @Test
    fun `failure to sync published prepared directory prevents mutation and retains preimage`() {
        val directory = File(temp.root, "recovery")
        val previous = byteArrayOf(4, 5, 6)
        var parentSyncs = 0
        val journal = BackupRecoveryJournal(directory) { parent ->
            if (parent.canonicalPath == temp.root.canonicalPath && ++parentSyncs == 2) {
                error("Simulated failure syncing published PREPARED directory")
            }
        }

        assertTrue(runCatching { journal.prepare(previous) }.isFailure)
        assertEquals(2, parentSyncs)
        assertArrayEquals(
            previous,
            (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
        // Caller must not apply replacement after prepare throws. Startup recovery can retry.
    }

    @Test
    fun `failure to sync committed marker is reported and retains verifiable journal`() {
        val directory = File(temp.root, "recovery")
        var failOnCommit = false
        val journal = BackupRecoveryJournal(directory) { parent ->
            if (failOnCommit && parent.canonicalPath == directory.canonicalPath) {
                error("Simulated directory metadata sync failure")
            }
        }
        journal.prepare(byteArrayOf(7, 8, 9))
        failOnCommit = true

        assertTrue(runCatching { journal.markCommitted() }.isFailure)
        // A rename may have succeeded before its fsync failed; never claim durable success.
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(directory).inspect())
    }


    @Test
    fun `failed sync of staging directory creation leaves no active journal and can retry`() {
        val directory = File(temp.root, "recovery")
        val preparing = File(temp.root, "recovery.preparing")
        val original = byteArrayOf(12, 13, 14)
        val journal = BackupRecoveryJournal(directory) { synced ->
            if (synced.canonicalPath == temp.root.canonicalPath) {
                error("Simulated parent fsync failure immediately after mkdir")
            }
        }

        assertTrue(runCatching { journal.prepare(original) }.isFailure)
        assertFalse(directory.exists())
        assertTrue(preparing.isDirectory)

        // No PREPARED was published, so startup must discard only staging, not restore data.
        var replayed = false
        kotlinx.coroutines.runBlocking {
            BackupRecoveryCoordinator(BackupRecoveryJournal(directory)).recoverBeforeDataAccess {
                replayed = true
            }
        }
        assertFalse(replayed)
        assertFalse(preparing.exists())
        BackupRecoveryJournal(directory).prepare(original)
        assertArrayEquals(
            original,
            (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
    }

    @Test
    fun `failed sync of staged archive entry never publishes prepared marker`() {
        val directory = File(temp.root, "recovery")
        val preparing = File(temp.root, "recovery.preparing")
        val original = byteArrayOf(15, 16, 17)
        val journal = BackupRecoveryJournal(directory) { synced ->
            if (synced.canonicalPath == preparing.canonicalPath) {
                error("Simulated fsync failure after staged archive rename")
            }
        }

        assertTrue(runCatching { journal.prepare(original) }.isFailure)
        assertFalse(directory.exists())
        assertTrue(File(preparing, "previous.zip").isFile)
        assertFalse(File(preparing, "state").exists())

        var replayed = false
        kotlinx.coroutines.runBlocking {
            BackupRecoveryCoordinator(BackupRecoveryJournal(directory)).recoverBeforeDataAccess {
                replayed = true
            }
        }
        assertFalse(replayed)
        assertFalse(preparing.exists())
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(directory).inspect())
        BackupRecoveryJournal(directory).prepare(original)
        assertArrayEquals(
            original,
            (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
    }

    @Test
    fun `symlinked active journal fails closed without following its target`() {
        val target = File(temp.root, "valid-recovery")
        val previous = byteArrayOf(31, 32)
        BackupRecoveryJournal(target).prepare(previous)
        val linked = File(temp.root, "recovery")
        Files.createSymbolicLink(linked.toPath(), target.toPath())

        assertTrue(runCatching { BackupRecoveryJournal(linked).inspect() }.isFailure)
        assertArrayEquals(
            previous,
            (BackupRecoveryJournal(target).inspect() as BackupRecoveryJournal.State.Prepared).previousArchive,
        )
    }

    @Test
    fun `symlinked retired journal cannot delete files outside recovery`() {
        val outside = File(temp.root, "outside-retired")
        assertTrue(outside.mkdir())
        val sentinel = File(outside, "keep.txt")
        sentinel.writeText("preserve")
        val retired = File(temp.root, "recovery.retired")
        Files.createSymbolicLink(retired.toPath(), outside.toPath())

        assertTrue(runCatching {
            BackupRecoveryJournal(File(temp.root, "recovery")).cleanupRetired()
        }.isFailure)
        assertEquals("preserve", sentinel.readText())
        assertTrue(Files.isSymbolicLink(retired.toPath()))
    }

    @Test
    fun `symlinked unpublished preparation cannot delete files outside recovery`() {
        val outside = File(temp.root, "outside-preparing")
        assertTrue(outside.mkdir())
        val sentinel = File(outside, "keep.txt")
        sentinel.writeText("preserve")
        val preparing = File(temp.root, "recovery.preparing")
        Files.createSymbolicLink(preparing.toPath(), outside.toPath())

        val journal = BackupRecoveryJournal(File(temp.root, "recovery"))
        assertTrue(runCatching { journal.cleanupAbandonedPreparation() }.isFailure)
        assertTrue(runCatching { journal.prepare(byteArrayOf(1)) }.isFailure)
        assertEquals("preserve", sentinel.readText())
    }

    @Test
    fun `symlinked preimage cannot be read as trusted recovery data`() {
        val directory = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(directory)
        journal.prepare(byteArrayOf(41, 42))
        val archive = File(directory, "previous.zip")
        val outside = File(temp.root, "outside.zip")
        assertTrue(archive.renameTo(outside))
        Files.createSymbolicLink(archive.toPath(), outside.toPath())

        assertTrue(runCatching { journal.inspect() }.isFailure)
        assertTrue(runCatching { journal.cleanupCommitted() }.isFailure)
        assertArrayEquals(byteArrayOf(41, 42), outside.readBytes())
    }

    @Test
    fun `journal marker swapped to symlink after preflight is never read`() {
        val directory = File(temp.root, "recovery")
        val previous = byteArrayOf(61, 62, 63)
        BackupRecoveryJournal(directory).prepare(previous)
        val outside = File(temp.root, "outside-state")
        outside.writeText("untrusted marker")
        val state = File(directory, "state")
        val saved = File(temp.root, "saved-state")
        var swapped = false
        val reader = BackupRecoveryJournal(
            directory,
            beforeTrustedRead = { file ->
                if (file.name == "state" && !swapped) {
                    assertTrue(state.renameTo(saved))
                    Files.createSymbolicLink(state.toPath(), outside.toPath())
                    swapped = true
                }
            },
        )

        assertTrue(runCatching { reader.inspect() }.isFailure)
        assertTrue(swapped)
        assertTrue(Files.isSymbolicLink(state.toPath()))
        assertEquals("untrusted marker", outside.readText())
        assertTrue(saved.isFile)
        assertArrayEquals(previous, File(directory, "previous.zip").readBytes())
    }

    @Test
    fun `journal preimage swapped to symlink after preflight is never read`() {
        val directory = File(temp.root, "recovery")
        val previous = byteArrayOf(71, 72, 73)
        BackupRecoveryJournal(directory).prepare(previous)
        val outside = File(temp.root, "outside-archive")
        outside.writeBytes(byteArrayOf(99, 98, 97))
        val archive = File(directory, "previous.zip")
        val saved = File(temp.root, "saved-archive")
        var swapped = false
        val reader = BackupRecoveryJournal(
            directory,
            beforeTrustedRead = { file ->
                if (file.name == "previous.zip" && !swapped) {
                    assertTrue(archive.renameTo(saved))
                    Files.createSymbolicLink(archive.toPath(), outside.toPath())
                    swapped = true
                }
            },
        )

        assertTrue(runCatching { reader.inspect() }.isFailure)
        assertTrue(swapped)
        assertTrue(Files.isSymbolicLink(archive.toPath()))
        assertArrayEquals(byteArrayOf(99, 98, 97), outside.readBytes())
        assertArrayEquals(previous, saved.readBytes())
        assertTrue(File(directory, "state").isFile)
    }

    @Test
    fun `dangling symlink marker temp cannot be overwritten during commit`() {
        val directory = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(directory)
        val previous = byteArrayOf(51, 52)
        journal.prepare(previous)
        val marker = File(directory, "state.tmp")
        Files.createSymbolicLink(marker.toPath(), File(temp.root, "missing-target").toPath())

        assertTrue(runCatching { journal.markCommitted() }.isFailure)
        assertTrue(Files.isSymbolicLink(marker.toPath()))
        assertArrayEquals(
            previous,
            (journal.inspect() as BackupRecoveryJournal.State.Prepared).previousArchive,
        )
    }


    @Test
    fun `nested symlink in retired journal fails closed before deleting other entries`() {
        val retired = File(temp.root, "recovery.retired")
        assertTrue(retired.mkdir())
        val marker = File(retired, "state")
        marker.writeText("retain")
        val outside = File(temp.root, "outside-retired-file")
        outside.writeText("untouched")
        val linked = File(retired, "previous.zip")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())

        assertTrue(runCatching {
            BackupRecoveryJournal(File(temp.root, "recovery")).cleanupRetired()
        }.isFailure)
        assertEquals("untouched", outside.readText())
        assertEquals("retain", marker.readText())
        assertTrue(Files.isSymbolicLink(linked.toPath()))
    }

    @Test
    fun `dangling symlink in abandoned preparation preserves all entries`() {
        val preparing = File(temp.root, "recovery.preparing")
        assertTrue(preparing.mkdir())
        val archive = File(preparing, "previous.zip")
        archive.writeBytes(byteArrayOf(1, 2, 3))
        val linked = File(preparing, "state.tmp")
        Files.createSymbolicLink(linked.toPath(), File(temp.root, "missing-target").toPath())

        assertTrue(runCatching {
            BackupRecoveryJournal(File(temp.root, "recovery")).cleanupAbandonedPreparation()
        }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), archive.readBytes())
        assertTrue(Files.isSymbolicLink(linked.toPath()))
    }

    @Test
    fun `unexpected nested directory in abandoned preparation is never traversed`() {
        val preparing = File(temp.root, "recovery.preparing")
        assertTrue(preparing.mkdir())
        val archive = File(preparing, "previous.zip")
        archive.writeBytes(byteArrayOf(4, 5))
        val unexpected = File(preparing, "unknown")
        assertTrue(unexpected.mkdir())
        val sentinel = File(unexpected, "keep.txt")
        sentinel.writeText("preserve")

        assertTrue(runCatching {
            BackupRecoveryJournal(File(temp.root, "recovery")).cleanupAbandonedPreparation()
        }.isFailure)
        assertArrayEquals(byteArrayOf(4, 5), archive.readBytes())
        assertEquals("preserve", sentinel.readText())
    }

    @Test
    fun `retired journal with known interrupted temporary entries is cleaned`() {
        val retired = File(temp.root, "recovery.retired")
        assertTrue(retired.mkdir())
        File(retired, "previous.zip").writeBytes(byteArrayOf(1))
        File(retired, "state.tmp").writeText("incomplete")
        BackupRecoveryJournal(File(temp.root, "recovery")).cleanupRetired()
        assertFalse(retired.exists())
    }

    @Test
    fun `preparation never follows symlink injected before opening temporary preimage`() {
        val directory = File(temp.root, "recovery")
        val outside = File(temp.root, "outside-preimage")
        outside.writeBytes(byteArrayOf(91, 92, 93))
        val journal = BackupRecoveryJournal(
            directory,
            beforeTrustedWrite = { temporary ->
                if (temporary.name == "previous.zip.tmp") {
                    Files.createSymbolicLink(temporary.toPath(), outside.toPath())
                }
            },
        )

        assertTrue(runCatching { journal.prepare(byteArrayOf(1, 2, 3)) }.isFailure)
        assertArrayEquals(byteArrayOf(91, 92, 93), outside.readBytes())
        assertFalse(directory.exists())
        assertTrue(Files.isSymbolicLink(File(temp.root, "recovery.preparing/previous.zip.tmp").toPath()))
    }

    @Test
    fun `commit never follows symlink injected before opening temporary marker`() {
        val directory = File(temp.root, "recovery")
        val outside = File(temp.root, "outside-marker")
        outside.writeText("do not overwrite")
        var injectOnCommit = false
        val journal = BackupRecoveryJournal(
            directory,
            beforeTrustedWrite = { temporary ->
                if (injectOnCommit && temporary.name == "state.tmp") {
                    Files.createSymbolicLink(temporary.toPath(), outside.toPath())
                }
            },
        )
        val previous = byteArrayOf(11, 12, 13)
        journal.prepare(previous)
        injectOnCommit = true

        assertTrue(runCatching { journal.markCommitted() }.isFailure)
        assertEquals("do not overwrite", outside.readText())
        assertTrue(Files.isSymbolicLink(File(directory, "state.tmp").toPath()))
        assertArrayEquals(
            previous,
            (journal.inspect() as BackupRecoveryJournal.State.Prepared).previousArchive,
        )
    }

}
