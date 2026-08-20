package com.mundoinformaticacanaria.gymup.feature.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.HistoryRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.usecase.rankExercises
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(
    exerciseCatalogRepository: ExerciseCatalogRepository,
    masterCatalogRepository: MasterCatalogRepository,
    historyRepository: HistoryRepository,
    onOpenHistory: (String) -> Unit,
    onBack: () -> Unit,
) {
    val exercises by exerciseCatalogRepository.observeActiveExercises().collectAsStateWithLifecycle(initialValue = emptyList())
    val muscleGroups by masterCatalogRepository.observeMuscleGroups().collectAsStateWithLifecycle(initialValue = emptyList())
    val metadata by historyRepository.observeExerciseSearchMetadata().collectAsStateWithLifecycle(initialValue = emptyMap())
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var muscleGroupId by remember { mutableStateOf<String?>(null) }
    val ranked = remember(exercises, metadata, query, muscleGroupId) {
        rankExercises(exercises, metadata, query, muscleGroupId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ejercicios") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Buscar por nombre") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = muscleGroupId == null, onClick = { muscleGroupId = null }, label = { Text("Todos") })
                }
                items(muscleGroups, key = { it.id }) { group ->
                    FilterChip(
                        selected = muscleGroupId == group.id,
                        onClick = { muscleGroupId = if (muscleGroupId == group.id) null else group.id },
                        label = { Text(group.name) },
                    )
                }
            }
            Text("${ranked.size} ejercicio(s)", style = MaterialTheme.typography.labelLarge)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ranked, key = { it.id }) { exercise ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${exercise.nameEs} · ${exercise.nameEn}", style = MaterialTheme.typography.titleMedium)
                            val meta = metadata[exercise.id]
                            val support = buildList {
                                if (exercise.isFavorite) add("Favorito")
                                meta?.lastExecutionDate?.let { add("Último: $it") }
                                if (meta?.inRoutine == true) add("En rutina")
                            }
                            if (support.isNotEmpty()) Text(support.joinToString(" · "))
                            TextButton(
                                onClick = { scope.launch { exerciseCatalogRepository.setFavorite(exercise.id, !exercise.isFavorite) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (exercise.isFavorite) "★ Quitar favorito" else "☆ Marcar favorito") }
                            TextButton(onClick = { onOpenHistory(exercise.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Ver histórico y gráficas")
                            }
                        }
                    }
                }
            }
        }
    }
}
