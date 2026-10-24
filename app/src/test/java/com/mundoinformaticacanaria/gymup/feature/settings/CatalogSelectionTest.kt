package com.mundoinformaticacanaria.gymup.feature.settings

import com.mundoinformaticacanaria.gymup.domain.repository.MasterCatalogKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSelectionTest {
    private val sessionTypes = listOf("Fuerza", "Cardio")
    private val muscleGroups = listOf("Espalda")
    private val equipment = listOf("Mancuernas", "Barra")

    @Test
    fun sessionTypeSelectionDoesNotShowOtherMasters() {
        assertEquals(
            sessionTypes,
            catalogItemsFor(MasterCatalogKind.SESSION_TYPE, sessionTypes, muscleGroups, equipment),
        )
    }

    @Test
    fun muscleGroupSelectionDoesNotShowOtherMasters() {
        assertEquals(
            muscleGroups,
            catalogItemsFor(MasterCatalogKind.MUSCLE_GROUP, sessionTypes, muscleGroups, equipment),
        )
    }

    @Test
    fun equipmentSelectionDoesNotShowOtherMasters() {
        assertEquals(
            equipment,
            catalogItemsFor(MasterCatalogKind.EQUIPMENT, sessionTypes, muscleGroups, equipment),
        )
    }
}
