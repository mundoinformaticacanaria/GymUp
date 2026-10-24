package com.mundoinformaticacanaria.gymup.feature.settings

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupPreviewGenerationTest {
    private val cutoff = LocalDate.parse("2026-09-01")

    private fun isCurrent(
        text: String = "2026-09-01",
        request: Int = 3,
        active: Int = 3,
        deleting: Boolean = false,
    ) = cleanupPreviewResponseIsCurrent(cutoff, text, request, active, deleting)

    @Test fun latestPreviewForSameCutoffIsAccepted() {
        assertTrue(isCurrent())
    }

    @Test fun olderResponseForSameCutoffIsRejected() {
        assertFalse(isCurrent(request = 2))
    }

    @Test fun editedOrInvalidCutoffRejectsResponse() {
        assertFalse(isCurrent(text = "2026-10-01"))
        assertFalse(isCurrent(text = "not-a-date"))
    }

    @Test fun cleanupInProgressRejectsPendingPreview() {
        assertFalse(isCurrent(deleting = true))
    }
}
