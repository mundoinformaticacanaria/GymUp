package com.mundoinformaticacanaria.gymup.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode

@Composable
fun SettingsScreen(
    currentMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Ajustes", style = MaterialTheme.typography.headlineMedium)
        Text(text = "Tema")
        ThemeButton("Sistema", ThemeMode.SYSTEM, currentMode, onThemeModeSelected)
        ThemeButton("Claro", ThemeMode.LIGHT, currentMode, onThemeModeSelected)
        ThemeButton("Oscuro", ThemeMode.DARK, currentMode, onThemeModeSelected)
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Volver")
        }
    }
}

@Composable
private fun ThemeButton(
    label: String,
    mode: ThemeMode,
    currentMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
) {
    if (currentMode == mode) {
        Button(onClick = { onThemeModeSelected(mode) }, modifier = Modifier.fillMaxWidth()) {
            Text("$label · seleccionado")
        }
    } else {
        OutlinedButton(onClick = { onThemeModeSelected(mode) }, modifier = Modifier.fillMaxWidth()) {
            Text(label)
        }
    }
}
