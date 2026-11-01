package com.mundoinformaticacanaria.gymup.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs on an Android device/emulator, NOT Robolectric. In particular it exercises the real
 * FileChannel.force(true) directory implementation used by BackupRecoveryJournal.
 *
 * Passing this test verifies API/filesystem compatibility for the tested device only.
 * It does NOT simulate sudden process death, power loss or durable Room/DataStore writes.
 */
class BackupRecoveryJournalDeviceTest {
    @Test
    fun preparedAndCommittedDirectoryMetadataCanBeSyncedInPrivateStorage() {
        withJournalDirectory { directory ->
            val previous = byteArrayOf(1, 2, 3, 4)
            val journal = BackupRecoveryJournal(directory)
            assertEquals(BackupRecoveryJournal.State.Absent, journal.inspect())

            // Includes fsync of the parent directory, staged entries, PREPARED and COMMITTED.
            journal.prepare(previous)
            val reopened = BackupRecoveryJournal(directory)
            assertArrayEquals(
                previous,
                (reopened.inspect() as BackupRecoveryJournal.State.Prepared).previousArchive,
            )
            reopened.markCommitted()
            assertEquals(
                BackupRecoveryJournal.State.Committed,
                BackupRecoveryJournal(directory).inspect(),
            )
            BackupRecoveryJournal(directory).cleanupCommitted()
            assertEquals(
                BackupRecoveryJournal.State.Absent,
                BackupRecoveryJournal(directory).inspect(),
            )
            assertFalse(File(directory.parentFile, "${directory.name}.retired").exists())
        }
    }

    @Test
    fun abandonedPreparationCleanupSyncsParentDirectoryOnDevice() {
        withJournalDirectory { directory ->
            val preparing = File(directory.parentFile, "${directory.name}.preparing")
            assertTrue(preparing.mkdirs())
            File(preparing, "previous.zip").writeBytes(byteArrayOf(7, 8))
            val journal = BackupRecoveryJournal(directory)
            journal.cleanupAbandonedPreparation()
            assertFalse(preparing.exists())
            journal.prepare(byteArrayOf(9))
            assertTrue(BackupRecoveryJournal(directory).inspect() is BackupRecoveryJournal.State.Prepared)
        }
    }

