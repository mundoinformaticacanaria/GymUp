package com.mundoinformaticacanaria.gymup.feature.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineDetail
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    trainingRepository: TrainingRepository,
    masterCatalogRepository: MasterCatalogRepository,
    exerciseCatalogRepository: ExerciseCatalogRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val routines by trainingRepository.observeRoutines().collectAsStateWithLifecycle(initialValue = emptyList())
    val types by masterCatalogRepository.observeSessionTypes().collectAsStateWithLifecycle(initialValue = emptyList())
    val activeExercises by exerciseCatalogRepository.observeActiveExercises().collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<RoutineDetail?>(null) }
    var createName by remember { mutableStateOf("") }
    var createDescription by remember { mutableStateOf("") }
    var createTypeId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedId, refresh) {
        detail = selectedId?.let { trainingRepository.getRoutineDetail(it) }
    }

    fun action(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { refresh += 1; message = null }
                .onFailure { message = it.message ?: "No se pudo aplicar el cambio." }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rutinas") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nueva rutina", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(createName, { createName = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(createDescription, { createDescription = it }, label = { Text("Descripción opcional") }, modifier = Modifier.fillMaxWidth())
                        Text("Tipo sugerido opcional")
                        FilterChip(selected = createTypeId == null, onClick = { createTypeId = null }, label = { Text("Ninguno") })
                        types.forEach { type -> FilterChip(selected = createTypeId == type.id, onClick = { createTypeId = type.id }, label = { Text(type.name) }) }
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching { trainingRepository.createRoutine(createName, createTypeId, createDescription) }
                                        .onSuccess { id ->
                                            selectedId = id
                                            createName = ""
                                            createDescription = ""
                                            createTypeId = null
                                            refresh += 1
                                        }
                                        .onFailure { message = it.message }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Crear rutina") }
                    }
                }
            }

            item { Text("Rutinas guardadas", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(routines, key = { _, routine -> routine.id }) { _, routine ->
                Card(onClick = { selectedId = routine.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(routine.name, style = MaterialTheme.typography.titleMedium)
                        routine.description?.let { Text(it) }
                    }
                }
            }

            detail?.let { current ->
                item {
                    RoutineEditor(
                        detail = current,
                        types = types.map { it.id to it.name },
                        activeExercises = activeExercises.map { Triple(it.id, it.nameEs, it.nameEn) },
                        message = message,
                        onUpdate = { name, typeId, description -> action { trainingRepository.updateRoutine(current.routine.id, name, typeId, description) } },
                        onDelete = {
                            scope.launch {
                                runCatching { trainingRepository.deleteRoutine(current.routine.id) }
                                    .onSuccess { selectedId = null; detail = null }
                                    .onFailure { message = it.message }
                            }
                        },
                        onAdd = { exerciseId -> action { trainingRepository.addRoutineExercise(current.routine.id, exerciseId) } },
                        onRemove = { exerciseId -> action { trainingRepository.deleteRoutineExercise(current.routine.id, exerciseId) } },
                        onReorder = { ids -> action { trainingRepository.reorderRoutineExercises(current.routine.id, ids) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutineEditor(
    detail: RoutineDetail,
    types: List<Pair<String, String>>,
    activeExercises: List<Triple<String, String, String>>,
    message: String?,
    onUpdate: (String, String?, String) -> Unit,
    onDelete: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    var name by remember(detail.routine.name) { mutableStateOf(detail.routine.name) }
    var description by remember(detail.routine.description) { mutableStateOf(detail.routine.description.orEmpty()) }
    var typeId by remember(detail.routine.suggestedSessionTypeId) { mutableStateOf(detail.routine.suggestedSessionTypeId) }
    val presentIds = detail.exercises.map { it.exerciseId }.toSet()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Editar rutina", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
            Text("Tipo sugerido")
            FilterChip(selected = typeId == null, onClick = { typeId = null }, label = { Text("Ninguno") })
            types.forEach { (id, label) -> FilterChip(selected = typeId == id, onClick = { typeId = id }, label = { Text(label) }) }
            Button(onClick = { onUpdate(name, typeId, description) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar rutina") }

            Text("Ejercicios", style = MaterialTheme.typography.titleSmall)
            detail.exercises.forEachIndexed { index, exercise ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${index + 1}. ${exercise.nameEs} · ${exercise.nameEn}${if (!exercise.isActive) " · Desactivado" else ""}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = index > 0, onClick = {
                            val ids = detail.exercises.map { it.exerciseId }.toMutableList()
                            val id = ids.removeAt(index)
                            ids.add(index - 1, id)
                            onReorder(ids)
                        }) { Text("↑") }
                        TextButton(enabled = index < detail.exercises.lastIndex, onClick = {
                            val ids = detail.exercises.map { it.exerciseId }.toMutableList()
                            val id = ids.removeAt(index)
                            ids.add(index + 1, id)
                            onReorder(ids)
                        }) { Text("↓") }
                        TextButton(onClick = { onRemove(exercise.exerciseId) }) { Text("Quitar") }
                    }
                }
            }
            Text("Añadir ejercicio")
            activeExercises.filterNot { it.first in presentIds }.forEach { exercise ->
                TextButton(onClick = { onAdd(exercise.first) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${exercise.second} · ${exercise.third}")
                }
            }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Eliminar rutina") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
