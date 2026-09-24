package com.mundoinformaticacanaria.gymup.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ListPositionTest {
    @Test
    fun `swaps first and last without moving intermediate items`() {
        val items = listOf("A", "B", "C", "D")

        val result = items.swapPositions(fromIndex = 0, toIndex = 3)

        assertEquals(listOf("D", "B", "C", "A"), result)
    }

    @Test
    fun `swaps two intermediate positions only`() {
        val items = listOf("A", "B", "C", "D", "E")

        val result = items.swapPositions(fromIndex = 1, toIndex = 3)

        assertEquals(listOf("A", "D", "C", "B", "E"), result)
    }

    @Test
    fun `selecting current position leaves order unchanged`() {
        val items = listOf("A", "B", "C")

        val result = items.swapPositions(fromIndex = 1, toIndex = 1)

        assertSame(items, result)
        assertEquals(listOf("A", "B", "C"), result)
    }
}
