package com.mundoinformaticacanaria.gymup.feature.home

import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary

internal fun prioritizeTodaySessions(sessions: List<SessionSummary>): List<SessionSummary> =
    sessions.sortedWith(
        compareBy<SessionSummary> { it.operationalState.homePriority() }
            .thenBy { it.orderInDay },
    )

private fun SessionOperationalState.homePriority(): Int = when (this) {
    SessionOperationalState.IN_PROGRESS -> 0
    SessionOperationalState.PLANNED -> 1
    SessionOperationalState.REALIZED -> 2
}
