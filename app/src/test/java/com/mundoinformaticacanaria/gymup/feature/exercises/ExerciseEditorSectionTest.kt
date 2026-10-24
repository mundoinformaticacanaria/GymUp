package com.mundoinformaticacanaria.gymup.feature.exercises

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseEditorSectionTest {
    @Test
    fun progressesFromIdentityThroughConfigurationToDefaults() {
        assertEquals(ExerciseEditorSection.CONFIGURATION, ExerciseEditorSection.IDENTITY.next())
        assertEquals(ExerciseEditorSection.DEFAULTS, ExerciseEditorSection.CONFIGURATION.next())
        assertNull(ExerciseEditorSection.DEFAULTS.next())
    }

    @Test
    fun everyEditorSectionIsReachable() {
        val visited = mutableListOf(ExerciseEditorSection.IDENTITY)
        while (true) {
            val next = visited.last().next() ?: break
            visited += next
        }
        assertEquals(ExerciseEditorSection.entries.toList(), visited)
    }
}
