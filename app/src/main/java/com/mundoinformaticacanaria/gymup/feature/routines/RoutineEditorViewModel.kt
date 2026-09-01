package com.mundoinformaticacanaria.gymup.feature.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogItem
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogItem
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRepository
import com.mundoinformaticacanaria.gymup.domain.usecase.FilterRoutineExercisesUseCase
import com.mundoinformaticacanaria.gymup.domain.usecase.RoutineDraft
import com.mundoinformaticacanaria.gymup.domain.usecase.SaveRoutineUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RoutineEditorStep { BASICS, EXERCISES, SUMMARY }

data class RoutineExerciseDraft(
    val id: String,
    val nameEs: String,
    val nameEn: String,
    val isActive: Boolean,
)

data class RoutineEditorUiState(
    val routineId: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val name: String = "",
    val description: String = "",
    val suggestedSessionTypeId: String? = null,
    val sessionTypes: List<CatalogItem> = emptyList(),
    val muscleGroups: List<CatalogItem> = emptyList(),
    val activeExercises: List<ExerciseCatalogItem> = emptyList(),
    val availableExercises: List<ExerciseCatalogItem> = emptyList(),
    val selectedExercises: List<RoutineExerciseDraft> = emptyList(),
    val query: String = "",
    val muscleGroupId: String? = null,
    val step: RoutineEditorStep = RoutineEditorStep.BASICS,
    val error: String? = null,
    val finishedRoutineId: String? = null,
    val deleted: Boolean = false,
) {
    val canContinue: Boolean get() = name.isNotBlank()
}

