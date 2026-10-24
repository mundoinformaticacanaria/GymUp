package com.mundoinformaticacanaria.gymup.feature.exercises

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseEditorUiStateTest {
    private fun validState() = ExerciseEditorUiState(
        loaded = true,
        nameEs = "Press",
        nameEn = "Bench press",
        muscleGroupId = "chest",
        equipmentId = "barbell",
        loadMode = LoadMode.KG_TOTAL,
        measurementUnit = MeasurementUnit.REPETITIONS,
        initialSetsText = "3",
        initialLoadText = "12,5",
        initialMeasurementText = "8",
        description = "Banco",
    )

    @Test
    fun retainsAllFieldsAcrossSectionChanges() {
        val state = validState().copy(
            selectedSection = ExerciseEditorSection.DEFAULTS,
            expandedSelector = ExerciseConfigSelector.EQUIPMENT,
        )
        val input = state.toInput()
        assertEquals("Press", input.nameEs)
        assertEquals("Bench press", input.nameEn)
        assertEquals("chest", input.muscleGroupId)
        assertEquals("barbell", input.equipmentId)
        assertEquals(3, input.initialSetCount)
        assertEquals(12.5, input.initialLoad ?: 0.0, 0.001)
        assertEquals(8, input.initialMeasurement)
        assertEquals("Banco", input.description)
    }

    @Test
    fun refusesInitialLoadWithoutSetCount() {
        val state = validState().copy(initialSetsText = "")
        val result = runCatching { state.toInput() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("series iniciales"))
    }

    @Test
    fun rejectsNonFiniteInitialLoadBeforePersistence() {
        listOf("Infinity", "-Infinity", "NaN", "1e309").forEach { invalid ->
            val result = runCatching { validState().copy(initialLoadText = invalid).toInput() }
            assertTrue("Invalid load $invalid must fail", result.isFailure)
        }
        assertEquals(12.5, validState().toInput().initialLoad ?: 0.0, 0.001)
    }

    @Test
    fun repeatedIdenticalErrorsStillAdvanceVisibilityRevision() {
        val first = validState().withFailure("Series iniciales debe ser un número entero")
        // A quick retry may clear and re-emit the same error within one UI frame.
        val second = first.copy(error = null).withFailure("Series iniciales debe ser un número entero")

        assertEquals(first.error, second.error)
        assertEquals(first.errorRevision + 1, second.errorRevision)
        assertTrue(second.errorRevision > first.errorRevision)
    }

    @Test
    fun cannotSaveWithoutRequiredNamesOrGroup() {
        assertTrue(validState().canSave)
        assertTrue(!validState().copy(nameEs = "").canSave)
        assertTrue(!validState().copy(nameEn = "").canSave)
        assertTrue(!validState().copy(muscleGroupId = null).canSave)
        assertTrue(!validState().copy(isBusy = true).canSave)
        // Failed master loads must not allow writing a blank replacement.
        assertTrue(!validState().copy(loaded = false, error = "Ejercicio inexistente").canSave)
    }
}
