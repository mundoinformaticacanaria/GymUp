package com.mundoinformaticacanaria.gymup.feature.exercises

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionPreview
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository

enum class ExerciseEditorSection { IDENTITY, CONFIGURATION, DEFAULTS }

internal fun ExerciseEditorSection.next(): ExerciseEditorSection? = when (this) {
    ExerciseEditorSection.IDENTITY -> ExerciseEditorSection.CONFIGURATION
    ExerciseEditorSection.CONFIGURATION -> ExerciseEditorSection.DEFAULTS
    ExerciseEditorSection.DEFAULTS -> null
}

private fun ExerciseEditorSection.label(): String = when (this) {
    ExerciseEditorSection.IDENTITY -> "Identidad"
    ExerciseEditorSection.CONFIGURATION -> "Configuración"
    ExerciseEditorSection.DEFAULTS -> "Valores iniciales"
}

enum class ExerciseConfigSelector { GROUP, EQUIPMENT, LOAD_MODE, MEASUREMENT_UNIT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseEditorScreen(
    exerciseId: String?,
    maintenanceRepository: CatalogMaintenanceRepository,
    masterCatalogRepository: MasterCatalogRepository,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val editorViewModel: ExerciseEditorViewModel = viewModel(
        key = "exercise-editor-" + (exerciseId ?: "new"),
        factory = ExerciseEditorViewModel.Factory(exerciseId, maintenanceRepository, masterCatalogRepository),
    )
    val state by editorViewModel.uiState.collectAsStateWithLifecycle()
    val groups = state.groups
    val equipment = state.equipment
    val loaded = state.loaded
    val nameEs = state.nameEs
    val nameEn = state.nameEn
    val muscleGroupId = state.muscleGroupId
    val equipmentId = state.equipmentId
    val loadMode = state.loadMode
    val measurementUnit = state.measurementUnit
    val rirRequired = state.rirRequired
    val initialSetsText = state.initialSetsText
    val initialLoadText = state.initialLoadText
    val initialMeasurementText = state.initialMeasurementText
    val description = state.description
    val error = state.error
    val selectedSection = state.selectedSection
    val expandedSelector = state.expandedSelector
    val saveInProgress = state.isBusy
    val fieldsEnabled = loaded && !saveInProgress
    val deletionPreview = state.deletionPreview
    val savedExerciseId = state.savedExerciseId

    // Keep the newly selected section and validation errors visible even after scrolling.
    LaunchedEffect(selectedSection) { listState.scrollToItem(0) }
    LaunchedEffect(error) { if (error != null) listState.scrollToItem(0) }

    BackHandler(enabled = saveInProgress) { /* Wait for the in-flight repository operation. */ }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (exerciseId == null) "Nuevo ejercicio" else "Editar ejercicio") },
                navigationIcon = { TextButton(onClick = onBack, enabled = !saveInProgress) { Text("Cancelar") } },
            )
        },
        bottomBar = {
            if (loaded) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(
                        onClick = editorViewModel::save,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.canSave,
                    ) { Text(if (saveInProgress) "Guardando ejercicio…" else "Guardar ejercicio") }
                }
            }
        },
    ) { padding ->
        if (!loaded) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text(error ?: "Cargando ejercicio…")
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Los cambios del maestro se aplican a usos futuros. Las sesiones existentes conservan sus snapshots.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saveInProgress) Text("Guardando. Espera antes de salir.")
            }
            item {
                Text("Editar por secciones", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExerciseEditorSection.entries.forEach { section ->
                        OutlinedButton(
                            onClick = { editorViewModel.selectSection(section) },
                            enabled = !saveInProgress,
                            modifier = Modifier.fillMaxWidth().semantics { selected = selectedSection == section },
                        ) {
                            Text(section.label() + if (selectedSection == section) " · seleccionada" else "")
                        }
                    }
                }
            }
            when (selectedSection) {
                ExerciseEditorSection.IDENTITY -> {
                    item { Text("Identidad", style = MaterialTheme.typography.titleLarge) }
                    item { OutlinedTextField(nameEs, { editorViewModel.updateNameEs(it) }, label = { Text("Nombre español") }, modifier = Modifier.fillMaxWidth(), enabled = fieldsEnabled) }
                    item { OutlinedTextField(nameEn, { editorViewModel.updateNameEn(it) }, label = { Text("English name") }, modifier = Modifier.fillMaxWidth(), enabled = fieldsEnabled) }
                    item {
                        OutlinedTextField(
                            description, { editorViewModel.updateDescription(it) },
                            label = { Text("Descripción / instrucciones") },
                            modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = fieldsEnabled,
                        )
                    }
                }
                ExerciseEditorSection.CONFIGURATION -> {
                    item {
                        Text("Configuración", style = MaterialTheme.typography.titleLarge)
                        Text("Abre un selector cada vez; los valores elegidos se conservan al cambiar de sección.")
                    }
                    item {
                        val groupName = groups.firstOrNull { it.id == muscleGroupId }?.name ?: "Seleccionar"
                        val equipmentName = equipment.firstOrNull { it.id == equipmentId }?.name ?: "Sin equipo"
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { editorViewModel.toggleSelector(ExerciseConfigSelector.GROUP) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = fieldsEnabled,
                            ) { Text("Grupo muscular: " + groupName) }
                            OutlinedButton(
                                onClick = { editorViewModel.toggleSelector(ExerciseConfigSelector.EQUIPMENT) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = fieldsEnabled,
                            ) { Text("Equipo: " + equipmentName) }
                            OutlinedButton(
                                onClick = { editorViewModel.toggleSelector(ExerciseConfigSelector.LOAD_MODE) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = fieldsEnabled,
                            ) { Text("Modalidad de carga: " + loadMode.label()) }
                            OutlinedButton(
                                onClick = { editorViewModel.toggleSelector(ExerciseConfigSelector.MEASUREMENT_UNIT) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = fieldsEnabled,
                            ) { Text("Unidad: " + measurementUnit.label()) }
                            FilterChip(
                                selected = rirRequired,
                                onClick = { editorViewModel.toggleRir() },
                                enabled = fieldsEnabled,
                                label = { Text(if (rirRequired) "RIR obligatorio" else "RIR opcional") },
                            )
                        }
                    }
                    when (expandedSelector) {
                        ExerciseConfigSelector.GROUP -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                groups.forEach { group ->
                                    FilterChip(
                                        selected = muscleGroupId == group.id,
                                        enabled = fieldsEnabled,
                                        onClick = { editorViewModel.selectMuscleGroup(group.id) },
                                        label = { Text(group.name) },
                                    )
                                }
                            }
                        }
                        ExerciseConfigSelector.EQUIPMENT -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = equipmentId == null,
                                        enabled = fieldsEnabled,
                                    onClick = { editorViewModel.selectEquipment(null) },
                                    label = { Text("Sin equipo") },
                                )
                                equipment.forEach { entry ->
                                    FilterChip(
                                        selected = equipmentId == entry.id,
                                        enabled = fieldsEnabled,
                                        onClick = { editorViewModel.selectEquipment(entry.id) },
                                        label = { Text(entry.name) },
                                    )
                                }
                            }
                        }
                        ExerciseConfigSelector.LOAD_MODE -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LoadMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = loadMode == mode,
                                        enabled = fieldsEnabled,
                                        onClick = { editorViewModel.selectLoadMode(mode) },
                                        label = { Text(mode.label()) },
                                    )
                                }
                            }
                        }
                        ExerciseConfigSelector.MEASUREMENT_UNIT -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                MeasurementUnit.entries.forEach { unit ->
                                    FilterChip(
                                        selected = measurementUnit == unit,
                                        enabled = fieldsEnabled,
                                        onClick = { editorViewModel.selectMeasurementUnit(unit) },
                                        label = { Text(unit.label()) },
                                    )
                                }
                            }
                        }
                        null -> Unit
                    }
                }
                ExerciseEditorSection.DEFAULTS -> {
                    item {
                        Text("Configuración inicial para sesiones nuevas", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Si no existe histórico completado, GymUp creará estas series y precargará carga/medición como objetivos.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item { OutlinedTextField(initialSetsText, { editorViewModel.updateInitialSets(it) }, label = { Text("Series iniciales (opcional)") }, modifier = Modifier.fillMaxWidth(), enabled = fieldsEnabled) }
                    item { OutlinedTextField(initialLoadText, { editorViewModel.updateInitialLoad(it) }, label = { Text("Carga inicial (opcional, admite coma)") }, modifier = Modifier.fillMaxWidth(), enabled = fieldsEnabled) }
                    item { OutlinedTextField(initialMeasurementText, { editorViewModel.updateInitialMeasurement(it) }, label = { Text("Medición inicial (opcional)") }, modifier = Modifier.fillMaxWidth(), enabled = fieldsEnabled) }
                    if (exerciseId != null) {
                        item {
                            OutlinedButton(
                                onClick = editorViewModel::previewDeletion,
                                enabled = !saveInProgress,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Eliminar / dar de baja") }
                        }
                    }
                }
            }
            selectedSection.next()?.let { next ->
                item {
                    OutlinedButton(
                        onClick = { editorViewModel.selectSection(next) },
                        enabled = !saveInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Siguiente: " + next.label()) }
                }
            }
            item {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !saveInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancelar y volver") }
            }
        }
    }

    savedExerciseId?.let { savedId ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Ejercicio guardado") },
            text = {
                Text(
                    "La ficha y su configuración inicial se han guardado correctamente. " +
                        "Se usarán para nuevas sesiones cuando no exista histórico completado previo.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    onSaved(savedId)
                }) { Text("Volver al maestro") }
            },
        )
    }

    deletionPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { editorViewModel.dismissDeletionPreview() },
            title = { Text(if (preview.willDeactivate) "Dar de baja ejercicio" else "Eliminar ejercicio") },
            text = {
                Text(
                    when {
                        preview.willDeactivate -> "El ejercicio tiene ${preview.historicalReferences} referencia(s) histórica(s). No se borrará: se desactivará y el histórico se conservará."
                        preview.requiresRoutineConfirmation -> "El ejercicio no tiene histórico, pero aparece en ${preview.routineReferences} rutina(s). Se eliminará también de esas rutinas."
                        else -> "El ejercicio no tiene histórico ni referencias en rutinas y se eliminará definitivamente."
                    },
                )
            },
            dismissButton = { TextButton(onClick = { editorViewModel.dismissDeletionPreview() }, enabled = !saveInProgress) { Text("Cancelar") } },
            confirmButton = {
                Button(onClick = editorViewModel::confirmDeletion, enabled = !saveInProgress) { Text(if (preview.willDeactivate) "Dar de baja" else "Eliminar definitivamente") }
            },
        )
    }
}

private fun LoadMode.label(): String = when (this) {
    LoadMode.KG_TOTAL -> "kg total"
    LoadMode.KG_PER_HAND -> "kg/mano"
    LoadMode.KG_PER_SIDE -> "kg/lado"
    LoadMode.BODYWEIGHT -> "peso corporal"
    LoadMode.BODYWEIGHT_PLUS_LOAD -> "peso corporal + X kg"
    LoadMode.BODYWEIGHT_MINUS_ASSISTANCE -> "peso corporal - X kg asistencia"
    LoadMode.NO_WEIGHT -> "sin peso"
}

private fun MeasurementUnit.label(): String = when (this) {
    MeasurementUnit.REPETITIONS -> "repeticiones"
    MeasurementUnit.REPETITIONS_PER_SIDE -> "repeticiones/lado"
    MeasurementUnit.SECONDS -> "segundos"
    MeasurementUnit.SECONDS_PER_SIDE -> "segundos/lado"
}
