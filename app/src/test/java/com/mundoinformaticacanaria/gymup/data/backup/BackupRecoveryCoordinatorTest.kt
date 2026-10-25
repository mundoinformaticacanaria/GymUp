package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRecoveryCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `absent journal does not invoke restoration`() = runBlocking {
        var restored = false
        BackupRecoveryCoordinator(BackupRecoveryJournal(File(temp.root, "recovery")))
            .recoverBeforeDataAccess { restored = true }
        assertFalse(restored)
    }

    @Test
    fun `prepared archive is restored once then journal is removed`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val bytes = byteArrayOf(1, 2, 3)
        BackupRecoveryJournal(dir).prepare(bytes)
        var count = 0
        val restore: suspend (ByteArray) -> Unit = {
            assertArrayEquals(bytes, it)
            count++
        }

        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess(restore)
        assertFalse(dir.exists())
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess(restore)
        assertEquals(1, count)
    }

    @Test
    fun `failed restoration preserves prepared archive for a subsequent restart`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val bytes = byteArrayOf(4, 5, 6)
        BackupRecoveryJournal(dir).prepare(bytes)
        var attempts = 0

        val failure = runCatching {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
                attempts++
                error("Fallo simulado de recuperación")
            }
        }
        assertTrue(failure.isFailure)
        assertArrayEquals(
            bytes,
            (BackupRecoveryJournal(dir).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )

        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            attempts++
            assertArrayEquals(bytes, it)
        }
        assertEquals(2, attempts)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }

    @Test
    fun `committed journal only cleans up and never replays old state`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(dir)
        journal.prepare(byteArrayOf(7, 8, 9))
        journal.markCommitted()

        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            error("COMMITTED must not restore old data")
        }
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }

    @Test
    fun `corrupt prepared journal blocks recovery without calling restore`() = runBlocking {
        val dir = File(temp.root, "recovery")
        BackupRecoveryJournal(dir).prepare(byteArrayOf(10, 11))
        File(dir, "previous.zip").writeBytes(byteArrayOf(99))
        var called = false

        val result = runCatching {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
                called = true
            }
        }
        assertTrue(result.isFailure)
        assertFalse(called)
        assertTrue(dir.exists())
    }
}
