package com.mundoinformaticacanaria.gymup.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NameNormalizerTest {
    @Test
    fun ignoresCaseWhitespaceAndDiacritics() {
        assertEquals("press", normalizeName("  PrÉSS  "))
        assertEquals("biceps", normalizeName("Bíceps"))
    }
}
