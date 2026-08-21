package com.mundoinformaticacanaria.gymup.feature.exercises

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
        return ExerciseMaintenanceInput(
            nameEs = nameEs,
            nameEn = nameEn,
            muscleGroupId = selectedGroup,
            equipmentId = equipmentId,
            loadMode = loadMode,
            measurementUnit = measurementUnit,
            rirRequired = rirRequired,
            initialSetCount = initialSetsText.optionalInt("Series iniciales"),
            initialLoad = initialLoadText.optionalDecimal("Carga inicial"),
            initialMeasurement = initialMeasurementText.optionalInt("Medición inicial"),
            description = description,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (exerciseId == null) "Nuevo ejercicio" else "Editar ejercicio") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
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
            }
            item { OutlinedTextField(nameEs, { nameEs = it }, label = { Text("Nombre español") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(nameEn, { nameEn = it }, label = { Text("English name") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Text("Grupo muscular", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.forEach { group ->
                        FilterChip(
                            selected = muscleGroupId == group.id,
                            onClick = { muscleGroupId = group.id },
                            label = { Text(group.name) },
                        )
                    }
                }
            }
            item {
                Text("Equipo", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = equipmentId == null, onClick = { equipmentId = null }, label = { Text("Sin equipo") })
                    equipment.forEach { item ->
                        FilterChip(
                            selected = equipmentId == item.id,
                            onClick = { equipmentId = item.id },
                            label = { Text(item.name) },
                        )
                    }
                }
            }
            item {
                Text("Modalidad de carga", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LoadMode.entries.forEach { mode ->
                        FilterChip(selected = loadMode == mode, onClick = { loadMode = mode }, label = { Text(mode.label()) })
                    }
                }
            }
            item {
                Text("Unidad de medición", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MeasurementUnit.entries.forEach { unit ->
                        FilterChip(
                            selected = measurementUnit == unit,
                            onClick = { measurementUnit = unit },
                            label = { Text(unit.label()) },
                        )
                    }
                }
            }
            item {
                FilterChip(
                    selected = rirRequired,
                    onClick = { rirRequired = !rirRequired },
                    label = { Text(if (rirRequired) "RIR obligatorio" else "RIR opcional") },
                )
            }
            item { OutlinedTextField(initialSetsText, { initialSetsText = it }, label = { Text("Series iniciales (opcional)") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(initialLoadText, { initialLoadText = it }, label = { Text("Carga inicial (opcional, admite coma)") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(initialMeasurementText, { initialMeasurementText = it }, label = { Text("Medición inicial (opcional)") }, modifier = Modifier.fillMaxWidth()) }
            item {
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("Descripción / instrucciones") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val input = buildInput()
                                if (exerciseId == null) maintenanceRepository.createExercise(input)
                                else {
                                    maintenanceRepository.updateExercise(exerciseId, input)
                                    exerciseId
                                }
                            }.onSuccess(onSaved)
                                .onFailure { error = it.message ?: "No se pudo guardar el ejercicio" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = nameEs.isNotBlank() && nameEn.isNotBlank() && muscleGroupId != null,
                ) { Text("Guardar ejercicio") }
            }
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
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Eliminar / dar de baja") }
                }
            }
        }
    }

    deletionPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { deletionPreview = null },
            title = { Text(if (preview.willDeactivate) "Dar de baja ejercicio" else "Eliminar ejercicio") },
            text = {
                Text(
                    when {
                        preview.willDeactivate ->
                            "El ejercicio tiene ${preview.historicalReferences} referencia(s) histórica(s). No se borrará: se desactivará y el histórico se conservará."
                        preview.requiresRoutineConfirmation ->
                            "El ejercicio no tiene histórico, pero aparece en ${preview.routineReferences} rutina(s). Se eliminará también de esas rutinas."
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
