package com.mundoinformaticacanaria.gymup.feature.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogItem
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionPreview
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceInput
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExerciseEditorUiState(
    val loaded: Boolean = false,
    val nameEs: String = "",
    val nameEn: String = "",
    val muscleGroupId: String? = null,
    val equipmentId: String? = null,
    val loadMode: LoadMode = LoadMode.KG_TOTAL,
    val measurementUnit: MeasurementUnit = MeasurementUnit.REPETITIONS,
    val rirRequired: Boolean = true,
    val initialSetsText: String = "",
    val initialLoadText: String = "",
    val initialMeasurementText: String = "",
    val description: String = "",
    val groups: List<CatalogItem> = emptyList(),
    val equipment: List<CatalogItem> = emptyList(),
    val selectedSection: ExerciseEditorSection = ExerciseEditorSection.IDENTITY,
    val expandedSelector: ExerciseConfigSelector? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    val deletionPreview: ExerciseDeletionPreview? = null,
    val savedExerciseId: String? = null,
    val deleted: Boolean = false,
) {
    val canSave: Boolean get() = loaded && !isBusy &&
        nameEs.isNotBlank() && nameEn.isNotBlank() && muscleGroupId != null

    fun toInput(): ExerciseMaintenanceInput {
        val selectedGroup = requireNotNull(muscleGroupId) { "Selecciona un grupo muscular" }
        val initialSets = initialSetsText.optionalEditorInt("Series iniciales")
        val initialLoad = initialLoadText.optionalEditorDecimal("Carga inicial")
        val initialMeasurement = initialMeasurementText.optionalEditorInt("Medición inicial")
        require(initialSets != null || (initialLoad == null && initialMeasurement == null)) {
            "Indica las series iniciales cuando configures carga o medición inicial."
        }
        return ExerciseMaintenanceInput(
            nameEs = nameEs, nameEn = nameEn, muscleGroupId = selectedGroup,
            equipmentId = equipmentId, loadMode = loadMode, measurementUnit = measurementUnit,
            rirRequired = rirRequired, initialSetCount = initialSets,
            initialLoad = initialLoad, initialMeasurement = initialMeasurement,
            description = description,
        )
    }
}

private fun String.optionalEditorInt(label: String): Int? {
    val value = trim()
    if (value.isEmpty()) return null
    return value.toIntOrNull() ?: error("$label debe ser un número entero")
}

private fun String.optionalEditorDecimal(label: String): Double? {
    val value = trim()
    if (value.isEmpty()) return null
    return value.replace(',', '.').toDoubleOrNull() ?: error("$label debe ser un número válido")
}

