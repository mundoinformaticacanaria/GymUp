package com.mundoinformaticacanaria.gymup.feature.settings

import com.mundoinformaticacanaria.gymup.data.cleanup.HistoricalCleanupImpact
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCleanupPreviewTest {
    private val preview = HistoricalCleanupImpact(
        cutoffDate = LocalDate.of(2026, 10, 1),
        sessions = 2,
        sessionExercises = 4,
        sessionSets = 6,
    )

    @Test
    fun matchingCutoffCanBeConfirmed() {
        assertTrue(cleanupPreviewMatchesCutoff(" 2026-10-01 ", preview))
    }

    @Test
    fun editedCutoffCannotConfirmStalePreview() {
        assertFalse(cleanupPreviewMatchesCutoff("2026-10-02", preview))
    }

    @Test
    fun malformedOrMissingCutoffCannotConfirmPreview() {
        assertFalse(cleanupPreviewMatchesCutoff("not-a-date", preview))
        assertFalse(cleanupPreviewMatchesCutoff("", preview))
        assertFalse(cleanupPreviewMatchesCutoff("2026-10-01", null))
    }
}
