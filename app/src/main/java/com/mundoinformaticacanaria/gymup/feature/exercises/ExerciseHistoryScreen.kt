package com.mundoinformaticacanaria.gymup.feature.exercises

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseHistory
import com.mundoinformaticacanaria.gymup.domain.repository.HistoryRepository
import com.mundoinformaticacanaria.gymup.domain.usecase.ChartSeriesSegment
import com.mundoinformaticacanaria.gymup.domain.usecase.HistoryMetric
import com.mundoinformaticacanaria.gymup.domain.usecase.buildChartSegments

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseHistoryScreen(
    exerciseId: String,
    historyRepository: HistoryRepository,
    onBack: () -> Unit,
) {
    var history by remember(exerciseId) { mutableStateOf<ExerciseHistory?>(null) }
    LaunchedEffect(exerciseId) { history = historyRepository.getExerciseHistory(exerciseId, 10) }
    val current = history

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.let { "${it.nameEs} · ${it.nameEn}" } ?: "Histórico") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) { Text("Cargando histórico…") }
            return@Scaffold
        }
        ExerciseHistoryContent(current, Modifier.padding(padding))
    }
}

@Composable
private fun ExerciseHistoryContent(history: ExerciseHistory, modifier: Modifier = Modifier) {
    val chronological = remember(history.executions) { history.executions.asReversed() }
    val loadSegments = remember(chronological) { buildChartSegments(chronological, HistoryMetric.LOAD) }
    val measurementSegments = remember(chronological) { buildChartSegments(chronological, HistoryMetric.MEASUREMENT) }
    val hasLoad = loadSegments.isNotEmpty()
    var metric by remember(history.exerciseId, hasLoad) { mutableStateOf(if (hasLoad) HistoryMetric.LOAD else HistoryMetric.MEASUREMENT) }
    val segments = if (metric == HistoryMetric.LOAD) loadSegments else measurementSegments

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Últimas ${history.executions.size} ejecuciones válidas", style = MaterialTheme.typography.titleMedium)
            if (history.executions.isEmpty()) Text("Todavía no hay ejecuciones con trabajo real.")
        }
        if (history.executions.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasLoad) {
                        FilterChip(selected = metric == HistoryMetric.LOAD, onClick = { metric = HistoryMetric.LOAD }, label = { Text("Carga") })
                    }
                    FilterChip(selected = metric == HistoryMetric.MEASUREMENT, onClick = { metric = HistoryMetric.MEASUREMENT }, label = { Text("Medición") })
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (metric == HistoryMetric.LOAD) "Evolución de carga" else "Evolución de medición", style = MaterialTheme.typography.titleMedium)
                        if (segments.isEmpty()) {
                            Text("No hay valores reales disponibles para esta dimensión.")
                        } else {
                            HistoryChart(segments, chronological.size)
                            segments.distinctBy { it.setPosition to it.dimension }.forEach { segment ->
                                Text("Serie ${segment.setPosition} · ${segment.dimension.prettyDimension()}", style = MaterialTheme.typography.labelMedium)
                            }
                            Text("Punto vacío = ejecución parcial · punto sólido = completada", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        items(history.executions, key = { "${it.sessionId}:${it.date}:${it.orderInDay}" }) { execution ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${execution.date} · ${execution.sessionName}", style = MaterialTheme.typography.titleSmall)
                    Text(if (execution.status == ExerciseExecutionStatus.PARTIAL) "Parcial" else "Completado")
                    execution.sets.sortedBy { it.position }.forEach { set ->
                        val load = set.actualLoad?.let { " · carga ${it.toString().replace('.', ',')} (${set.loadMode.name.prettyDimension()})" }.orEmpty()
                        val measurement = set.actualMeasurement?.let { " · $it (${set.measurementUnit.name.prettyDimension()})" }.orEmpty()
                        val rir = set.rir?.let { " · RIR $it" }.orEmpty()
                        Text("Serie ${set.position}$load$measurement$rir")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryChart(segments: List<ChartSeriesSegment>, executionCount: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val allValues = segments.flatMap { it.points }.map { it.value }
    val minValue = allValues.minOrNull() ?: 0.0
    val maxValue = allValues.maxOrNull() ?: 1.0
    val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0

    Canvas(modifier = Modifier.fillMaxWidth().height(240.dp)) {
        val left = 24f
        val right = (size.width - 12f).coerceAtLeast(left + 1f)
        val top = 12f
        val bottom = (size.height - 24f).coerceAtLeast(top + 1f)
        drawLine(axis, Offset(left, top), Offset(left, bottom), strokeWidth = 1.5f)
        drawLine(axis, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.5f)

        fun pointOffset(index: Int, value: Double): Offset {
            val x = if (executionCount <= 1) (left + right) / 2f else left + (right - left) * index.toFloat() / (executionCount - 1).toFloat()
            val normalized = ((value - minValue) / span).toFloat()
            val y = bottom - (bottom - top) * normalized
            return Offset(x, y)
        }

        segments.forEach { segment ->
            val alpha = (1f - (segment.setPosition - 1) * 0.13f).coerceIn(0.35f, 1f)
            val lineColor = primary.copy(alpha = alpha)
            segment.points.zipWithNext().forEach { (a, b) ->
                drawLine(lineColor, pointOffset(a.executionIndex, a.value), pointOffset(b.executionIndex, b.value), strokeWidth = 4f)
            }
            segment.points.forEach { point ->
                val center = pointOffset(point.executionIndex, point.value)
                if (point.isPartial) {
                    drawCircle(lineColor, radius = 7f, center = center, style = Stroke(width = 3f))
                } else {
                    drawCircle(lineColor, radius = 6f, center = center)
                }
            }
        }
    }
}

private fun String.prettyDimension(): String = when (this) {
    "KG_TOTAL" -> "kg total"
    "KG_PER_HAND" -> "kg/mano"
    "KG_PER_SIDE" -> "kg/lado"
    "BODYWEIGHT_PLUS_LOAD" -> "lastre"
    "BODYWEIGHT_MINUS_ASSISTANCE" -> "asistencia"
    "BODYWEIGHT" -> "peso corporal"
    "NO_WEIGHT" -> "sin peso"
    "REPETITIONS" -> "repeticiones"
    "REPETITIONS_PER_SIDE" -> "repeticiones/lado"
    "SECONDS" -> "segundos"
    "SECONDS_PER_SIDE" -> "segundos/lado"
    else -> this
}
