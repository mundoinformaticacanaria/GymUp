package com.mundoinformaticacanaria.gymup.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingRepository
import java.time.LocalDate

@Composable
fun HomeScreen(
    trainingRepository: TrainingRepository,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSessions: () -> Unit,
    onRoutines: () -> Unit,
    onExercises: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val todaySessions by trainingRepository.observeSessionsForDate(today)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val prioritized = remember(todaySessions) {
        todaySessions.sortedWith(
            compareBy<SessionSummary> { it.operationalState.homePriority() }
                .thenBy { it.orderInDay },
        )
    }
    val hasInProgress = prioritized.any { it.operationalState == SessionOperationalState.IN_PROGRESS }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(text = "GymUp", style = MaterialTheme.typography.headlineLarge)
            Text(text = "Hoy", style = MaterialTheme.typography.headlineMedium)
            Text(today.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        if (prioritized.isEmpty()) {
            item { Text("Todavía no hay sesiones para hoy.") }
        } else {
            items(prioritized, key = { it.id }) { session ->
                TodaySessionCard(
                    session = session,
                    prominent = session.operationalState == SessionOperationalState.IN_PROGRESS,
                    onOpen = { onOpenSession(session.id) },
                )
            }
        }
        item {
            if (hasInProgress) {
                OutlinedButton(onClick = onNewSession, modifier = Modifier.fillMaxWidth()) { Text("Nueva sesión") }
            } else {
                Button(onClick = onNewSession, modifier = Modifier.fillMaxWidth()) { Text("Nueva sesión") }
            }
        }
        item {
            QuickLinks(
                onSessions = onSessions,
                onRoutines = onRoutines,
                onExercises = onExercises,
                onHistory = onHistory,
                onSettings = onSettings,
            )
        }
    }
}

@Composable
private fun TodaySessionCard(
    session: SessionSummary,
    prominent: Boolean,
    onOpen: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(session.name, style = MaterialTheme.typography.titleMedium)
            Text("${session.sessionTypeName} · S${session.orderInDay}")
            Text("${session.operationalState.label()} · ${session.executionResult.name.lowercase().replace('_', ' ')}")
            if (prominent) {
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(session.operationalState.actionLabel()) }
            } else {
                OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(session.operationalState.actionLabel()) }
            }
        }
    }
}

@Composable
private fun QuickLinks(
    onSessions: () -> Unit,
    onRoutines: () -> Unit,
    onExercises: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Accesos rápidos", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onSessions, modifier = Modifier.fillMaxWidth()) { Text("Sesiones") }
        OutlinedButton(onClick = onRoutines, modifier = Modifier.fillMaxWidth()) { Text("Rutinas") }
        OutlinedButton(onClick = onExercises, modifier = Modifier.fillMaxWidth()) { Text("Ejercicios") }
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Histórico") }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Ajustes") }
    }
}

private fun SessionOperationalState.homePriority(): Int = when (this) {
    SessionOperationalState.IN_PROGRESS -> 0
    SessionOperationalState.PLANNED -> 1
    SessionOperationalState.REALIZED -> 2
}

private fun SessionOperationalState.actionLabel(): String = when (this) {
    SessionOperationalState.IN_PROGRESS -> "Continuar"
    SessionOperationalState.PLANNED -> "Empezar"
    SessionOperationalState.REALIZED -> "Ver"
}

private fun SessionOperationalState.label(): String = when (this) {
    SessionOperationalState.PLANNED -> "Planificada"
    SessionOperationalState.IN_PROGRESS -> "En curso"
    SessionOperationalState.REALIZED -> "Realizada"
}
