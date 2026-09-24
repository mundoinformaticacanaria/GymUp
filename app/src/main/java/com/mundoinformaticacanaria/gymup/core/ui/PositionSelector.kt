package com.mundoinformaticacanaria.gymup.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PositionSelector(
    currentPosition: Int,
    totalPositions: Int,
    onPositionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Posición")
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = totalPositions > 1,
            ) {
                Text("$currentPosition de $totalPositions")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                (1..totalPositions).forEach { position ->
                    DropdownMenuItem(
                        text = {
                            Text(if (position == currentPosition) "$position (actual)" else position.toString())
                        },
                        onClick = {
                            expanded = false
                            onPositionSelected(position)
                        },
                    )
                }
            }
        }
    }
}
