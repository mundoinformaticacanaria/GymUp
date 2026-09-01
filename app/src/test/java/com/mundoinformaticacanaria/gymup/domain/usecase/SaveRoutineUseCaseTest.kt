package com.mundoinformaticacanaria.gymup.domain.usecase

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveRoutineUseCaseTest {
    private val filterExercises = FilterRoutineExercisesUseCase()

    @Test
    fun `filter excludes selected exercises and applies normalized name and group`() {
        val exercises = listOf(
            exercise("1", "Press banca", "Bench press", "chest"),
            exercise("2", "Remo sentado", "Seated row", "back"),
            exercise("3", "Press militar", "Shoulder press", "shoulder"),
        )

        val result = filterExercises(
            exercises = exercises,
            query = "press",
            muscleGroupId = "shoulder",
            excludedExerciseIds = setOf("1"),
        )

        assertEquals(listOf("3"), result.map { it.id })
    }

    @Test
    fun `filter prioritizes favorites and keeps stable alphabetical order`() {
        val exercises = listOf(
            exercise("1", "Zancada", "Lunge", "legs"),
            exercise("2", "Apertura", "Fly", "chest"),
            exercise("3", "Dominada", "Pull-up", "back", favorite = true),
        )

        val result = filterExercises(exercises, query = "", muscleGroupId = null, excludedExerciseIds = emptySet())

        assertEquals(listOf("3", "2", "1"), result.map { it.id })
    }

    private fun exercise(
        id: String,
        nameEs: String,
        nameEn: String,
        groupId: String,
        favorite: Boolean = false,
    ) = ExerciseCatalogItem(
        id = id,
        nameEs = nameEs,
        nameEn = nameEn,
        muscleGroupId = groupId,
        equipmentId = null,
        loadMode = LoadMode.KG_TOTAL,
        measurementUnit = MeasurementUnit.REPETITIONS,
        rirRequired = true,
        isFavorite = favorite,
    )
}
