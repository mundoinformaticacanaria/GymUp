package com.mundoinformaticacanaria.gymup.feature.home

import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSessionPriorityTest {
    private fun session(id: String, state: SessionOperationalState, order: Int) =
        SessionSummary(
            id = id,
            date = LocalDate.of(2026, 10, 12),
            orderInDay = order,
            name = id,
            sessionTypeName = "Fuerza",
            operationalState = state,
            executionResult = SessionExecutionResult.NOT_STARTED,
        )

    @Test
    fun inProgressAlwaysPrecedesPlannedAndRealized() {
        val sessions = listOf(
            session("realizada", SessionOperationalState.REALIZED, 1),
            session("planificada", SessionOperationalState.PLANNED, 1),
            session("encurso", SessionOperationalState.IN_PROGRESS, 3),
        )
        assertEquals(
            listOf("encurso", "planificada", "realizada"),
            prioritizeTodaySessions(sessions).map { it.id },
        )
    }

    @Test
    fun sameStateRetainsOrderInDayWithoutRemovingSessions() {
        val sessions = listOf(
            session("tercera", SessionOperationalState.PLANNED, 3),
            session("primera", SessionOperationalState.PLANNED, 1),
            session("segunda", SessionOperationalState.PLANNED, 2),
        )
        assertEquals(
            listOf("primera", "segunda", "tercera"),
            prioritizeTodaySessions(sessions).map { it.id },
        )
        assertEquals(3, prioritizeTodaySessions(sessions).size)
    }
}
