package com.mundoinformaticacanaria.gymup.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageManager
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MissingRirException
import com.mundoinformaticacanaria.gymup.domain.repository.SessionDetail
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingExercise
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingRepository
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingSet
import com.mundoinformaticacanaria.gymup.feature.exercises.ExerciseImageGallery
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    trainingRepository: TrainingRepository,
    masterCatalogRepository: MasterCatalogRepository,
    exerciseCatalogRepository: ExerciseCatalogRepository,
    exerciseImageManager: ExerciseImageManager,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val types by masterCatalogRepository.observeSessionTypes().collectAsStateWithLifecycle(initialValue = emptyList())
    val activeExercises by exerciseCatalogRepository.observeActiveExercises().collectAsStateWithLifecycle(initialValue = emptyList())
    var refresh by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<SessionDetail?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showExercisePicker by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId, refresh) { detail = trainingRepository.getSessionDetail(sessionId) }

    fun action(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { refresh += 1; message = null }
                .onFailure { error ->
                    message = if (error is MissingRirException) {
                        "Falta RIR obligatorio en ${error.missingSetIds.size} serie(s)."
                    } else error.message ?: "No se pudo aplicar el cambio."
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.summary?.name ?: "Sesión") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        val current = detail
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) { Text("Cargando sesión…") }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SessionMetadataEditor(
                    detail = current,
                    typeOptions = types.map { it.id to it.name },
                    message = message,
                    onSaveMetadata = { typeId, name, note -> action { trainingRepository.updateSessionMetadata(sessionId, typeId, name, note) } },
                    onChangePosition = { date, order -> action { trainingRepository.changeSessionPosition(sessionId, date, order) } },
                    onRecalculate = { action { trainingRepository.recalculateObjectives(sessionId) } },
                    onStateChange = { state -> action { trainingRepository.setOperationalState(sessionId, state) } },
                    onFinalize = { action { trainingRepository.finalizeSession(sessionId) } },
                    onDelete = {
                        scope.launch {
                            runCatching { trainingRepository.deleteSession(sessionId) }
                                .onSuccess { onDeleted() }
                                .onFailure { message = it.message }
                        }
                    },
                )
            }

            item {
                Button(onClick = { showExercisePicker = !showExercisePicker }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showExercisePicker) "Cerrar ejercicios" else "Añadir ejercicio")
                }
                if (showExercisePicker) {
                    val present = current.exercises.map { it.exerciseId }.toSet()
                    activeExercises.filterNot { it.id in present }.forEach { exercise ->
                        TextButton(
                            onClick = { action { trainingRepository.addExercise(sessionId, exercise.id) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("${exercise.nameEs} · ${exercise.nameEn}") }
                    }
                }
            }

            itemsIndexed(current.exercises, key = { _, exercise -> exercise.id }) { index, exercise ->
                ExerciseEditor(
                    exercise = exercise,
                    exerciseImageManager = exerciseImageManager,
                    canMoveUp = index > 0,
                    canMoveDown = index < current.exercises.lastIndex,
                    onMoveUp = {
                        val ids = current.exercises.map { it.id }.toMutableList()
                        val id = ids.removeAt(index)
                        ids.add(index - 1, id)
                        action { trainingRepository.reorderExercises(sessionId, ids) }
                    },
                    onMoveDown = {
                        val ids = current.exercises.map { it.id }.toMutableList()
                        val id = ids.removeAt(index)
                        ids.add(index + 1, id)
                        action { trainingRepository.reorderExercises(sessionId, ids) }
                    },
                    onSaveMeta = { rest, note, reason -> action { trainingRepository.updateExerciseMeta(exercise.id, rest, note, reason) } },
                    onAddSet = { action { trainingRepository.addSet(exercise.id) } },
                    onDeleteExercise = { action { trainingRepository.deleteExercise(exercise.id) } },
                    onFinalizeExercise = { action { trainingRepository.finalizeExercise(exercise.id) } },
                    onSaveTargets = { set, load, measurement, mode, unit -> action { trainingRepository.updateSetTargets(set.id, load, measurement, mode, unit) } },
                    onSaveActual = { set, load, measurement, rir -> action { trainingRepository.updateSetActual(set.id, load, measurement, rir) } },
                    onSetRest = { set, rest -> action { trainingRepository.updateSetRest(set.id, rest) } },
                    onFulfilled = { set -> action { trainingRepository.fulfillSet(set.id) } },
                    onDeleteSet = { set -> action { trainingRepository.deleteSet(set.id) } },
                )
            }
        }
    }
}

