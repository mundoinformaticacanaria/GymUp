package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * File-key checks are a best-effort defence against deterministic regular-path swaps.
 * They do not make a path-based delete atomic, nor prove Android filesystem durability.
 */
class BackupRecoveryJournalCleanupIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `abandoned cleanup preserves another regular directory swapped after preflight`() {
        val active = File(temp.root, "recovery")
        val preparing = File(temp.root, "recovery.preparing")
        val held = File(temp.root, "held-preparing")
        val foreign = File(temp.root, "foreign-preparing")
        val originalBytes = byteArrayOf(1, 2, 3)
        val foreignBytes = byteArrayOf(4, 5, 6)
        assertTrue(preparing.mkdir())
        assertTrue(foreign.mkdir())
        File(preparing, "previous.zip").writeBytes(originalBytes)
        File(foreign, "previous.zip").writeBytes(foreignBytes)

        var swapped = false
        val journal = BackupRecoveryJournal(
            active,
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
        assertArrayEquals(originalBytes, File(held, "previous.zip").readBytes())
        assertArrayEquals(foreignBytes, File(preparing, "previous.zip").readBytes())

        assertTrue(preparing.renameTo(foreign))
        assertTrue(held.renameTo(preparing))
        BackupRecoveryJournal(active).cleanupAbandonedPreparation()
        assertFalse(preparing.exists())
        assertArrayEquals(foreignBytes, File(foreign, "previous.zip").readBytes())
    }

    @Test
    fun `abandoned cleanup preserves a different regular file swapped after preflight`() {
        val active = File(temp.root, "recovery")
        val preparing = File(temp.root, "recovery.preparing")
        val held = File(temp.root, "held-archive")
        val originalBytes = byteArrayOf(7, 8, 9)
        val foreignBytes = byteArrayOf(9, 8, 7)
        assertTrue(preparing.mkdir())
        val archive = File(preparing, "previous.zip")
        archive.writeBytes(originalBytes)

        var swapped = false
        val journal = BackupRecoveryJournal(
            active,
            beforeArtifactDelete = { entry ->
                if (!swapped) {
                    assertTrue(entry.renameTo(held))
                    entry.writeBytes(foreignBytes)
                    swapped = true
                }
            },
        )
        assertTrue(runCatching { journal.cleanupAbandonedPreparation() }.isFailure)
        assertTrue(swapped)
        assertArrayEquals(originalBytes, held.readBytes())
        assertArrayEquals(foreignBytes, archive.readBytes())

        assertTrue(archive.delete())
        assertTrue(held.renameTo(archive))
        BackupRecoveryJournal(active).cleanupAbandonedPreparation()
        assertFalse(preparing.exists())
    }
}