    @Test
    fun failedReplayKeepsPreparedJournalForDeviceRetry() {
        withJournalDirectory { directory ->
            val previous = byteArrayOf(12, 34, 56, 78)
            BackupRecoveryJournal(directory).prepare(previous)
            var replayCount = 0

            // Simulate an interrupted restore without killing the instrumentation process.
            // The pre-image must remain durable and readable by a NEW coordinator.
            val firstFailure = runCatching {
                runBlocking {
                    BackupRecoveryCoordinator(BackupRecoveryJournal(directory))
                        .recoverBeforeDataAccess { archive ->
                            assertArrayEquals(previous, archive)
                            replayCount++
                            error("injected restore failure")
                        }
                }
            }.exceptionOrNull()
            assertTrue(firstFailure is IllegalStateException)
            assertArrayEquals(
                previous,
                (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                    .previousArchive,
            )

            runBlocking {
                BackupRecoveryCoordinator(BackupRecoveryJournal(directory))
                    .recoverBeforeDataAccess { archive ->
                        assertArrayEquals(previous, archive)
                        replayCount++
                    }
            }
            assertEquals(2, replayCount)
            assertEquals(
                BackupRecoveryJournal.State.Absent,
                BackupRecoveryJournal(directory).inspect(),
            )

            // A subsequent startup must not replay an already committed restore.
            runBlocking {
                BackupRecoveryCoordinator(BackupRecoveryJournal(directory))
                    .recoverBeforeDataAccess { replayCount++ }
            }
            assertEquals(2, replayCount)
        }
    }


    @Test
    fun swappedIdenticalPreparingRootIsRejectedByDeviceFileIdentity() {
        withJournalDirectory { directory ->
            val staging = File(directory.parentFile, "${directory.name}.preparing")
            val held = File(directory.parentFile, "${directory.name}.held-identical")
            val foreign = File(directory.parentFile, "${directory.name}.foreign-identical")
            val previous = byteArrayOf(91, 92, 93)
            try {
                BackupRecoveryJournal(foreign).prepare(previous)
                var swapped = false
                val journal = BackupRecoveryJournal(
                    directory,
                    syncDirectory = { synced ->
                        // This seam injects a deterministic swap, not a durability test.
                        if (!swapped && synced.absolutePath == staging.absolutePath &&
                            File(staging, "state").isFile
                        ) {
                            val key = Files.readAttributes(
                                staging.toPath(),
                                BasicFileAttributes::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            ).fileKey()
                            assertNotNull("Android did not expose a staging directory fileKey", key)
                            assertTrue(staging.renameTo(held))
                            assertTrue(foreign.renameTo(staging))
                            swapped = true
                        }
                    },
                )

                assertTrue(runCatching { journal.prepare(previous) }.isFailure)
                assertTrue(swapped)
                assertFalse(directory.exists())
                assertArrayEquals(previous, File(held, "previous.zip").readBytes())
                assertArrayEquals(previous, File(staging, "previous.zip").readBytes())
                assertArrayEquals(File(held, "state").readBytes(), File(staging, "state").readBytes())
            } finally {
                // Isolated random fixture under noBackupFilesDir; no app user data.
                held.deleteRecursively()
                staging.deleteRecursively()
                foreign.deleteRecursively()
            }
        }
    }

    @Test
    fun swappedRegularPreparingRootIsRejectedByDeviceFileIdentity() {
        withJournalDirectory { directory ->
            val preparing = File(directory.parentFile, "${directory.name}.preparing")
            val held = File(directory.parentFile, "${directory.name}.held")
            val foreign = File(directory.parentFile, "${directory.name}.foreign")
            val original = byteArrayOf(1, 2, 3)
            val alien = byteArrayOf(4, 5, 6)
            try {
                assertTrue(preparing.mkdir())
                assertTrue(foreign.mkdir())
                File(preparing, "previous.zip").writeBytes(original)
                File(foreign, "previous.zip").writeBytes(alien)

                // The JVM test alone cannot prove fileKey() exists on Android API 35/36.
                // Fail explicitly rather than treating a null key as protection.
                val identity = Files.readAttributes(
                    preparing.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).fileKey()
                assertNotNull("Android filesystem did not expose a directory fileKey", identity)

                var swapped = false
                val journal = BackupRecoveryJournal(
                    directory,
                    beforeArtifactDelete = {
                        if (!swapped) {
                            assertTrue(preparing.renameTo(held))
                            assertTrue(foreign.renameTo(preparing))
                            swapped = true
                        }
                    },
                )
                assertTrue(runCatching { journal.cleanupAbandonedPreparation() }.isFailure)
                assertTrue(swapped)
                assertArrayEquals(original, File(held, "previous.zip").readBytes())
                assertArrayEquals(alien, File(preparing, "previous.zip").readBytes())
            } finally {
                // These are isolated test-only directories; never touch the app image store.
                held.deleteRecursively()
                foreign.deleteRecursively()
                preparing.deleteRecursively()
            }
        }
    }

    @Test
    fun swappedRegularArchiveIsRejectedByDeviceFileIdentity() {
        withJournalDirectory { directory ->
            val preparing = File(directory.parentFile, "${directory.name}.preparing")
            val archive = File(preparing, "previous.zip")
            val held = File(directory.parentFile, "${directory.name}.held-archive")
            val foreign = File(directory.parentFile, "${directory.name}.foreign-archive")
            val originalBytes = byteArrayOf(10, 20, 30)
            val foreignBytes = byteArrayOf(40, 50, 60)
            try {
                assertTrue(preparing.mkdir())
                archive.writeBytes(originalBytes)
                foreign.writeBytes(foreignBytes)

                // Check a regular file as well as the directory identity covered above.
                val identity = Files.readAttributes(
                    archive.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).fileKey()
                assertNotNull("Android filesystem did not expose a regular-file fileKey", identity)

                var swapped = false
                val journal = BackupRecoveryJournal(
                    directory,
                    beforeArtifactDelete = { entry ->
                        if (!swapped && entry.name == "previous.zip") {
                            assertTrue(archive.renameTo(held))
                            assertTrue(foreign.renameTo(archive))
                            swapped = true
                        }
                    },
                )
                assertTrue(runCatching { journal.cleanupAbandonedPreparation() }.isFailure)
                assertTrue(swapped)
                assertArrayEquals(originalBytes, held.readBytes())
                assertArrayEquals(foreignBytes, archive.readBytes())
            } finally {
                // All paths are unique test fixtures under noBackupFilesDir.
                preparing.deleteRecursively()
                held.delete()
                foreign.delete()
            }
        }
    }

    @Test
    fun swappedCommittedRootKeepsBothPreimagesOnDevice() {
        withJournalDirectory { directory ->
            val held = File(directory.parentFile, "${directory.name}.held-committed")
            val foreign = File(directory.parentFile, "${directory.name}.foreign-committed")
            val previous = byteArrayOf(11, 22, 33)
            val replacement = byteArrayOf(44, 55, 66)
            try {
                BackupRecoveryJournal(directory).apply {
                    prepare(previous)
                    markCommitted()
                }
                BackupRecoveryJournal(foreign).apply {
                    prepare(replacement)
                    markCommitted()
                }

                // Swap two *valid* COMMITTED roots after inspection but before retirement.
                // Neither is a symlink: this exercises the real Android rename/marker checks.
                var swapped = false
                val journal = BackupRecoveryJournal(
                    directory,
                    beforeCommittedRetirement = {
                        assertTrue(directory.renameTo(held))
                        assertTrue(foreign.renameTo(directory))
                        swapped = true
                    },
                )
                assertTrue(runCatching { journal.cleanupCommitted() }.isFailure)
                assertTrue(swapped)
                assertArrayEquals(previous, File(held, "previous.zip").readBytes())
                assertArrayEquals(replacement, File(directory, "previous.zip").readBytes())
                assertTrue(File(held, "state").readText().contains("COMMITTED"))
                assertTrue(File(directory, "state").readText().contains("COMMITTED"))
            } finally {
                // Only these uniquely named test fixtures are removed.
                held.deleteRecursively()
                foreign.deleteRecursively()
                directory.deleteRecursively()
            }
        }
    }


    @Test
    fun swappedCommittedRootWithIdenticalPreimageIsRejectedByDeviceIdentity() {
        withJournalDirectory { directory ->
            val held = File(directory.parentFile, "${directory.name}.held-identical")
            val foreign = File(directory.parentFile, "${directory.name}.foreign-identical")
            val sameArchive = byteArrayOf(71, 72, 73)
            try {
                BackupRecoveryJournal(foreign).apply {
                    prepare(sameArchive)
                    markCommitted()
                }
                val journal = BackupRecoveryJournal(
                    directory,
                    beforeCommittedRetirement = {
                        assertTrue(directory.renameTo(held))
                        assertTrue(foreign.renameTo(directory))
                    },
                )
                journal.prepare(sameArchive)
                journal.markCommitted()
                val identity = Files.readAttributes(
                    directory.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).fileKey()
                assertNotNull("Android filesystem did not expose a journal directory fileKey", identity)

                // Unlike a different archive, inspect() and marker comparison cannot detect
                // this swap: the real Android filesystem identity must reject it.
                assertTrue(runCatching { journal.cleanupCommitted() }.isFailure)
                assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(held).inspect())
                assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(directory).inspect())
                assertArrayEquals(sameArchive, File(held, "previous.zip").readBytes())
                assertArrayEquals(sameArchive, File(directory, "previous.zip").readBytes())
            } finally {
                held.deleteRecursively()
                foreign.deleteRecursively()
                directory.deleteRecursively()
            }
        }
    }


    @Test
    fun swappedPreparedRootWithIdenticalPreimageIsRejectedByDeviceIdentity() {
        withJournalDirectory { directory ->
            val held = File(directory.parentFile, "${directory.name}.held-prepared-identical")
            val foreign = File(directory.parentFile, "${directory.name}.foreign-prepared-identical")
            val previous = byteArrayOf(81, 82, 83)
            try {
                BackupRecoveryJournal(foreign).prepare(previous)
                File(foreign, "state.tmp").writeText("foreign interrupted commit")
                val journal = BackupRecoveryJournal(
                    directory,
                    beforeInterruptedCommitCleanup = {
                        assertTrue(directory.renameTo(held))
                        assertTrue(foreign.renameTo(directory))
                    },
                )
                journal.prepare(previous)
                val identity = Files.readAttributes(
                    directory.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).fileKey()
                assertNotNull("Android filesystem did not expose a PREPARED root fileKey", identity)

                // Identical archive/marker bytes are insufficient to identify the original root.
                assertTrue(runCatching { journal.markCommitted() }.isFailure)
                assertEquals("foreign interrupted commit", File(directory, "state.tmp").readText())
                assertArrayEquals(
                    previous,
                    (BackupRecoveryJournal(held).inspect() as BackupRecoveryJournal.State.Prepared)
                        .previousArchive,
                )
                assertArrayEquals(
                    previous,
                    (BackupRecoveryJournal(directory).inspect() as BackupRecoveryJournal.State.Prepared)
                        .previousArchive,
                )
            } finally {
                held.deleteRecursively()
                foreign.deleteRecursively()
                directory.deleteRecursively()
            }
        }
    }

    private fun withJournalDirectory(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "gymup-journal-test-${UUID.randomUUID()}")
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
            File(directory.parentFile, "${directory.name}.preparing").deleteRecursively()
            File(directory.parentFile, "${directory.name}.retired").deleteRecursively()
        }
    }
}