@Composable
private fun SessionMetadataEditor(
    detail: SessionDetail,
    typeOptions: List<Pair<String, String>>,
    message: String?,
    onSaveMetadata: (String, String, String) -> Unit,
    onChangePosition: (LocalDate, Int) -> Unit,
    onRecalculate: () -> Unit,
    onStateChange: (SessionOperationalState) -> Unit,
    onFinalize: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(detail.summary.name) { mutableStateOf(if (detail.isAutoName) "" else detail.summary.name) }
    var note by remember(detail.generalNote) { mutableStateOf(detail.generalNote.orEmpty()) }
    var typeId by remember(detail.sessionTypeId) { mutableStateOf(detail.sessionTypeId) }
    var dateText by remember(detail.summary.date) { mutableStateOf(detail.summary.date.toString()) }
    var orderText by remember(detail.summary.orderInDay) { mutableStateOf(detail.summary.orderInDay.toString()) }
    var confirmRecalculate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasActualData = detail.exercises.any { exercise -> exercise.sets.any { it.actualConfirmed } }

    if (confirmRecalculate) {
        AlertDialog(
            onDismissRequest = { confirmRecalculate = false },
            title = { Text("Recalcular objetivos") },
            text = { Text("Se sustituirán los objetivos actuales por los calculados desde la nueva posición temporal. Esta acción no modifica datos reales.") },
            confirmButton = {
                TextButton(onClick = { confirmRecalculate = false; onRecalculate() }) { Text("Recalcular") }
            },
            dismissButton = { TextButton(onClick = { confirmRecalculate = false }) { Text("Cancelar") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar sesión") },
            text = { Text("La sesión y todos sus ejercicios y series se eliminarán definitivamente.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${detail.summary.operationalState.label()} · ${detail.summary.executionResult.label()}", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Nombre (vacío = automático)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Nota general") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Text("Tipo")
            typeOptions.forEach { (id, label) ->
                FilterChip(selected = typeId == id, onClick = { typeId = id }, label = { Text(label) })
            }
            Button(onClick = { onSaveMetadata(typeId, name, note) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar datos") }
            OutlinedTextField(dateText, { dateText = it }, label = { Text("Fecha") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(orderText, { orderText = it }, label = { Text("Orden") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
                    val order = orderText.toIntOrNull()
                    if (date != null && order != null && order > 0) onChangePosition(date, order)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Guardar fecha/orden") }
            if (!hasActualData) {
                TextButton(onClick = { confirmRecalculate = true }, modifier = Modifier.fillMaxWidth()) { Text("Recalcular objetivos") }
            }
            Text("Estado operativo")
            SessionOperationalState.entries.forEach { state ->
                FilterChip(selected = detail.summary.operationalState == state, onClick = { onStateChange(state) }, label = { Text(state.label()) })
            }
            Button(onClick = onFinalize, modifier = Modifier.fillMaxWidth()) { Text("Finalizar sesión") }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Eliminar sesión") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ExerciseEditor(
    exercise: TrainingExercise,
    exerciseImageManager: ExerciseImageManager,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSaveMeta: (Int?, String, String?) -> Unit,
    onAddSet: () -> Unit,
    onDeleteExercise: () -> Unit,
    onFinalizeExercise: () -> Unit,
    onSaveTargets: (TrainingSet, Double?, Int?, LoadMode, MeasurementUnit) -> Unit,
    onSaveActual: (TrainingSet, Double?, Int?, Int?) -> Unit,
    onSetRest: (TrainingSet, Int?) -> Unit,
    onFulfilled: (TrainingSet) -> Unit,
    onDeleteSet: (TrainingSet) -> Unit,
) {
    var note by remember(exercise.note) { mutableStateOf(exercise.note.orEmpty()) }
    var rest by remember(exercise.exerciseRestSeconds) { mutableStateOf(exercise.exerciseRestSeconds?.toString().orEmpty()) }
    var reason by remember(exercise.incompleteReason) { mutableStateOf(exercise.incompleteReason) }
    val reasons = listOf("Máquina ocupada", "Falta de tiempo", "Molestia", "Fatiga", "Decisión de la sesión", "Otro")

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${exercise.position}. ${exercise.nameEs} · ${exercise.nameEn}", style = MaterialTheme.typography.titleMedium)
            Text("${exercise.muscleGroupName}${exercise.equipmentName?.let { " · $it" }.orEmpty()} · ${exercise.status.label()}")
            exercise.description?.let {
                Text("Instrucciones", style = MaterialTheme.typography.labelLarge)
                Text(it)
            }
            ExerciseImageGallery(
                exerciseId = exercise.exerciseId,
                manager = exerciseImageManager,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = canMoveUp, onClick = onMoveUp) { Text("↑ Subir") }
                TextButton(enabled = canMoveDown, onClick = onMoveDown) { Text("↓ Bajar") }
            }
            TextButton(onClick = onDeleteExercise, modifier = Modifier.fillMaxWidth()) { Text("Quitar ejercicio") }
            OutlinedTextField(rest, { rest = it }, label = { Text("Descanso ejercicio (s)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Nota del ejercicio") }, modifier = Modifier.fillMaxWidth())
            if (exercise.status != ExerciseExecutionStatus.COMPLETED) {
                Text("Motivo opcional")
                reasons.forEach { item -> FilterChip(selected = reason == item, onClick = { reason = if (reason == item) null else item }, label = { Text(item) }) }
            }
            TextButton(onClick = { onSaveMeta(rest.toIntOrNull(), note, reason) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar ejercicio") }

            exercise.sets.forEach { set ->
                SetEditor(
                    set = set,
                    rirRequired = exercise.rirRequired,
                    onSaveTargets = onSaveTargets,
                    onSaveActual = onSaveActual,
                    onSetRest = onSetRest,
                    onFulfilled = onFulfilled,
                    onDelete = onDeleteSet,
                )
            }
            Button(onClick = onAddSet, modifier = Modifier.fillMaxWidth()) { Text("Añadir serie") }
            TextButton(onClick = onFinalizeExercise, modifier = Modifier.fillMaxWidth()) { Text("Finalizar ejercicio") }
        }
    }
}

@Composable
private fun SetEditor(
    set: TrainingSet,
    rirRequired: Boolean,
    onSaveTargets: (TrainingSet, Double?, Int?, LoadMode, MeasurementUnit) -> Unit,
    onSaveActual: (TrainingSet, Double?, Int?, Int?) -> Unit,
    onSetRest: (TrainingSet, Int?) -> Unit,
    onFulfilled: (TrainingSet) -> Unit,
    onDelete: (TrainingSet) -> Unit,
) {
    var targetLoad by remember(set.id, set.targetLoad) { mutableStateOf(set.targetLoad?.displayDecimal().orEmpty()) }
    var targetMeasurement by remember(set.id, set.targetMeasurement) { mutableStateOf(set.targetMeasurement?.toString().orEmpty()) }
    var actualLoad by remember(set.id, set.actualLoad) { mutableStateOf(set.actualLoad?.displayDecimal().orEmpty()) }
    var actualMeasurement by remember(set.id, set.actualMeasurement) { mutableStateOf(set.actualMeasurement?.toString().orEmpty()) }
    var rest by remember(set.id, set.restOverrideSeconds) { mutableStateOf(set.restOverrideSeconds?.toString().orEmpty()) }
    var mode by remember(set.id, set.loadMode) { mutableStateOf(set.loadMode) }
    var unit by remember(set.id, set.measurementUnit) { mutableStateOf(set.measurementUnit) }
    var rir by remember(set.id, set.rir) { mutableStateOf(set.rir) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Serie ${set.position}${if (set.actualConfirmed) " · Realizada" else " · Pendiente"}", style = MaterialTheme.typography.titleSmall)
            Text("Modalidad de carga")
            LoadMode.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.label()) }) }
            Text("Medición")
            MeasurementUnit.entries.forEach { item -> FilterChip(selected = unit == item, onClick = { unit = item }, label = { Text(item.label()) }) }
            OutlinedTextField(targetLoad, { targetLoad = it }, label = { Text("Carga objetivo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(targetMeasurement, { targetMeasurement = it }, label = { Text("Objetivo") }, modifier = Modifier.fillMaxWidth())
            TextButton(
                onClick = { onSaveTargets(set, targetLoad.parseDecimal(), targetMeasurement.toIntOrNull(), mode, unit) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Guardar objetivo") }
            OutlinedTextField(actualLoad, { actualLoad = it }, label = { Text("Carga real") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(actualMeasurement, { actualMeasurement = it }, label = { Text("Real") }, modifier = Modifier.fillMaxWidth())
            Text("RIR${if (rirRequired) " obligatorio" else " opcional"}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf<Int?>(null, 0, 1, 2).forEach { item ->
                    FilterChip(selected = rir == item, onClick = { rir = item }, label = { Text(item?.toString() ?: "—") })
                }
            }
            Button(
                onClick = { onSaveActual(set, actualLoad.parseDecimal(), actualMeasurement.toIntOrNull(), rir) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Guardar real") }
            Button(onClick = { onFulfilled(set) }, modifier = Modifier.fillMaxWidth()) { Text("Cumplido") }
            OutlinedTextField(rest, { rest = it }, label = { Text("Descanso serie (s)") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { onSetRest(set, rest.toIntOrNull()) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar descanso") }
            TextButton(onClick = { onDelete(set) }, modifier = Modifier.fillMaxWidth()) { Text("Eliminar serie") }
        }
    }
}

private fun ExerciseExecutionStatus.label(): String = when (this) {
    ExerciseExecutionStatus.NOT_PERFORMED -> "No realizado"
    ExerciseExecutionStatus.PARTIAL -> "Parcial"
    ExerciseExecutionStatus.COMPLETED -> "Completado"
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

private fun String.parseDecimal(): Double? = trim().takeIf(String::isNotEmpty)?.replace(',', '.')?.toDoubleOrNull()
private fun Double.displayDecimal(): String = toString().replace('.', ',')
