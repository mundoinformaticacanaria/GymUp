package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarrierProtectedExerciseImageWriterTest {
    private val item = ExerciseImageItem("image", "exercise", 1, "USER", "user:image", null)

    @Test
    fun addWaitsForExclusiveBackupBeforeWritingFileOrMetadata() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val writer = BarrierProtectedExerciseImageWriter(
            { id, bytes ->
                events += "add:$id:${bytes.size}"
                item
            },
            { events += "delete:$it" },
            barrier,
        )
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val add = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals(item, writer.addUserImage("exercise", byteArrayOf(1, 2)))
        }

        assertFalse(add.isCompleted)
        assertTrue(events.isEmpty())
        release.complete(Unit)
        backup.join()
        add.join()
        assertEquals(listOf("add:exercise:2"), events)
    }

    @Test
    fun deleteWaitsForExclusiveBackupBeforeRemovingImage() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        val writer = BarrierProtectedExerciseImageWriter({ _, _ -> item }, { deleted += it }, barrier)
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            writer.deleteUserImage("image")
        }

        assertFalse(deletion.isCompleted)
        assertTrue(deleted.isEmpty())
        release.complete(Unit)
        backup.join()
        deletion.join()
        assertEquals(listOf("image"), deleted)
    }

    @Test
    fun backupWaitsUntilImageAndRoomMutationHaveFinished() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val writer = BarrierProtectedExerciseImageWriter(
            { _, _ ->
                events += "write-start"
                entered.complete(Unit)
                release.await()
                events += "write-end"
                item
            },
            {},
            barrier,
        )
        val add = launch(start = CoroutineStart.UNDISPATCHED) {
            writer.addUserImage("exercise", byteArrayOf(1))
        }
        entered.await()
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup { events += "backup" }
        }

        assertFalse(backup.isCompleted)
        release.complete(Unit)
        add.join()
        backup.join()
        assertEquals(listOf("write-start", "write-end", "backup"), events)
    }

    @Test
    fun cancelledImageMutationWaitingForBackupNeverWrites() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val writer = BarrierProtectedExerciseImageWriter(
            { _, _ -> writes++; item },
            { writes++ },
            barrier,
        )
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val add = launch(start = CoroutineStart.UNDISPATCHED) {
            writer.addUserImage("exercise", byteArrayOf(1))
        }
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            writer.deleteUserImage("image")
        }

        add.cancelAndJoin()
        deletion.cancelAndJoin()
        release.complete(Unit)
        backup.join()
        assertEquals(0, writes)
    }

    @Test
    fun failedImageMutationReleasesBarrier() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val writer = BarrierProtectedExerciseImageWriter(
            { _, _ -> error("simulated image write failure") },
            { error("simulated image delete failure") },
            barrier,
        )

        assertTrue(runCatching { writer.addUserImage("exercise", byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { writer.deleteUserImage("image") }.isFailure)
        assertEquals("ready", barrier.withExclusiveBackup { "ready" })
    }
}
