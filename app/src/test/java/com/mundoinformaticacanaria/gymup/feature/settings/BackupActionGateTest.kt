package com.mundoinformaticacanaria.gymup.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupActionGateTest {
    @Test
    fun secondOperationIsRejectedWhileImportOrExportRuns() {
        val gate = BackupActionGate()

        assertTrue(gate.tryStart())
        assertTrue(gate.running)
        assertFalse(gate.tryStart())
        assertTrue(gate.running)
    }

    @Test
    fun operationsCanResumeAfterCompletion() {
        val gate = BackupActionGate()

        assertTrue(gate.tryStart())
        gate.finish()
        assertFalse(gate.running)
        assertTrue(gate.tryStart())
        gate.finish()
        assertFalse(gate.running)
    }
}
