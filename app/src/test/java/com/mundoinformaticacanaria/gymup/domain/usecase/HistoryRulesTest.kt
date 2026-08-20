package com.mundoinformaticacanaria.gymup.domain.usecase

import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseCatalogItem
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseHistoryExecution
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseHistorySet
import com.mundoinformaticacanaria.gymup.domain.repository.ExerciseSearchMetadata
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRulesTest {
    @Test
    fun searchUsesStartsBeforeContainsThenFavoriteUsageRoutineAndStableName() {
        val exercises = listOf(
            exercise("favorite-contains", "Elevación press", favorite = true),
            exercise("used-start", "Press inclinado"),
            exercise("favorite-start", "Press banca", favorite = true),
            exercise("routine-start", "Press militar"),
            exercise("rest-start", "Préss cerrado"),
        )
        val metadata = mapOf(
            "used-start" to ExerciseSearchMetadata(LocalDate.of(2026, 8, 20), 1, false),
            "routine-start" to ExerciseSearchMetadata(null, null, true),
        )

        val ranked = rankExercises(exercises, metadata, "press", null)

        assertEquals(
            listOf("favorite-start", "used-start", "routine-start", "rest-start", "favorite-contains"),
            ranked.map { it.id },
        )
    }

    @Test
    fun searchMatchesAccentsAndFiltersMuscleGroup() {
        val shoulder = exercise("shoulder", "Elevación lateral", muscleGroupId = "shoulder")
        val chest = exercise("chest", "Elevacion frontal", muscleGroupId = "chest")
        val ranked = rankExercises(listOf(shoulder, chest), emptyMap(), "elevacion", "shoulder")
        assertEquals(listOf("shoulder"), ranked.map { it.id })
    }

    @Test
    fun sessionHistoryFiltersAreInclusiveAndCombinable() {
        val sessions = listOf(
            session("a", LocalDate.of(2026, 8, 19), "Fuerza", SessionOperationalState.REALIZED, SessionExecutionResult.COMPLETED),
            session("b", LocalDate.of(2026, 8, 20), "Fuerza", SessionOperationalState.REALIZED, SessionExecutionResult.PARTIAL),
            session("c", LocalDate.of(2026, 8, 21), "Cardio", SessionOperationalState.PLANNED, SessionExecutionResult.NOT_STARTED),
        )
        val filtered = filterSessionHistory(
            sessions,
            SessionHistoryFilter(
                operationalState = SessionOperationalState.REALIZED,
                sessionTypeName = "Fuerza",
                from = LocalDate.of(2026, 8, 20),
                to = LocalDate.of(2026, 8, 20),
            ),
        )
        assertEquals(listOf("b"), filtered.map { it.id })
    }

    @Test
    fun chartBreaksOnLoadModeChangesMissingSeriesAndMarksPartialPoints() {
        val executions = listOf(
            execution(LocalDate.of(2026, 8, 17), ExerciseExecutionStatus.COMPLETED, set(1, LoadMode.KG_PER_HAND, 10.0, 10)),
            execution(LocalDate.of(2026, 8, 18), ExerciseExecutionStatus.PARTIAL, set(1, LoadMode.KG_PER_HAND, 12.5, 8)),
            execution(LocalDate.of(2026, 8, 19), ExerciseExecutionStatus.COMPLETED, set(2, LoadMode.KG_TOTAL, 20.0, 10)),
            execution(LocalDate.of(2026, 8, 20), ExerciseExecutionStatus.COMPLETED, set(1, LoadMode.KG_TOTAL, 25.0, 12)),
        )

        val load = buildChartSegments(executions, HistoryMetric.LOAD)

        val setOne = load.filter { it.setPosition == 1 }
        assertEquals(2, setOne.size)
        assertEquals("KG_PER_HAND", setOne.first().dimension)
        assertEquals(listOf(0, 1), setOne.first().points.map { it.executionIndex })
        assertTrue(setOne.first().points[1].isPartial)
        assertEquals("KG_TOTAL", setOne.last().dimension)
        assertEquals(listOf(3), setOne.last().points.map { it.executionIndex })
        assertFalse(setOne.last().points.single().isPartial)
    }

    @Test
    fun bodyweightWithoutNumericLoadDoesNotProduceLoadSeries() {
        val executions = listOf(
            execution(LocalDate.of(2026, 8, 20), ExerciseExecutionStatus.COMPLETED, set(1, LoadMode.BODYWEIGHT, null, 10)),
        )
        assertTrue(buildChartSegments(executions, HistoryMetric.LOAD).isEmpty())
        assertEquals(1, buildChartSegments(executions, HistoryMetric.MEASUREMENT).size)
    }

    private fun exercise(
        id: String,
        name: String,
        favorite: Boolean = false,
        muscleGroupId: String = "g",
    ) = ExerciseCatalogItem(
        id = id,
        nameEs = name,
        nameEn = name,
        muscleGroupId = muscleGroupId,
        equipmentId = null,
        loadMode = LoadMode.KG_TOTAL,
        measurementUnit = MeasurementUnit.REPETITIONS,
        rirRequired = true,
        isFavorite = favorite,
    )

    private fun session(
        id: String,
        date: LocalDate,
        type: String,
        state: SessionOperationalState,
        result: SessionExecutionResult,
    ) = SessionSummary(id, date, 1, id, type, state, result)

    private fun set(position: Int, mode: LoadMode, load: Double?, measurement: Int) =
        ExerciseHistorySet(position, mode, load, MeasurementUnit.REPETITIONS, measurement, 1)

    private fun execution(date: LocalDate, status: ExerciseExecutionStatus, vararg sets: ExerciseHistorySet) =
        ExerciseHistoryExecution("session-$date", "Sesión", date, 1, status, sets.toList())
}