class RoutineEditorViewModel(
    private val routineId: String?,
    private val routineRepository: RoutineRepository,
    masterCatalogRepository: MasterCatalogRepository,
    exerciseCatalogRepository: ExerciseCatalogRepository,
    private val saveRoutine: SaveRoutineUseCase,
    private val filterExercises: FilterRoutineExercisesUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RoutineEditorUiState(routineId = routineId))
    val uiState: StateFlow<RoutineEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                masterCatalogRepository.observeSessionTypes(),
                masterCatalogRepository.observeMuscleGroups(),
                exerciseCatalogRepository.observeActiveExercises(),
            ) { types, groups, exercises -> Triple(types, groups, exercises) }
                .collect { (types, groups, exercises) ->
                    updateState { state ->
                        state.copy(
                            sessionTypes = types,
                            muscleGroups = groups,
                            activeExercises = exercises,
                        )
                    }
                }
        }
        viewModelScope.launch {
            if (routineId == null) {
                updateState { it.copy(isLoading = false) }
                return@launch
            }
            runCatching { requireNotNull(routineRepository.getRoutineDetail(routineId)) }
                .onSuccess { detail ->
                    updateState { state ->
                        state.copy(
                            isLoading = false,
                            name = detail.routine.name,
                            description = detail.routine.description.orEmpty(),
                            suggestedSessionTypeId = detail.routine.suggestedSessionTypeId,
                            selectedExercises = detail.exercises.map { exercise ->
                                RoutineExerciseDraft(
                                    id = exercise.exerciseId,
                                    nameEs = exercise.nameEs,
                                    nameEn = exercise.nameEn,
                                    isActive = exercise.isActive,
                                )
                            },
                        )
                    }
                }
                .onFailure { error ->
                    updateState {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "No se pudo cargar la rutina.",
                        )
                    }
                }
        }
    }

    fun updateName(value: String) = updateState { it.copy(name = value, error = null) }

    fun updateDescription(value: String) = updateState { it.copy(description = value, error = null) }

    fun selectSessionType(id: String?) = updateState {
        it.copy(suggestedSessionTypeId = id, error = null)
    }

    fun selectStep(step: RoutineEditorStep) = updateState { state ->
        if (step == RoutineEditorStep.BASICS || state.canContinue) state.copy(step = step, error = null) else state
    }

    fun nextStep() = updateState { state ->
        when {
            !state.canContinue -> state.copy(error = "Escribe un nombre para continuar.")
            state.step == RoutineEditorStep.BASICS -> state.copy(step = RoutineEditorStep.EXERCISES, error = null)
            state.step == RoutineEditorStep.EXERCISES -> state.copy(step = RoutineEditorStep.SUMMARY, error = null)
            else -> state
        }
    }

    fun previousStep() = updateState { state ->
        state.copy(
            step = when (state.step) {
                RoutineEditorStep.BASICS -> RoutineEditorStep.BASICS
                RoutineEditorStep.EXERCISES -> RoutineEditorStep.BASICS
                RoutineEditorStep.SUMMARY -> RoutineEditorStep.EXERCISES
            },
            error = null,
        )
    }

    fun updateQuery(value: String) = updateState { it.copy(query = value) }

    fun selectMuscleGroup(id: String?) = updateState { it.copy(muscleGroupId = id) }

    fun addExercise(exerciseId: String) = updateState { state ->
        val exercise = state.activeExercises.firstOrNull { it.id == exerciseId } ?: return@updateState state
        state.copy(
            selectedExercises = state.selectedExercises + RoutineExerciseDraft(
                id = exercise.id,
                nameEs = exercise.nameEs,
                nameEn = exercise.nameEn,
                isActive = true,
            ),
            error = null,
        )
    }

    fun removeExercise(exerciseId: String) = updateState { state ->
        state.copy(selectedExercises = state.selectedExercises.filterNot { it.id == exerciseId }, error = null)
    }

    fun moveExercise(exerciseId: String, offset: Int) = updateState { state ->
        val currentIndex = state.selectedExercises.indexOfFirst { it.id == exerciseId }
        if (currentIndex == -1) return@updateState state
        val targetIndex = (currentIndex + offset).coerceIn(state.selectedExercises.indices)
        if (targetIndex == currentIndex) return@updateState state
        val reordered = state.selectedExercises.toMutableList()
        val item = reordered.removeAt(currentIndex)
        reordered.add(targetIndex, item)
        state.copy(selectedExercises = reordered, error = null)
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        if (!state.canContinue) {
            updateState { it.copy(step = RoutineEditorStep.BASICS, error = "El nombre de rutina es obligatorio.") }
            return
        }
        updateState { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                saveRoutine(
                    RoutineDraft(
                        id = routineId,
                        name = _uiState.value.name,
                        suggestedSessionTypeId = _uiState.value.suggestedSessionTypeId,
                        description = _uiState.value.description,
                        orderedExerciseIds = _uiState.value.selectedExercises.map { it.id },
                    ),
                )
            }.onSuccess { savedId ->
                updateState { it.copy(isSaving = false, finishedRoutineId = savedId) }
            }.onFailure { error ->
                updateState {
                    it.copy(isSaving = false, error = error.message ?: "No se pudo guardar la rutina.")
                }
            }
        }
    }

    fun delete() {
        val id = routineId ?: return
        if (_uiState.value.isSaving) return
        updateState { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching { routineRepository.deleteRoutine(id) }
                .onSuccess { updateState { it.copy(isSaving = false, deleted = true) } }
                .onFailure { error ->
                    updateState {
                        it.copy(isSaving = false, error = error.message ?: "No se pudo eliminar la rutina.")
                    }
                }
        }
    }

    private fun updateState(transform: (RoutineEditorUiState) -> RoutineEditorUiState) {
        _uiState.update { current ->
            val updated = transform(current)
            updated.copy(
                availableExercises = filterExercises(
                    exercises = updated.activeExercises,
                    query = updated.query,
                    muscleGroupId = updated.muscleGroupId,
                    excludedExerciseIds = updated.selectedExercises.mapTo(mutableSetOf()) { it.id },
                ),
            )
        }
    }

    class Factory(
        private val routineId: String?,
        private val routineRepository: RoutineRepository,
        private val masterCatalogRepository: MasterCatalogRepository,
        private val exerciseCatalogRepository: ExerciseCatalogRepository,
        private val saveRoutine: SaveRoutineUseCase,
        private val filterExercises: FilterRoutineExercisesUseCase,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RoutineEditorViewModel::class.java))
            return RoutineEditorViewModel(
                routineId = routineId,
                routineRepository = routineRepository,
                masterCatalogRepository = masterCatalogRepository,
                exerciseCatalogRepository = exerciseCatalogRepository,
                saveRoutine = saveRoutine,
                filterExercises = filterExercises,
            ) as T
        }
    }
}
