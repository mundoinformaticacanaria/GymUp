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
