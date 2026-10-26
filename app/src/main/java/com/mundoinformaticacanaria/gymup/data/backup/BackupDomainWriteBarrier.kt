package com.mundoinformaticacanaria.gymup.data.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local coordination primitive for a future consistent backup snapshot and replace-all.
 *
 * Every domain mutation (Room, private image files and DataStore) must use the SAME instance
 * before this can provide a global barrier. The backup path must hold [withExclusiveBackup]
 * across pre-image capture, journal publication, replacement and recovery.
 *
 * Do not acquire [withDomainWrite] recursively from [withExclusiveBackup]: Mutex is not
 * reentrant. Backup must call its underlying unwrapped data sources while holding the barrier.
 * This class is NOT wired into AppContainer/repositories yet and does not protect other
 * processes or filesystem durability. It must not be treated as a completed #56 gate.
 */
internal class BackupDomainWriteBarrier {
    private val mutex = Mutex()

    /** Guard one complete domain mutation, including related Room/file/preferences writes. */
    suspend fun <T> withDomainWrite(block: suspend () -> T): T = mutex.withLock { block() }

    /** Guard the full backup operation against all cooperating domain writers. */
    suspend fun <T> withExclusiveBackup(block: suspend () -> T): T = mutex.withLock { block() }
}
