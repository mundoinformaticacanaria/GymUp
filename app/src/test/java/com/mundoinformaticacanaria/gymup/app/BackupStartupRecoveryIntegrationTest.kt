package com.mundoinformaticacanaria.gymup.app

import com.mundoinformaticacanaria.gymup.data.backup.BackupRecoveryCoordinator
import com.mundoinformaticacanaria.gymup.data.backup.BackupRecoveryJournal
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the real journal/coordinator behind the startup sequencing primitive.
 * This does not wire AppContainer or prove durability after an Android process death.
 */
class BackupStartupRecoveryIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `prepared journal is recovered before seeding and startup is not repeated`() = runBlocking {
        val directory = File(temp.root, "recovery")
        val previous = byteArrayOf(1, 2, 3, 4)
        BackupRecoveryJournal(directory).prepare(previous)
        val events = mutableListOf<String>()
        val coordinator = BackupRecoveryCoordinator(BackupRecoveryJournal(directory))
        val gate = BackupStartupGate(
            recover = {
                coordinator.recoverBeforeDataAccess { bytes ->
                    assertArrayEquals(previous, bytes)
                    events += "restore"
                }
            },
            seed = { events += "seed" },
        )

        gate.awaitReady()
        gate.awaitReady()

        assertEquals(listOf("restore", "seed"), events)
        assertEquals(BackupRecoveryJournal.State.Absent, BackupRecoveryJournal(directory).inspect())
    }

    @Test
    fun `corrupt journal prevents seeding and retry only proceeds after repair`() = runBlocking {
        val directory = File(temp.root, "recovery")
        val previous = byteArrayOf(10, 11, 12)
        BackupRecoveryJournal(directory).prepare(previous)
        File(directory, "previous.zip").writeBytes(byteArrayOf(99))
        val events = mutableListOf<String>()
        val coordinator = BackupRecoveryCoordinator(BackupRecoveryJournal(directory))
        val gate = BackupStartupGate(
            recover = {
                coordinator.recoverBeforeDataAccess { bytes ->
                    assertArrayEquals(previous, bytes)
                    events += "restore"
                }
            },
            seed = { events += "seed" },
        )

        assertTrue(runCatching { gate.awaitReady() }.isFailure)
        assertTrue(events.isEmpty())
        assertTrue(directory.exists())

        // Simulate an external repair of the archive; the gate must still require recovery.
        File(directory, "previous.zip").writeBytes(previous)
        gate.awaitReady()

        assertEquals(listOf("restore", "seed"), events)
        assertFalse(directory.exists())
    }
}
