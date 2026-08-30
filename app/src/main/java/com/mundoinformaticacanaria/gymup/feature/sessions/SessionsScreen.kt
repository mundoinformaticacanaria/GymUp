package com.mundoinformaticacanaria.gymup.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary
import com.mundoinformaticacanaria.gymup.domain.repository.SessionRepository
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    sessionRepository: SessionRepository,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
) {
    val sessions by sessionRepository.observeSessions().collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sesiones") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onNewSession, modifier = Modifier.fillMaxWidth()) { Text("Nueva sesión") }
            if (sessions.isEmpty()) {
                Text("Todavía no hay sesiones.", style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = SessionSummary::id) { session ->
                        Card(onClick = { onOpenSession(session.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(session.name, style = MaterialTheme.typography.titleMedium)
                                Text("${session.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} · ${session.sessionTypeName}")
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(session.operationalState.sessionStateLabel())
                                    Text(session.executionResult.label())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun SessionOperationalState.sessionStateLabel(): String = when (this) {
    SessionOperationalState.PLANNED -> "Planificada"
    SessionOperationalState.IN_PROGRESS -> "En curso"
    SessionOperationalState.REALIZED -> "Realizada"
}

internal fun SessionExecutionResult.label(): String = when (this) {
    SessionExecutionResult.NOT_STARTED -> "No iniciada"
    SessionExecutionResult.PARTIAL -> "Parcial"
    SessionExecutionResult.COMPLETED -> "Completada"
}
