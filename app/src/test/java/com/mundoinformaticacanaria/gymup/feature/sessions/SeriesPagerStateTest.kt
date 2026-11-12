package com.mundoinformaticacanaria.gymup.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesPagerStateTest {
    @Test
    fun newSeriesBecomesVisible() {
        assertEquals(
            3,
            resolveSeriesPageIndex(
                previousSetIds = listOf("one", "two", "three"),
                currentSetIds = listOf("one", "two", "three", "four"),
                previousPage = 1,
            ),
        )
    }

    @Test
    fun selectedSeriesStaysVisibleWhenAnotherSeriesIsDeleted() {
        assertEquals(
            1,
            resolveSeriesPageIndex(
                previousSetIds = listOf("one", "two", "three"),
                currentSetIds = listOf("one", "three"),
                previousPage = 2,
            ),
        )
    }

    @Test
    fun deletingSelectedLastSeriesSelectsTheNewLastSeries() {
        assertEquals(
            1,
            resolveSeriesPageIndex(
                previousSetIds = listOf("one", "two", "three"),
                currentSetIds = listOf("one", "two"),
                previousPage = 2,
            ),
        )
    }

    @Test
    fun indicatorsDisappearAtTheirLimits() {
        assertEquals("Serie 1 de 4  >", seriesPositionText(page = 0, pageCount = 4))
        assertEquals("<  Serie 2 de 4  >", seriesPositionText(page = 1, pageCount = 4))
        assertEquals("<  Serie 4 de 4", seriesPositionText(page = 3, pageCount = 4))
        assertEquals("Serie 1 de 1", seriesPositionText(page = 0, pageCount = 1))
    }
}
