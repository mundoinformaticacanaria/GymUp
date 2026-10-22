package com.mundoinformaticacanaria.gymup.feature.sessions

import com.mundoinformaticacanaria.gymup.domain.repository.MissingRirException
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDetailViewModelMessageTest {
    @Test
    fun requiredRirErrorIdentifiesMissingSets() {
        assertEquals(
            "Falta RIR obligatorio en 2 serie(s).",
            sessionActionErrorText(MissingRirException(listOf("serie-1", "serie-2"))),
        )
    }

    @Test
    fun repositoryErrorMessageIsPreserved() {
        assertEquals(
            "No se pudo guardar",
            sessionActionErrorText(IllegalStateException("No se pudo guardar")),
        )
    }

    @Test
    fun missingErrorMessageHasFallback() {
        assertEquals(
            "No se pudo aplicar el cambio.",
            sessionActionErrorText(IllegalStateException()),
        )
    }
}
