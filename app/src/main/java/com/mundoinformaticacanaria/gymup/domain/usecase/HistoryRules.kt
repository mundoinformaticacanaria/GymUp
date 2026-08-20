package com.mundoinformaticacanaria.gymup.domain.usecase

import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.core.util.normalizeName
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogItem
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseHistoryExecution
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseSearchMetadata
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary
import java.time.LocalDate

data class SessionHistoryFilter(
    val operationalState: SessionOperationalState? = null,
    val executionResult: SessionExecutionResult? = null,
    val sessionTypeId: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

enum class HistoryMetric { LOAD, MEASUREMENT }

data class ChartPoint(
    val executionIndex: Int,
    val value: Double,
    val isPartial: Boolean,
    val date: LocalDate,
)

data class ChartSeriesSegment(
    val setPosition: Int,
    val dimension: String,
    val points: List<ChartPoint>,
)

fun rankExercises(
    exercises: List<ExerciseCatalogItem>,
    metadata: Map<String, ExerciseSearchMetadata>,
    query: String,
    muscleGroupId: String?,
): List<ExerciseCatalogItem> {
    val normalizedQuery = normalizeName(query)
    data class Ranked(val exercise: ExerciseCatalogItem, val primary: Int, val tier: Int, val meta: ExerciseSearchMetadata?)

    val ranked = exercises.mapNotNull { exercise ->
        if (muscleGroupId != null && exercise.muscleGroupId != muscleGroupId) return@mapNotNull null
        val es = normalizeName(exercise.nameEs)
        val en = normalizeName(exercise.nameEn)
        val primary = when {
            normalizedQuery.isBlank() -> 0
            es.startsWith(normalizedQuery) || en.startsWith(normalizedQuery) -> 0
            es.contains(normalizedQuery) || en.contains(normalizedQuery) -> 1
            else -> return@mapNotNull null
        }
        val meta = metadata[exercise.id]
        val tier = when {
            exercise.isFavorite -> 0
            meta?.lastExecutionDate != null -> 1
            meta?.inRoutine == true -> 2
            else -> 3
        }
        Ranked(exercise, primary, tier, meta)
    }

    return ranked.sortedWith { a, b ->
        var result = a.primary.compareTo(b.primary)
        if (result != 0) return@sortedWith result
        result = a.tier.compareTo(b.tier)
        if (result != 0) return@sortedWith result
        if (a.tier == 1) {
            result = compareValues(b.meta?.lastExecutionDate, a.meta?.lastExecutionDate)
            if (result != 0) return@sortedWith result
            result = compareValues(b.meta?.lastExecutionOrderInDay, a.meta?.lastExecutionOrderInDay)
            if (result != 0) return@sortedWith result
        }
        result = normalizeName(a.exercise.nameEs).compareTo(normalizeName(b.exercise.nameEs))
        if (result != 0) return@sortedWith result
        normalizeName(a.exercise.nameEn).compareTo(normalizeName(b.exercise.nameEn))
    }.map { it.exercise }
}

fun filterSessionHistory(sessions: List<SessionSummary>, filter: SessionHistoryFilter): List<SessionSummary> =
    sessions.filter { session ->
        (filter.operationalState == null || session.operationalState == filter.operationalState) &&
            (filter.executionResult == null || session.executionResult == filter.executionResult) &&
            (filter.sessionTypeId == null || session.sessionTypeId == filter.sessionTypeId) &&
            (filter.from == null || !session.date.isBefore(filter.from)) &&
            (filter.to == null || !session.date.isAfter(filter.to))
    }

fun buildChartSegments(
    executionsChronological: List<ExerciseHistoryExecution>,
    metric: HistoryMetric,
): List<ChartSeriesSegment> {
    val maxPosition = executionsChronological.flatMap { it.sets }.maxOfOrNull { it.position } ?: return emptyList()
    val result = mutableListOf<ChartSeriesSegment>()

    for (position in 1..maxPosition) {
        var dimension: String? = null
        var points = mutableListOf<ChartPoint>()

        fun flush() {
            val currentDimension = dimension
            if (currentDimension != null && points.isNotEmpty()) {
                result += ChartSeriesSegment(position, currentDimension, points.toList())
            }
            dimension = null
            points = mutableListOf()
        }

        executionsChronological.forEachIndexed { index, execution ->
            val set = execution.sets.firstOrNull { it.position == position }
            val valueAndDimension = when (metric) {
                HistoryMetric.LOAD -> set?.actualLoad?.takeIf { set.loadMode.hasNumericLoad() }?.let { value ->
                    value to set.loadMode.name
                }
                HistoryMetric.MEASUREMENT -> set?.actualMeasurement?.let { value ->
                    value.toDouble() to set.measurementUnit.name
                }
            }
            if (valueAndDimension == null) {
                flush()
            } else {
                val (value, nextDimension) = valueAndDimension
                if (dimension != null && dimension != nextDimension) flush()
                if (dimension == null) dimension = nextDimension
                points += ChartPoint(
                    executionIndex = index,
                    value = value,
                    isPartial = execution.status == ExerciseExecutionStatus.PARTIAL,
                    date = execution.date,
                )
            }
        }
        flush()
    }
    return result
}

private fun LoadMode.hasNumericLoad(): Boolean = when (this) {
    LoadMode.KG_TOTAL,
    LoadMode.KG_PER_HAND,
    LoadMode.KG_PER_SIDE,
    LoadMode.BODYWEIGHT_PLUS_LOAD,
    LoadMode.BODYWEIGHT_MINUS_ASSISTANCE,
    -> true
    LoadMode.BODYWEIGHT,
    LoadMode.NO_WEIGHT,
    -> false
}
