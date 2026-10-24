package com.mundoinformaticacanaria.gymup.feature.exercises

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.domain.repository.CatalogMaintenanceRepository
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionPreview
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseDeletionResult
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseMaintenanceInput
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import kotlinx.coroutines.launch

internal enum class ExerciseEditorSection { IDENTITY, CONFIGURATION, DEFAULTS }

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

private enum class ExerciseConfigSelector { GROUP, EQUIPMENT, LOAD_MODE, MEASUREMENT_UNIT }

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
    val groups by masterCatalogRepository.observeMuscleGroups().collectAsStateWithLifecycle(initialValue = emptyList())
    val equipment by masterCatalogRepository.observeEquipment().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var loaded by remember(exerciseId) { mutableStateOf(exerciseId == null) }
    var nameEs by remember(exerciseId) { mutableStateOf("") }
    var nameEn by remember(exerciseId) { mutableStateOf("") }
    var muscleGroupId by remember(exerciseId) { mutableStateOf<String?>(null) }
    var equipmentId by remember(exerciseId) { mutableStateOf<String?>(null) }
    var loadMode by remember(exerciseId) { mutableStateOf(LoadMode.KG_TOTAL) }
    var measurementUnit by remember(exerciseId) { mutableStateOf(MeasurementUnit.REPETITIONS) }
    var rirRequired by remember(exerciseId) { mutableStateOf(true) }
    var initialSetsText by remember(exerciseId) { mutableStateOf("") }
    var initialLoadText by remember(exerciseId) { mutableStateOf("") }
    var initialMeasurementText by remember(exerciseId) { mutableStateOf("") }
    var description by remember(exerciseId) { mutableStateOf("") }
    var error by remember(exerciseId) { mutableStateOf<String?>(null) }
    var deletionPreview by remember { mutableStateOf<ExerciseDeletionPreview?>(null) }
    var savedExerciseId by remember { mutableStateOf<String?>(null) }
    var selectedSection by remember(exerciseId) { mutableStateOf(ExerciseEditorSection.IDENTITY) }
    var expandedSelector by remember(exerciseId) { mutableStateOf<ExerciseConfigSelector?>(null) }
    var saveInProgress by remember(exerciseId) { mutableStateOf(false) }
    BackHandler(enabled = saveInProgress) { /* Keep the editor alive while saving. */ }

    LaunchedEffect(exerciseId) {
        if (exerciseId != null) {
            runCatching { requireNotNull(maintenanceRepository.getExercise(exerciseId)) { "Ejercicio inexistente" } }
                .onSuccess { exercise ->
                    nameEs = exercise.nameEs
                    nameEn = exercise.nameEn
                    muscleGroupId = exercise.muscleGroupId
                    equipmentId = exercise.equipmentId
                    loadMode = exercise.loadMode
                    measurementUnit = exercise.measurementUnit
                    rirRequired = exercise.rirRequired
                    initialSetsText = exercise.initialSetCount?.toString().orEmpty()
                    initialLoadText = exercise.initialLoad?.toSpanishDecimal().orEmpty()
                    initialMeasurementText = exercise.initialMeasurement?.toString().orEmpty()
                    description = exercise.description.orEmpty()
                    loaded = true
                }
                .onFailure { error = it.message ?: "No se pudo cargar el ejercicio" }
        }
    }
    LaunchedEffect(groups, loaded) {
        if (loaded && muscleGroupId == null && groups.isNotEmpty()) muscleGroupId = groups.first().id
    }

    fun buildInput(): ExerciseMaintenanceInput {
        val selectedGroup = requireNotNull(muscleGroupId) { "Selecciona un grupo muscular" }
        val initialSets = initialSetsText.optionalInt("Series iniciales")
        val initialLoad = initialLoadText.optionalDecimal("Carga inicial")
        val initialMeasurement = initialMeasurementText.optionalInt("Medición inicial")
        require(initialSets != null || (initialLoad == null && initialMeasurement == null)) {
            "Indica las series iniciales cuando configures carga o medición inicial."
        }
        return ExerciseMaintenanceInput(
            nameEs = nameEs,
            nameEn = nameEn,
            muscleGroupId = selectedGroup,
            equipmentId = equipmentId,
            loadMode = loadMode,
            measurementUnit = measurementUnit,
            rirRequired = rirRequired,
            initialSetCount = initialSets,
            initialLoad = initialLoad,
            initialMeasurement = initialMeasurement,
            description = description,
        )
    }

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
                        onClick = {
                            if (!saveInProgress) {
                                saveInProgress = true
                                scope.launch {
                                    try {
                                        val input = buildInput()
                                        val id = if (exerciseId == null) maintenanceRepository.createExercise(input)
                                            else {
                                                maintenanceRepository.updateExercise(exerciseId, input)
                                                exerciseId
                                            }
                                        error = null
                                        savedExerciseId = id
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "No se pudo guardar el ejercicio"
                                    } finally {
                                        saveInProgress = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saveInProgress && nameEs.isNotBlank() && nameEn.isNotBlank() && muscleGroupId != null,
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
                            onClick = { selectedSection = section; expandedSelector = null },
                            enabled = !saveInProgress,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(section.label() + if (selectedSection == section) " · seleccionada" else "")
                        }
                    }
                }
            }
            when (selectedSection) {
                ExerciseEditorSection.IDENTITY -> {
                    item { Text("Identidad", style = MaterialTheme.typography.titleLarge) }
                    item { OutlinedTextField(nameEs, { nameEs = it }, label = { Text("Nombre español") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(nameEn, { nameEn = it }, label = { Text("English name") }, modifier = Modifier.fillMaxWidth()) }
                    item {
                        OutlinedTextField(
                            description, { description = it },
                            label = { Text("Descripción / instrucciones") },
                            modifier = Modifier.fillMaxWidth(), minLines = 3,
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
                                onClick = { expandedSelector = if (expandedSelector == ExerciseConfigSelector.GROUP) null else ExerciseConfigSelector.GROUP },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Grupo muscular: " + groupName) }
                            OutlinedButton(
                                onClick = { expandedSelector = if (expandedSelector == ExerciseConfigSelector.EQUIPMENT) null else ExerciseConfigSelector.EQUIPMENT },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Equipo: " + equipmentName) }
                            OutlinedButton(
                                onClick = { expandedSelector = if (expandedSelector == ExerciseConfigSelector.LOAD_MODE) null else ExerciseConfigSelector.LOAD_MODE },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Modalidad de carga: " + loadMode.label()) }
                            OutlinedButton(
                                onClick = { expandedSelector = if (expandedSelector == ExerciseConfigSelector.MEASUREMENT_UNIT) null else ExerciseConfigSelector.MEASUREMENT_UNIT },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Unidad: " + measurementUnit.label()) }
                            FilterChip(
                                selected = rirRequired,
                                onClick = { rirRequired = !rirRequired },
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
                                        onClick = { muscleGroupId = group.id; expandedSelector = null },
                                        label = { Text(group.name) },
                                    )
                                }
                            }
                        }
                        ExerciseConfigSelector.EQUIPMENT -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = equipmentId == null,
                                    onClick = { equipmentId = null; expandedSelector = null },
                                    label = { Text("Sin equipo") },
                                )
                                equipment.forEach { entry ->
                                    FilterChip(
                                        selected = equipmentId == entry.id,
                                        onClick = { equipmentId = entry.id; expandedSelector = null },
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
                                        onClick = { loadMode = mode; expandedSelector = null },
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
                                        onClick = { measurementUnit = unit; expandedSelector = null },
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
                    item { OutlinedTextField(initialSetsText, { initialSetsText = it }, label = { Text("Series iniciales (opcional)") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(initialLoadText, { initialLoadText = it }, label = { Text("Carga inicial (opcional, admite coma)") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(initialMeasurementText, { initialMeasurementText = it }, label = { Text("Medición inicial (opcional)") }, modifier = Modifier.fillMaxWidth()) }
                    if (exerciseId != null) {
                        item {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { maintenanceRepository.previewExerciseDeletion(exerciseId) }
                                            .onSuccess { deletionPreview = it }
                                            .onFailure { error = it.message ?: "No se pudo preparar la baja" }
                                    }
                                },
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
                        onClick = { selectedSection = next; expandedSelector = null },
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
                    savedExerciseId = null
                    onSaved(savedId)
                }) { Text("Volver al maestro") }
            },
        )
    }

    deletionPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { deletionPreview = null },
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
            dismissButton = { TextButton(onClick = { deletionPreview = null }) { Text("Cancelar") } },
            confirmButton = {
                Button(onClick = {
                    val id = exerciseId ?: return@Button
                    deletionPreview = null
                    scope.launch {
                        runCatching { maintenanceRepository.deleteExercise(id, confirmRoutineRemoval = true) }
                            .onSuccess { result ->
                                when (result) {
                                    ExerciseDeletionResult.Deactivated -> onDeleted()
                                    is ExerciseDeletionResult.Deleted -> onDeleted()
                                }
                            }
                            .onFailure { error = it.message ?: "No se pudo eliminar el ejercicio" }
                    }
                }) { Text(if (preview.willDeactivate) "Dar de baja" else "Eliminar definitivamente") }
            },
        )
    }
}

private fun String.optionalInt(label: String): Int? {
    val clean = trim()
    if (clean.isEmpty()) return null
    return clean.toIntOrNull() ?: error("$label debe ser un número entero")
}

private fun String.optionalDecimal(label: String): Double? {
    val clean = trim()
    if (clean.isEmpty()) return null
    return clean.replace(',', '.').toDoubleOrNull() ?: error("$label debe ser un número válido")
}

private fun Double.toSpanishDecimal(): String = toString().replace('.', ',')

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
