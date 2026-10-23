package com.mundoinformaticacanaria.gymup.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogActionGateTest {
    @Test
    fun duplicateWriteIsRejectedUntilFirstFinishes() {
        val gate = CatalogActionGate()

        assertTrue(gate.tryStart())
        assertTrue(gate.running)
        assertFalse(gate.tryStart())
        assertTrue(gate.running)
    }

    @Test
    fun nextWriteCanStartAfterCompletion() {
        val gate = CatalogActionGate()

        assertTrue(gate.tryStart())
        gate.finish()

        assertFalse(gate.running)
        assertTrue(gate.tryStart())
        assertTrue(gate.running)
        gate.finish()
        assertFalse(gate.running)
    }
}
