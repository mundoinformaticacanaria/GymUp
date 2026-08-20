package com.mundoinformaticacanaria.gymup.data.seed

import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit

object SeedMapper {
    fun loadMode(value: String): LoadMode = when (value) {
        "KG_TOTAL" -> LoadMode.KG_TOTAL
        "KG_POR_MANO" -> LoadMode.KG_PER_HAND
        "KG_POR_LADO" -> LoadMode.KG_PER_SIDE
        "PESO_CORPORAL" -> LoadMode.BODYWEIGHT
        "PESO_CORPORAL_LASTRE" -> LoadMode.BODYWEIGHT_PLUS_LOAD
        "PESO_CORPORAL_ASISTENCIA" -> LoadMode.BODYWEIGHT_MINUS_ASSISTANCE
        "SIN_PESO" -> LoadMode.NO_WEIGHT
        else -> error("Unsupported seed load mode: $value")
    }

    fun measurementUnit(value: String): MeasurementUnit = when (value) {
        "REPETICIONES" -> MeasurementUnit.REPETITIONS
        "REPETICIONES_LADO" -> MeasurementUnit.REPETITIONS_PER_SIDE
        "SEGUNDOS" -> MeasurementUnit.SECONDS
        "SEGUNDOS_LADO" -> MeasurementUnit.SECONDS_PER_SIDE
        else -> error("Unsupported seed measurement unit: $value")
    }
}
