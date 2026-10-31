package com.mundoinformaticacanaria.gymup.data.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local coordination primitive used by DefaultAppContainer for backup and domain writes.
 *
 * Every domain mutation (Room, private image files and DataStore) must use the SAME instance
 * before this can provide a global barrier. The backup path must hold [withExclusiveBackup]
 * across pre-image capture, journal publication, replacement and recovery.
 *
 * Do not acquire [withDomainWrite] recursively from [withExclusiveBackup]: Mutex is not
 * reentrant. Backup must call its underlying unwrapped data sources while holding the barrier.
 * This class is wired into AppContainer in PR #57 but does not lock readers, protect
 * other processes or establish filesystem durability. It is not a completed #56 gate.
 */
internal class BackupDomainWriteBarrier {
    private val mutex = Mutex()
    // Protected by mutex. A failed hot-import recovery must not allow another mutation
    // to overwrite the pre-image needed for the next successful recovery attempt.
    private var recoveryRequired = false

    /** Guard one complete domain mutation, including related Room/file/preferences writes. */
    suspend fun <T> withDomainWrite(block: suspend () -> T): T = mutex.withLock {
        ensureRecovered()
        block()
    }

    /** Guard the full backup operation against all cooperating domain writers. */
    suspend fun <T> withExclusiveBackup(block: suspend () -> T): T = mutex.withLock { block() }

    /** These methods are only called from inside [withExclusiveBackup], with mutex held. */
    internal fun ensureRecovered() {
        check(!recoveryRequired) {
            "Recuperación de backup pendiente: reinicia GymUp antes de modificar los datos"
        }
    }

    internal fun markRecoveryRequired() {
        recoveryRequired = true
    }

    internal fun markRecovered() {
        recoveryRequired = false
    }
}
