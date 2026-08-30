package com.mundoinformaticacanaria.gymup.feature.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RoutineStepHeader(
    current: RoutineEditorStep,
    onSelect: (RoutineEditorStep) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepChip("1. Datos", RoutineEditorStep.BASICS, current, onSelect, Modifier.weight(1f))
        StepChip("2. Ejercicios", RoutineEditorStep.EXERCISES, current, onSelect, Modifier.weight(1f))
        StepChip("3. Resumen", RoutineEditorStep.SUMMARY, current, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun StepChip(
    label: String,
    step: RoutineEditorStep,
    current: RoutineEditorStep,
    onSelect: (RoutineEditorStep) -> Unit,
    modifier: Modifier,
) {
    FilterChip(
        selected = current == step,
        onClick = { onSelect(step) },
        label = { Text(label) },
        modifier = modifier,
    )
}

@Composable
internal fun RoutineBasicsStep(
    state: RoutineEditorUiState,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSessionTypeSelected: (String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Datos básicos", style = MaterialTheme.typography.titleLarge)
        Text("Ponle un nombre. El tipo y la descripción son opcionales.")
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text("Nombre") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = { Text("Descripción opcional") },
            minLines = 2,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Tipo de sesión sugerido", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.suggestedSessionTypeId == null,
                    onClick = { onSessionTypeSelected(null) },
                    label = { Text("Ninguno") },
                )
            }
            items(state.sessionTypes, key = { it.id }) { type ->
                FilterChip(
                    selected = state.suggestedSessionTypeId == type.id,
                    onClick = { onSessionTypeSelected(type.id) },
                    label = { Text(type.name) },
                )
            }
        }
    }
}

@Composable
internal fun RoutineExerciseSelectionStep(
    state: RoutineEditorUiState,
    onQueryChange: (String) -> Unit,
    onMuscleGroupSelected: (String?) -> Unit,
    onAddExercise: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Selecciona ejercicios", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Buscar por nombre") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.muscleGroupId == null,
                    onClick = { onMuscleGroupSelected(null) },
                    label = { Text("Todos") },
                )
            }
            items(state.muscleGroups, key = { it.id }) { group ->
                FilterChip(
                    selected = state.muscleGroupId == group.id,
                    onClick = { onMuscleGroupSelected(if (state.muscleGroupId == group.id) null else group.id) },
                    label = { Text(group.name) },
                )
            }
        }
        Text(
            "${state.selectedExercises.size} seleccionado(s) · ${state.availableExercises.size} disponible(s)",
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.availableExercises.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    if (state.activeExercises.isEmpty()) {
                        "No hay ejercicios activos disponibles."
                    } else {
                        "No hay más resultados con esta búsqueda."
                    },
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.availableExercises, key = { it.id }) { exercise ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(exercise.nameEs, style = MaterialTheme.typography.titleMedium)
                                Text(exercise.nameEn, style = MaterialTheme.typography.bodyMedium)
                                if (exercise.isFavorite) Text("Favorito", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = { onAddExercise(exercise.id) }) { Text("Añadir") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RoutineSummaryStep(
    state: RoutineEditorUiState,
    onMoveExercise: (String, Int) -> Unit,
    onRemoveExercise: (String) -> Unit,
) {
    val typeName = state.sessionTypes.firstOrNull { it.id == state.suggestedSessionTypeId }?.name ?: "Sin tipo sugerido"
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Revisa y ordena", style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(state.name, style = MaterialTheme.typography.titleMedium)
                Text(typeName)
                if (state.description.isNotBlank()) Text(state.description)
            }
        }
        Text("Ejercicios (${state.selectedExercises.size})", style = MaterialTheme.typography.titleMedium)
        if (state.selectedExercises.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("Puedes guardar una rutina vacía o volver para añadir ejercicios.", Modifier.padding(16.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.selectedExercises, key = { it.id }) { exercise ->
                    val index = state.selectedExercises.indexOfFirst { it.id == exercise.id }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${index + 1}. ${exercise.nameEs}", style = MaterialTheme.typography.titleMedium)
                            Text(exercise.nameEn)
                            if (!exercise.isActive) {
                                Text("Desactivado", color = MaterialTheme.colorScheme.error)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    enabled = index > 0,
                                    onClick = { onMoveExercise(exercise.id, -1) },
                                ) { Text("Subir") }
                                TextButton(
                                    enabled = index < state.selectedExercises.lastIndex,
                                    onClick = { onMoveExercise(exercise.id, 1) },
                                ) { Text("Bajar") }
                                TextButton(onClick = { onRemoveExercise(exercise.id) }) { Text("Quitar") }
                            }
                        }
                    }
                }
            }
        }
    }
}
