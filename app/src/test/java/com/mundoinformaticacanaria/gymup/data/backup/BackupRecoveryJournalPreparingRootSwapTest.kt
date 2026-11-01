package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A synced but replaced regular staging directory must never become PREPARED. */
class BackupRecoveryJournalPreparingRootSwapTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `prepare refuses a different regular staging journal after final directory sync`() {
        val active = File(temp.root, "recovery")
        val staging = File(temp.root, "recovery.preparing")
        val held = File(temp.root, "held-preparing")
        val foreignRoot = File(temp.root, "foreign-prepared")
        val original = byteArrayOf(11, 12, 13)
        val foreign = byteArrayOf(21, 22, 23)
        BackupRecoveryJournal(foreignRoot).prepare(foreign)

        var swapped = false
        val journal = BackupRecoveryJournal(
            active,
            syncDirectory = { synced ->
                if (!swapped && synced.absolutePath == staging.absolutePath &&
                    File(staging, "state").isFile
                ) {
                    assertTrue(staging.renameTo(held))
                    assertTrue(foreignRoot.renameTo(staging))
                    swapped = true
                }
            },
        )

        assertTrue(runCatching { journal.prepare(original) }.isFailure)
        assertTrue(swapped)
        assertFalse(active.exists())
        assertArrayEquals(original, File(held, "previous.zip").readBytes())
        assertArrayEquals(foreign, File(staging, "previous.zip").readBytes())

        // Repair only the synthetic fixture; no alien pre-image was published.
        assertTrue(staging.renameTo(foreignRoot))
        assertTrue(held.renameTo(staging))
        BackupRecoveryJournal(active).cleanupAbandonedPreparation()
        BackupRecoveryJournal(active).prepare(original)
        assertArrayEquals(
            original,
            (BackupRecoveryJournal(active).inspect() as BackupRecoveryJournal.State.Prepared)
                .previousArchive,
        )
        assertArrayEquals(foreign, File(foreignRoot, "previous.zip").readBytes())
    }
}
