package com.mundoinformaticacanaria.gymup.domain.usecase

import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrainingRulesTest {
    @Test
    fun exerciseStatusIsDerivedOnlyFromRealSeries() {
        assertEquals(ExerciseExecutionStatus.NOT_PERFORMED, deriveExerciseStatus(emptyList()))
        assertEquals(ExerciseExecutionStatus.NOT_PERFORMED, deriveExerciseStatus(listOf(false, false)))
        assertEquals(ExerciseExecutionStatus.PARTIAL, deriveExerciseStatus(listOf(true, false)))
        assertEquals(ExerciseExecutionStatus.COMPLETED, deriveExerciseStatus(listOf(true, true)))
    }

    @Test
    fun sessionResultKeepsPartialExecutions() {
        assertEquals(SessionExecutionResult.NOT_STARTED, deriveSessionExecutionResult(emptyList()))
        assertEquals(
            SessionExecutionResult.PARTIAL,
            deriveSessionExecutionResult(listOf(ExerciseExecutionStatus.COMPLETED, ExerciseExecutionStatus.PARTIAL)),
        )
        assertEquals(
            SessionExecutionResult.COMPLETED,
            deriveSessionExecutionResult(listOf(ExerciseExecutionStatus.COMPLETED, ExerciseExecutionStatus.COMPLETED)),
        )
    }

    @Test
    fun automaticNameUsesBusinessOrder() {
        assertEquals("Jueves 20/08/2026 S2", buildAutoSessionName(LocalDate.of(2026, 8, 20), 2))
    }

    @Test
    fun realDataRequiresExplicitActualValue() {
        assertFalse(hasRealData(null, null, null))
        assertTrue(hasRealData(10.0, null, null))
        assertTrue(hasRealData(null, 10, null))
        assertTrue(hasRealData(null, null, 2))
    }
}
