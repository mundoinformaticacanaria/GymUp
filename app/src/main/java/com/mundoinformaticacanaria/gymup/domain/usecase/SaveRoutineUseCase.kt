package com.mundoinformaticacanaria.gymup.domain.usecase

import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogItem
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRepository
import com.mundoinformaticacanaria.gymup.core.util.normalizeName

data class RoutineDraft(
    val id: String?,
    val name: String,
    val suggestedSessionTypeId: String?,
    val description: String,
    val orderedExerciseIds: List<String>,
)

class SaveRoutineUseCase(
    private val routineRepository: RoutineRepository,
) {
    suspend operator fun invoke(draft: RoutineDraft): String {
        require(draft.name.isNotBlank()) { "El nombre de rutina es obligatorio" }
        require(draft.orderedExerciseIds.size == draft.orderedExerciseIds.distinct().size) {
            "Una rutina no puede contener el mismo ejercicio dos veces"
        }
        return routineRepository.saveRoutine(
            routineId = draft.id,
            name = draft.name,
            suggestedSessionTypeId = draft.suggestedSessionTypeId,
            description = draft.description,
            orderedExerciseIds = draft.orderedExerciseIds,
        )
    }
}

class FilterRoutineExercisesUseCase {
    operator fun invoke(
        exercises: List<ExerciseCatalogItem>,
        query: String,
        muscleGroupId: String?,
        excludedExerciseIds: Set<String>,
    ): List<ExerciseCatalogItem> {
        val normalizedQuery = normalizeName(query)
        return exercises.asSequence()
            .filterNot { it.id in excludedExerciseIds }
            .filter { muscleGroupId == null || it.muscleGroupId == muscleGroupId }
            .filter { exercise ->
                normalizedQuery.isBlank() ||
                    normalizeName(exercise.nameEs).contains(normalizedQuery) ||
                    normalizeName(exercise.nameEn).contains(normalizedQuery)
            }
            .sortedWith(
                compareByDescending<ExerciseCatalogItem> { it.isFavorite }
                    .thenBy { normalizeName(it.nameEs) }
                    .thenBy { normalizeName(it.nameEn) },
            )
            .toList()
    }
}
