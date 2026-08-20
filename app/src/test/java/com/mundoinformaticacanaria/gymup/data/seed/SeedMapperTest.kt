package com.mundoinformaticacanaria.gymup.data.seed

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SeedMapperTest {
    @Test fun mapsEverySupportedLoadMode() {
        val expected = mapOf("KG_TOTAL" to LoadMode.KG_TOTAL, "KG_POR_MANO" to LoadMode.KG_PER_HAND, "KG_POR_LADO" to LoadMode.KG_PER_SIDE, "PESO_CORPORAL" to LoadMode.BODYWEIGHT, "PESO_CORPORAL_LASTRE" to LoadMode.BODYWEIGHT_PLUS_LOAD, "PESO_CORPORAL_ASISTENCIA" to LoadMode.BODYWEIGHT_MINUS_ASSISTANCE, "SIN_PESO" to LoadMode.NO_WEIGHT)
        expected.forEach { (raw, mapped) -> assertEquals(mapped, SeedMapper.loadMode(raw)) }
    }

    @Test fun mapsEverySupportedMeasurementUnit() {
        val expected = mapOf("REPETICIONES" to MeasurementUnit.REPETITIONS, "REPETICIONES_LADO" to MeasurementUnit.REPETITIONS_PER_SIDE, "SEGUNDOS" to MeasurementUnit.SECONDS, "SEGUNDOS_LADO" to MeasurementUnit.SECONDS_PER_SIDE)
        expected.forEach { (raw, mapped) -> assertEquals(mapped, SeedMapper.measurementUnit(raw)) }
    }

    @Test fun rejectsUnknownValues() {
        assertThrows(IllegalStateException::class.java) { SeedMapper.loadMode("KG_INVENTADO") }
        assertThrows(IllegalStateException::class.java) { SeedMapper.measurementUnit("MINUTOS") }
    }
}
