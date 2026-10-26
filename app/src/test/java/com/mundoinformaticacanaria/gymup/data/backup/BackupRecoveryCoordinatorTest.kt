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
    fun `interruption after successful replay before commit retries idempotently on restart`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val previous = byteArrayOf(41, 42, 43)
        BackupRecoveryJournal(dir).prepare(previous)
        var current = byteArrayOf(99)
        var replays = 0

        val interrupted = runCatching {
            BackupRecoveryCoordinator(
                BackupRecoveryJournal(dir),
                afterRestoreBeforeCommit = { error("Simulated process stop after replay") },
            ).recoverBeforeDataAccess { archive ->
                current = archive.copyOf()
                replays++
            }
        }

        assertTrue(interrupted.isFailure)
        assertArrayEquals(previous, current)
        assertArrayEquals(
            previous,
            (BackupRecoveryJournal(dir).inspect() as BackupRecoveryJournal.State.Prepared).previousArchive,
        )

        // A fresh coordinator cannot assume that a completed replay was committed.
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess { archive ->
            current = archive.copyOf()
            replays++
        }
        assertEquals(2, replays)
        assertArrayEquals(previous, current)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            error("Committed recovery must not replay again")
        }
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

    @Test
    fun `interrupted committed cleanup does not restore previous data`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val retired = File(temp.root, "recovery.retired")
        val journal = BackupRecoveryJournal(dir)
        journal.prepare(byteArrayOf(1, 2, 3))
        journal.markCommitted()
        assertTrue(dir.renameTo(retired))
        assertTrue(File(retired, "state").delete())

        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            error("Committed data must not be rolled back during cleanup")
        }
        assertFalse(dir.exists())
        assertFalse(retired.exists())
    }

    @Test
    fun `interrupted committed marker write can finish recovery after restart`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val bytes = byteArrayOf(21, 22, 23)
        BackupRecoveryJournal(dir).prepare(bytes)
        // Simulate process death after writing state.tmp but before publishing COMMITTED.
        File(dir, "state.tmp").writeText("1 COMMITTED " + "0".repeat(64) + "\n")
        var restored = 0

        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            assertArrayEquals(bytes, it)
            restored++
        }

        assertEquals(1, restored)
        assertFalse(File(dir, "state.tmp").exists())
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            error("Recovery must not replay after successful commit")
        }
    }

    @Test
    fun `unremovable interrupted marker fails closed and preserves prepared archive`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val bytes = byteArrayOf(24, 25)
        BackupRecoveryJournal(dir).prepare(bytes)
        assertTrue(File(dir, "state.tmp").mkdir())
        var restored = 0

        val result = runCatching {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
                restored++
            }
        }

        assertTrue(result.isFailure)
        assertEquals(1, restored)
        assertArrayEquals(
            bytes,
            (BackupRecoveryJournal(dir).inspect() as BackupRecoveryJournal.State.Prepared).previousArchive,
        )
    }

    @Test
    fun `retired cleanup blocks new journal until completed`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val retired = File(temp.root, "recovery.retired")
        val journal = BackupRecoveryJournal(dir)
        journal.prepare(byteArrayOf(1))
        journal.markCommitted()
        assertTrue(dir.renameTo(retired))
        assertTrue(runCatching { journal.prepare(byteArrayOf(2)) }.isFailure)
        journal.cleanupRetired()
        journal.prepare(byteArrayOf(2))
        assertTrue(journal.inspect() is BackupRecoveryJournal.State.Prepared)
    }

    @Test
    fun `successful replacement commits and removes journal`() = runBlocking {
        val dir = File(temp.root, "recovery")
        var captures = 0
        var applied = 0
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).replaceWithRecovery(
            capturePrevious = { captures++; byteArrayOf(1, 2) },
            applyReplacement = { applied++ },
            restorePrevious = { error("No rollback on success") },
        )
        assertEquals(1, captures)
        assertEquals(1, applied)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }

    @Test
    fun `failed replacement restores preimage and retains committed cleanup for restart`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val before = byteArrayOf(3, 4)
        var restored: ByteArray? = null
        val result = runCatching {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).replaceWithRecovery(
                capturePrevious = { before },
                applyReplacement = { error("Simulated replacement failure") },
                restorePrevious = { restored = it },
            )
        }
        assertTrue(result.isFailure)
        assertArrayEquals(before, restored)
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(dir).inspect())
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            error("Committed journal must not replay preimage")
        }
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }

    @Test
    fun `failed rollback keeps prepared preimage for retry after restart`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val before = byteArrayOf(5, 6)
        val result = runCatching {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).replaceWithRecovery(
                capturePrevious = { before },
                applyReplacement = { error("Simulated interruption") },
                restorePrevious = { error("Simulated rollback failure") },
            )
        }
        assertTrue(result.isFailure)
        assertEquals(1, result.exceptionOrNull()!!.suppressed.size)
        assertArrayEquals(
            before,
            (BackupRecoveryJournal(dir).inspect() as BackupRecoveryJournal.State.Prepared).previousArchive,
        )
        var replayed = false
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            assertArrayEquals(before, it)
            replayed = true
        }
        assertTrue(replayed)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }

    @Test
    fun `pending journal blocks replacement before capture or mutation`() = runBlocking {
        val dir = File(temp.root, "recovery")
        BackupRecoveryJournal(dir).prepare(byteArrayOf(7, 8))
        var invoked = false
        val result = runCatching {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).replaceWithRecovery(
                capturePrevious = { invoked = true; byteArrayOf(1) },
                applyReplacement = { invoked = true },
                restorePrevious = { invoked = true },
            )
        }
        assertTrue(result.isFailure)
        assertFalse(invoked)
        assertTrue(BackupRecoveryJournal(dir).inspect() is BackupRecoveryJournal.State.Prepared)
    }
    @Test
    fun `startup recovery removes interrupted unpublished preparation before data access`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val preparing = File(temp.root, "recovery.preparing")
        assertTrue(preparing.mkdir())
        File(preparing, "previous.zip").writeBytes(byteArrayOf(1))
        var restored = false

        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            restored = true
        }

        assertFalse(restored)
        assertFalse(preparing.exists())
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }

}
