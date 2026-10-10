package com.mundoinformaticacanaria.gymup.feature.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFilterSummaryTest {
    @Test
    fun noActiveFilters() {
        assertEquals("Sin filtros activos", historyFilterSummary(null, null, null, "", ""))
    }

    @Test
    fun allFiltersAreDescribedWithoutDroppingCombinations() {
        assertEquals(
            "Estado: Realizada · Resultado: Parcial · Tipo: Fuerza · Desde: 2026-01-01 · Hasta: 2026-10-10",
            historyFilterSummary("Realizada", "Parcial", "Fuerza", " 2026-01-01 ", "2026-10-10"),
        )
    }

    @Test
    fun invalidDateInputRemainsVisibleForCorrection() {
        assertEquals("Desde: fecha-incorrecta", historyFilterSummary(null, null, null, "fecha-incorrecta", ""))
    }
}
