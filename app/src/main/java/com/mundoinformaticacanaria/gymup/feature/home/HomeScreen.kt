package com.mundoinformaticacanaria.gymup.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onNewSession: () -> Unit,
    onSessions: () -> Unit,
    onRoutines: () -> Unit,
    onExercises: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(text = "GymUp", style = MaterialTheme.typography.headlineLarge)
                Text(text = "Hoy", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                Text("Todavía no hay sesiones para hoy.")
            }
            item {
                Button(
                    onClick = onNewSession,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Nueva sesión")
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
        OutlinedButton(onClick = onSessions, modifier = Modifier.fillMaxWidth()) { Text("Sesiones") }
        OutlinedButton(onClick = onRoutines, modifier = Modifier.fillMaxWidth()) { Text("Rutinas") }
        OutlinedButton(onClick = onExercises, modifier = Modifier.fillMaxWidth()) { Text("Ejercicios") }
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Histórico") }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Ajustes") }
    }
}
