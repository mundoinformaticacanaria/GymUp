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
