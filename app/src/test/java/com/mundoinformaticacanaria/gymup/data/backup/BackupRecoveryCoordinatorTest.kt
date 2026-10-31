package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    fun `committed journal permits export while obsolete cleanup is pending`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(dir)
        journal.prepare(byteArrayOf(1, 2, 3))
        journal.markCommitted()
        var reads = 0

        val exported = BackupRecoveryCoordinator(BackupRecoveryJournal(dir))
            .readSnapshotIfRecovered {
                reads++
                byteArrayOf(90, 91)
            }

        assertArrayEquals(byteArrayOf(90, 91), exported)
        assertEquals(1, reads)
        // A read-only export must not mutate or silently retire the journal.
        assertEquals(BackupRecoveryJournal.State.Committed, journal.inspect())
    }

    @Test
    fun `prepared or corrupt committed journal blocks export before domain read`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val journal = BackupRecoveryJournal(dir)
        journal.prepare(byteArrayOf(1, 2, 3))
        var reads = 0
        val coordinator = BackupRecoveryCoordinator(journal)

        assertTrue(runCatching {
            coordinator.readSnapshotIfRecovered { reads++; byteArrayOf(42) }
        }.isFailure)
        assertEquals(0, reads)

        journal.markCommitted()
        File(dir, "previous.zip").writeBytes(byteArrayOf(99))
        assertTrue(runCatching {
            coordinator.readSnapshotIfRecovered { reads++; byteArrayOf(42) }
        }.isFailure)
        assertEquals(0, reads)
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

    @Test
    fun `cancellation during apply restores previous state before leaving the journal committed`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val before = byteArrayOf(31, 32, 33)
        var current = before.copyOf()
        var restored = false
        val applying = CompletableDeferred<Unit>()
        val interrupted = CompletableDeferred<Unit>()

        val worker = launch {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).replaceWithRecovery(
                capturePrevious = { before },
                applyReplacement = {
                    current = byteArrayOf(99)
                    applying.complete(Unit)
                    interrupted.await()
                },
                restorePrevious = { archive ->
                    // Suspension must still complete even though the caller is cancelled.
                    delay(1)
                    current = archive.copyOf()
                    restored = true
                },
            )
        }

        applying.await()
        worker.cancelAndJoin()

        assertTrue("Rollback must run in NonCancellable after cancellation", restored)
        assertArrayEquals(before, current)
        // A failed import is reported as cancelled, but its restored pre-image is committed.
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(dir).inspect())
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            error("COMMITTED rollback must not replay on restart")
        }
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }

    @Test
    fun `cancellation during startup replay preserves prepared archive for retry`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val before = byteArrayOf(41, 42)
        BackupRecoveryJournal(dir).prepare(before)
        val replayStarted = CompletableDeferred<Unit>()
        val interrupted = CompletableDeferred<Unit>()

        val worker = launch {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
                replayStarted.complete(Unit)
                interrupted.await()
            }
        }

        replayStarted.await()
        worker.cancelAndJoin()
        assertArrayEquals(
            before,
            (BackupRecoveryJournal(dir).inspect() as BackupRecoveryJournal.State.Prepared).previousArchive,
        )

        var replayed = 0
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            assertArrayEquals(before, it)
            replayed++
        }
        assertEquals(1, replayed)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }


    @Test
    fun `failed directory sync after prepared publication never starts replacement`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val previous = byteArrayOf(50, 51, 52)
        var parentSyncs = 0
        val journal = BackupRecoveryJournal(dir, syncDirectory = { parent ->
            if (parent.canonicalPath == temp.root.canonicalPath && ++parentSyncs == 2) {
                error("Simulated PREPARED directory fsync failure")
            }
        })
        var applied = false
        val result = runCatching {
            BackupRecoveryCoordinator(journal).replaceWithRecovery(
                capturePrevious = { previous },
                applyReplacement = { applied = true },
                restorePrevious = { error("No domain mutation occurred") },
            )
        }
        assertTrue(result.isFailure)
        assertFalse(applied)
        assertArrayEquals(
            previous,
            (BackupRecoveryJournal(dir).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )

        var recovered = false
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess { archive ->
            assertArrayEquals(previous, archive)
            recovered = true
        }
        assertTrue(recovered)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }


    @Test
    fun `failed sync after committed rename must not roll back underneath committed marker`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val previous = byteArrayOf(61, 62)
        val replacement = byteArrayOf(91, 92)
        var current = previous.copyOf()
        var failCommitSync = false
        var rollbackCalls = 0
        val journal = BackupRecoveryJournal(dir, syncDirectory = { syncedDirectory ->
            if (failCommitSync && syncedDirectory.canonicalPath == dir.canonicalPath) {
                error("Simulated COMMITTED directory fsync failure after rename")
            }
        })

        val result = runCatching {
            BackupRecoveryCoordinator(journal).replaceWithRecovery(
                capturePrevious = { previous },
                applyReplacement = {
                    current = replacement.copyOf()
                    failCommitSync = true
                },
                restorePrevious = {
                    rollbackCalls++
                    current = it.copyOf()
                },
            )
        }

        assertTrue(result.isFailure)
        assertEquals(0, rollbackCalls)
        assertArrayEquals(replacement, current)
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(dir).inspect())
        // A new coordinator must never replay the old snapshot under a visible COMMITTED.
        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            error("COMMITTED must not restore the old snapshot")
        }
        assertArrayEquals(replacement, current)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }

    @Test
    fun `unreadable marker after replacement fails closed without destructive rollback`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val previous = byteArrayOf(71)
        val replacement = byteArrayOf(99)
        var current = previous.copyOf()
        var rollbackCalls = 0

        val result = runCatching {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).replaceWithRecovery(
                capturePrevious = { previous },
                applyReplacement = {
                    current = replacement.copyOf()
                    File(dir, "state").writeText("corrupt marker")
                    error("Simulated storage failure with unknown journal state")
                },
                restorePrevious = {
                    rollbackCalls++
                    current = it.copyOf()
                },
            )
        }

        assertTrue(result.isFailure)
        assertEquals(0, rollbackCalls)
        assertArrayEquals(replacement, current)
        assertTrue(runCatching { BackupRecoveryJournal(dir).inspect() }.isFailure)
        val restart = runCatching {
            BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
                error("Corrupt journal must block recovery")
            }
        }
        assertTrue(restart.isFailure)
        assertTrue(dir.exists())
    }


    @Test
    fun `startup replay with ambiguous committed sync never replays old data twice`() = runBlocking {
        val dir = File(temp.root, "recovery")
        val before = byteArrayOf(81, 82)
        BackupRecoveryJournal(dir).prepare(before)
        var current = byteArrayOf(99)
        var replayCount = 0

        // The restore has already completed when COMMITTED is renamed. A failed directory
        // fsync must not make a later startup restore the pre-image a second time.
        val failingJournal = BackupRecoveryJournal(dir, syncDirectory = { syncedDirectory ->
            if (syncedDirectory.canonicalPath == dir.canonicalPath) {
                error("Simulated COMMITTED fsync failure during startup recovery")
            }
        })
        val firstAttempt = runCatching {
            BackupRecoveryCoordinator(failingJournal).recoverBeforeDataAccess { archive ->
                current = archive.copyOf()
                replayCount++
            }
        }
        assertTrue(firstAttempt.isFailure)
        assertEquals(1, replayCount)
        assertArrayEquals(before, current)
        assertEquals(BackupRecoveryJournal.State.Committed, BackupRecoveryJournal(dir).inspect())

        BackupRecoveryCoordinator(BackupRecoveryJournal(dir)).recoverBeforeDataAccess {
            error("COMMITTED recovery must only clean up, not replay")
        }
        assertEquals(1, replayCount)
        assertArrayEquals(before, current)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(dir).inspect())
    }


    @Test
    fun `startup fails closed when retired journal is a symlink even if cleanup errors are normally ignored`() = runBlocking {
        val outside = File(temp.root, "outside-retired")
        assertTrue(outside.mkdir())
        val sentinel = File(outside, "keep.txt")
        sentinel.writeText("preserve")
        Files.createSymbolicLink(File(temp.root, "recovery.retired").toPath(), outside.toPath())
        var restored = false

        val result = runCatching {
            BackupRecoveryCoordinator(BackupRecoveryJournal(File(temp.root, "recovery")))
                .recoverBeforeDataAccess { restored = true }
        }

        assertTrue(result.isFailure)
        assertFalse(restored)
        assertEquals("preserve", sentinel.readText())
    }

}
