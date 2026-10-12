package com.mundoinformaticacanaria.gymup.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
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
import com.mundoinformaticacanaria.gymup.domain.repository.SessionRepository
import java.time.LocalDate

@Composable
fun HomeScreen(
    sessionRepository: SessionRepository,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSessions: () -> Unit,
    onRoutines: () -> Unit,
    onExercises: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val todaySessions by sessionRepository.observeSessionsForDate(today)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val prioritized = remember(todaySessions) {
        todaySessions.sortedWith(
            compareBy<SessionSummary> { it.operationalState.homePriority() }
                .thenBy { it.orderInDay },
        )
    }
    val hasInProgress = prioritized.any { it.operationalState == SessionOperationalState.IN_PROGRESS }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("GymUp", style = MaterialTheme.typography.headlineLarge)
            Text("Hoy · $today", style = MaterialTheme.typography.bodyMedium)
            prioritized.firstOrNull {
                it.operationalState == SessionOperationalState.IN_PROGRESS
            }?.let { active ->
                Button(onClick = { onOpenSession(active.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Continuar · ${active.name}")
                }
            }
            if (hasInProgress) {
                OutlinedButton(onClick = onNewSession, modifier = Modifier.fillMaxWidth()) {
                    Text("Nueva sesión")
                }
            } else {
                Button(onClick = onNewSession, modifier = Modifier.fillMaxWidth()) {
                    Text("Nueva sesión")
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
        }
        QuickLinks(
            onSessions = onSessions,
            onRoutines = onRoutines,
            onExercises = onExercises,
            onHistory = onHistory,
            onSettings = onSettings,
        )
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
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "Secciones · desliza para ver más",
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilledTonalButton(onClick = onSessions) { Text("Sesiones") } }
            item { FilledTonalButton(onClick = onRoutines) { Text("Rutinas") } }
            item { FilledTonalButton(onClick = onExercises) { Text("Ejercicios") } }
            item { FilledTonalButton(onClick = onHistory) { Text("Histórico") } }
            item { FilledTonalButton(onClick = onSettings) { Text("Ajustes") } }
        }
    }
}

private fun SessionOperationalState.homePriority(): Int = when (this) {
    SessionOperationalState.IN_PROGRESS -> 0
    SessionOperationalState.PLANNED -> 1
    SessionOperationalState.REALIZED -> 2
}

private fun SessionOperationalState.actionLabel(): String = when (this) {
    SessionOperationalState.IN_PROGRESS -> "Continuar"
    SessionOperationalState.PLANNED -> "Abrir planificación"
    SessionOperationalState.REALIZED -> "Ver"
}

private fun SessionOperationalState.label(): String = when (this) {
    SessionOperationalState.PLANNED -> "Planificada"
    SessionOperationalState.IN_PROGRESS -> "En curso"
    SessionOperationalState.REALIZED -> "Realizada"
}