class ExerciseEditorViewModel(
    private val exerciseId: String?,
    private val maintenanceRepository: CatalogMaintenanceRepository,
    masterCatalogRepository: MasterCatalogRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExerciseEditorUiState(loaded = exerciseId == null))
    val uiState: StateFlow<ExerciseEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            masterCatalogRepository.observeMuscleGroups().collect { groups ->
                change { state ->
                    state.copy(
                        groups = groups,
                        muscleGroupId = state.muscleGroupId ?: if (state.loaded) groups.firstOrNull()?.id else null,
                    )
                }
            }
        }
        viewModelScope.launch {
            masterCatalogRepository.observeEquipment().collect { equipment ->
                change { it.copy(equipment = equipment) }
            }
        }
        if (exerciseId != null) {
            viewModelScope.launch {
                try {
                    val exercise = requireNotNull(maintenanceRepository.getExercise(exerciseId)) {
                        "Ejercicio inexistente"
                    }
                    change { state ->
                        state.copy(
                            loaded = true, nameEs = exercise.nameEs, nameEn = exercise.nameEn,
                            muscleGroupId = exercise.muscleGroupId, equipmentId = exercise.equipmentId,
                            loadMode = exercise.loadMode, measurementUnit = exercise.measurementUnit,
                            rirRequired = exercise.rirRequired,
                            initialSetsText = exercise.initialSetCount?.toString().orEmpty(),
                            initialLoadText = exercise.initialLoad?.toString()?.replace('.', ',').orEmpty(),
                            initialMeasurementText = exercise.initialMeasurement?.toString().orEmpty(),
                            description = exercise.description.orEmpty(), error = null,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // A failed lookup must never expose an empty editable form for an existing ID.
                    change { it.copy(loaded = false, error = failure.message ?: "No se pudo cargar el ejercicio") }
                }
            }
        }
    }

    fun updateNameEs(value: String) = edit { it.copy(nameEs = value) }
    fun updateNameEn(value: String) = edit { it.copy(nameEn = value) }
    fun updateDescription(value: String) = edit { it.copy(description = value) }
    fun selectMuscleGroup(id: String) = edit { it.copy(muscleGroupId = id, expandedSelector = null) }
    fun selectEquipment(id: String?) = edit { it.copy(equipmentId = id, expandedSelector = null) }
    fun selectLoadMode(mode: LoadMode) = edit { it.copy(loadMode = mode, expandedSelector = null) }
    fun selectMeasurementUnit(unit: MeasurementUnit) = edit { it.copy(measurementUnit = unit, expandedSelector = null) }
    fun toggleRir() = edit { it.copy(rirRequired = !it.rirRequired) }
    fun updateInitialSets(value: String) = edit { it.copy(initialSetsText = value) }
    fun updateInitialLoad(value: String) = edit { it.copy(initialLoadText = value) }
    fun updateInitialMeasurement(value: String) = edit { it.copy(initialMeasurementText = value) }
    fun selectSection(section: ExerciseEditorSection) = edit {
        it.copy(selectedSection = section, expandedSelector = null)
    }
    fun toggleSelector(selector: ExerciseConfigSelector) = edit {
        it.copy(expandedSelector = if (it.expandedSelector == selector) null else selector)
    }
    fun dismissDeletionPreview() = edit { it.copy(deletionPreview = null) }

    fun save() {
        val snapshot = _uiState.value
        if (!snapshot.canSave) return
        change { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val input = snapshot.toInput()
                val id = if (exerciseId == null) maintenanceRepository.createExercise(input) else {
                    maintenanceRepository.updateExercise(exerciseId, input)
                    exerciseId
                }
                change { it.copy(savedExerciseId = id, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                change { it.copy(error = failure.message ?: "No se pudo guardar el ejercicio") }
            } finally {
                change { it.copy(isBusy = false) }
            }
        }
    }

    fun previewDeletion() {
        val id = exerciseId ?: return
        if (_uiState.value.isBusy) return
        change { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val preview = maintenanceRepository.previewExerciseDeletion(id)
                change { it.copy(deletionPreview = preview) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                change { it.copy(error = failure.message ?: "No se pudo preparar la baja") }
            } finally {
                change { it.copy(isBusy = false) }
            }
        }
    }

    fun confirmDeletion() {
        val id = exerciseId ?: return
        if (_uiState.value.isBusy || _uiState.value.deletionPreview == null) return
        change { it.copy(isBusy = true, deletionPreview = null, error = null) }
        viewModelScope.launch {
            try {
                maintenanceRepository.deleteExercise(id, confirmRoutineRemoval = true)
                change { it.copy(deleted = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                change { it.copy(error = failure.message ?: "No se pudo eliminar el ejercicio") }
            } finally {
                change { it.copy(isBusy = false) }
            }
        }
    }

    private fun edit(transform: (ExerciseEditorUiState) -> ExerciseEditorUiState) {
        change { state -> if (state.isBusy) state else transform(state).copy(error = null) }
    }

    private fun change(transform: (ExerciseEditorUiState) -> ExerciseEditorUiState) {
        _uiState.update(transform)
    }

    class Factory(
        private val exerciseId: String?,
        private val maintenanceRepository: CatalogMaintenanceRepository,
        private val masterCatalogRepository: MasterCatalogRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ExerciseEditorViewModel::class.java))
            return ExerciseEditorViewModel(exerciseId, maintenanceRepository, masterCatalogRepository) as T
        }
    }
}
