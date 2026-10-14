package com.mundoinformaticacanaria.gymup.feature.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    routineRepository: RoutineRepository,
    onNewRoutine: () -> Unit,
    onOpenRoutine: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: RoutineListViewModel = viewModel(
        factory = RoutineListViewModel.Factory(routineRepository),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rutinas") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewRoutine,
                icon = { Text("+") },
                text = { Text("Nueva rutina") },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.routines.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Todavía no hay rutinas", style = MaterialTheme.typography.titleLarge)
                            Text("Pulsa Nueva rutina para crear la primera.")
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "Toca una rutina para editar sus datos y ejercicios.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    items(state.routines, key = { it.id }) { routine ->
                        Card(
                            onClick = { onOpenRoutine(routine.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(routine.name, style = MaterialTheme.typography.titleMedium)
                                routine.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Text("Editar rutina", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
