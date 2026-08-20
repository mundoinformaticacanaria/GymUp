package com.mundoinformaticacanaria.gymup.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.HistoryRepository
import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogRepository
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingRepository
import com.mundoinformaticacanaria.gymup.domain.usecase.SessionHistoryFilter
import com.mundoinformaticacanaria.gymup.domain.usecase.filterSessionHistory
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    trainingRepository: TrainingRepository,
    masterCatalogRepository: MasterCatalogRepository,
    historyRepository: HistoryRepository,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
) {
    val sessions by trainingRepository.observeSessions().collectAsStateWithLifecycle(initialValue = emptyList())
    val sessionTypes by masterCatalogRepository.observeSessionTypes().collectAsStateWithLifecycle(initialValue = emptyList())
    val sessionTypeIds by historyRepository.observeSessionTypeIds().collectAsStateWithLifecycle(initialValue = emptyMap())
    var state by remember { mutableStateOf<SessionOperationalState?>(null) }
    var result by remember { mutableStateOf<SessionExecutionResult?>(null) }
    var typeId by remember { mutableStateOf<String?>(null) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    val from = fromText.parseDateOrNull()
    val to = toText.parseDateOrNull()
    val filtered = remember(sessions, sessionTypeIds, state, result, typeId, from, to) {
        filterSessionHistory(
            sessions,
            SessionHistoryFilter(
                operationalState = state,
                executionResult = result,
                sessionTypeId = typeId,
                from = from,
                to = to,
            ),
            sessionTypeIds,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Histórico") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Filtros", style = MaterialTheme.typography.titleMedium)
                        Text("Estado operativo")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = state == null, onClick = { state = null }, label = { Text("Todos") })
                        }
                        SessionOperationalState.entries.forEach { item ->
                            FilterChip(
                                selected = state == item,
                                onClick = { state = if (state == item) null else item },
                                label = { Text(item.label()) },
                            )
                        }
                        Text("Resultado")
                        SessionExecutionResult.entries.forEach { item ->
                            FilterChip(
                                selected = result == item,
                                onClick = { result = if (result == item) null else item },
                                label = { Text(item.label()) },
                            )
                        }
                        Text("Tipo de sesión")
                        FilterChip(selected = typeId == null, onClick = { typeId = null }, label = { Text("Todos") })
                        sessionTypes.forEach { type ->
                            FilterChip(
                                selected = typeId == type.id,
                                onClick = { typeId = if (typeId == type.id) null else type.id },
                                label = { Text(type.name) },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = fromText,
                                onValueChange = { fromText = it },
                                label = { Text("Desde YYYY-MM-DD") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = toText,
                                onValueChange = { toText = it },
                                label = { Text("Hasta YYYY-MM-DD") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if ((fromText.isNotBlank() && from == null) || (toText.isNotBlank() && to == null)) {
                            Text("Usa fechas con formato YYYY-MM-DD.", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(
                            onClick = {
                                state = null
                                result = null
                                typeId = null
                                fromText = ""
                                toText = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Limpiar filtros") }
                    }
                }
            }
            item { Text("${filtered.size} sesión(es)", style = MaterialTheme.typography.labelLarge) }
            items(filtered, key = { it.id }) { session ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(session.name, style = MaterialTheme.typography.titleMedium)
                        Text("${session.date} · ${session.sessionTypeName}")
                        Text("${session.operationalState.label()} · ${session.executionResult.label()}")
                        TextButton(onClick = { onOpenSession(session.id) }, modifier = Modifier.fillMaxWidth()) { Text("Ver / editar") }
                    }
                }
            }
        }
    }
}

private fun String.parseDateOrNull(): LocalDate? = trim().takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun SessionOperationalState.label(): String = when (this) {
    SessionOperationalState.PLANNED -> "Planificada"
    SessionOperationalState.IN_PROGRESS -> "En curso"
    SessionOperationalState.REALIZED -> "Realizada"
}

private fun SessionExecutionResult.label(): String = when (this) {
    SessionExecutionResult.NOT_STARTED -> "No iniciada"
    SessionExecutionResult.PARTIAL -> "Parcial"
    SessionExecutionResult.COMPLETED -> "Completada"
}
