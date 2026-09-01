package com.mundoinformaticacanaria.gymup.feature.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRepository
import com.mundoinformaticacanaria.gymup.domain.usecase.FilterRoutineExercisesUseCase
import com.mundoinformaticacanaria.gymup.domain.usecase.SaveRoutineUseCase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorScreen(
    routineId: String?,
    routineRepository: RoutineRepository,
    masterCatalogRepository: MasterCatalogRepository,
    exerciseCatalogRepository: ExerciseCatalogRepository,
    saveRoutineUseCase: SaveRoutineUseCase,
    filterRoutineExercisesUseCase: FilterRoutineExercisesUseCase,
    onFinished: () -> Unit,
    onBack: () -> Unit,
) {
    val editorViewModel: RoutineEditorViewModel = viewModel(
        key = "routine-editor-${routineId ?: "new"}",
        factory = RoutineEditorViewModel.Factory(
            routineId = routineId,
            routineRepository = routineRepository,
            masterCatalogRepository = masterCatalogRepository,
            exerciseCatalogRepository = exerciseCatalogRepository,
            saveRoutine = saveRoutineUseCase,
            filterExercises = filterRoutineExercisesUseCase,
        ),
    )
    val state by editorViewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(state.finishedRoutineId, state.deleted) {
        if (state.finishedRoutineId != null || state.deleted) onFinished()
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Eliminar rutina") },
            text = { Text("Se eliminará la rutina. Las sesiones ya creadas no cambiarán.") },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancelar") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        editorViewModel.delete()
                    },
                ) { Text("Eliminar") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (routineId == null) "Nueva rutina" else "Editar rutina") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Cancelar") } },
                actions = {
                    if (routineId != null) {
                        TextButton(onClick = { showDeleteConfirmation = true }) { Text("Eliminar") }
                    }
                },
            )
        },
        bottomBar = {
            if (!state.isLoading) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.step != RoutineEditorStep.BASICS) {
                            OutlinedButton(
                                onClick = editorViewModel::previousStep,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isSaving,
                            ) { Text("Anterior") }
                        }
                        Button(
                            onClick = if (state.step == RoutineEditorStep.SUMMARY) {
                                editorViewModel::save
                            } else {
                                editorViewModel::nextStep
                            },
                            modifier = Modifier.weight(1f),
                            enabled = state.canContinue && !state.isSaving,
                        ) {
                            Text(
                                when {
                                    state.isSaving -> "Guardando…"
                                    state.step == RoutineEditorStep.SUMMARY -> "Guardar"
                                    else -> "Siguiente"
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoutineStepHeader(
                    current = state.step,
                    onSelect = editorViewModel::selectStep,
                )
                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (state.step) {
                        RoutineEditorStep.BASICS -> RoutineBasicsStep(
                            state = state,
                            onNameChange = editorViewModel::updateName,
                            onDescriptionChange = editorViewModel::updateDescription,
                            onSessionTypeSelected = editorViewModel::selectSessionType,
                        )
                        RoutineEditorStep.EXERCISES -> RoutineExerciseSelectionStep(
                            state = state,
                            onQueryChange = editorViewModel::updateQuery,
                            onMuscleGroupSelected = editorViewModel::selectMuscleGroup,
                            onAddExercise = editorViewModel::addExercise,
                        )
                        RoutineEditorStep.SUMMARY -> RoutineSummaryStep(
                            state = state,
                            onMoveExercise = editorViewModel::moveExercise,
                            onRemoveExercise = editorViewModel::removeExercise,
                        )
                    }
                }
            }
        }
    }
}
