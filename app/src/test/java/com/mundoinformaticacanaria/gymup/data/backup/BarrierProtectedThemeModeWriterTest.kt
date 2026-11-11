package com.mundoinformaticacanaria.gymup.data.backup

import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarrierProtectedThemeModeWriterTest {
    @Test
    fun themeMutationWaitsForExclusiveBackup() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<ThemeMode>()
        val guarded = BarrierProtectedThemeModeWriter({ writes += it }, barrier)
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            guarded.setThemeMode(ThemeMode.DARK)
        }

        assertFalse(writer.isCompleted)
        assertTrue(writes.isEmpty())
        release.complete(Unit)
        backup.join()
        writer.join()
        assertEquals(listOf(ThemeMode.DARK), writes)
    }

    @Test
    fun exclusiveBackupWaitsForThemeMutationToFinish() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val guarded = BarrierProtectedThemeModeWriter({
            events += "theme-start"
            entered.complete(Unit)
            release.await()
            events += "theme-end"
        }, barrier)
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            guarded.setThemeMode(ThemeMode.LIGHT)
        }
        entered.await()
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup { events += "backup" }
        }

        assertFalse(backup.isCompleted)
        release.complete(Unit)
        writer.join()
        backup.join()
        assertEquals(listOf("theme-start", "theme-end", "backup"), events)
    }

    @Test
    fun cancelledThemeMutationWaitingForBackupNeverWrites() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val guarded = BarrierProtectedThemeModeWriter({ writes++ }, barrier)
        val backup = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.withExclusiveBackup {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            guarded.setThemeMode(ThemeMode.SYSTEM)
        }

        writer.cancelAndJoin()
        release.complete(Unit)
        backup.join()
        assertEquals(0, writes)
    }

    @Test
    fun failedThemeMutationReleasesBarrier() = runBlocking {
        val barrier = BackupDomainWriteBarrier()
        val guarded = BarrierProtectedThemeModeWriter(
            { error("simulated DataStore write failure") },
            barrier,
        )

        assertTrue(runCatching { guarded.setThemeMode(ThemeMode.DARK) }.isFailure)
        assertEquals("available", barrier.withExclusiveBackup { "available" })
    }
}
