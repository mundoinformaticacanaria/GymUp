package com.mundoinformaticacanaria.gymup.domain.export

import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.core.model.LoadMode
import com.mundoinformaticacanaria.gymup.core.model.MeasurementUnit
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.SessionDetail
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingExercise
import com.mundoinformaticacanaria.gymup.domain.repository.TrainingSet
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReportTest {
    @Test
    fun `report keeps target and real separate and never exports unconfirmed actual data`() {
        val detail = realizedDetail()
        val report = SessionReportFactory.create(detail, Instant.parse("2026-08-20T20:00:00Z"))
        val json = SessionReportFactory.encode(report)

        assertEquals(1, report.schemaVersion)
        assertEquals("REALIZED", report.session.operationalState)
        assertEquals("PARTIAL", report.session.executionResult)
        assertTrue(json.contains("\"schema_version\": 1"))
        assertTrue(json.contains("\"target\""))
        assertTrue(json.contains("\"actual\""))
        assertEquals(40.0, report.session.exercises.single().sets.first().actual.load!!, 0.0)
        assertEquals(null, report.session.exercises.single().sets.last().actual.load)
        assertFalse(report.session.exercises.single().sets.last().performed)
        assertEquals(90, report.session.exercises.single().sets.first().restSeconds)
    }

    @Test
    fun `planned session cannot be exported`() {
        val detail = realizedDetail().copy(
            summary = realizedDetail().summary.copy(operationalState = SessionOperationalState.PLANNED),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SessionReportFactory.create(detail, Instant.parse("2026-08-20T20:00:00Z"))
        }
    }

    @Test
    fun `filename keeps date and sanitizes technical invalid characters`() {
        val detail = realizedDetail().copy(summary = realizedDetail().summary.copy(name = "Pierna: fuerte/rápida?"))
        assertEquals("2026-08-20_Pierna_ fuerte_rápida_.json", SessionReportFactory.fileName(detail))
    }

    private fun realizedDetail(): SessionDetail = SessionDetail(
        summary = SessionSummary(
            id = "00000000-0000-0000-0000-000000000001",
            date = LocalDate.of(2026, 8, 20),
            orderInDay = 1,
            name = "Jueves 20/08/2026 S1",
            sessionTypeName = "Fuerza",
            operationalState = SessionOperationalState.REALIZED,
            executionResult = SessionExecutionResult.PARTIAL,
        ),
        generalNote = "Sesión de prueba",
        isAutoName = true,
        sessionTypeId = "00000000-0000-0000-0000-000000000002",
        exercises = listOf(
            TrainingExercise(
                id = "00000000-0000-0000-0000-000000000003",
                exerciseId = "00000000-0000-0000-0000-000000000004",
                position = 1,
                nameEs = "Press banca",
                nameEn = "Bench press",
                muscleGroupName = "Pecho",
                equipmentName = "Barra",
                loadMode = LoadMode.KG_TOTAL,
                measurementUnit = MeasurementUnit.REPETITIONS,
                rirRequired = true,
                description = null,
                exerciseRestSeconds = 90,
                note = null,
                incompleteReason = "falta de tiempo",
                isFinalized = true,
                status = ExerciseExecutionStatus.PARTIAL,
                sets = listOf(
                    TrainingSet(
                        id = "00000000-0000-0000-0000-000000000005",
                        position = 1,
                        loadMode = LoadMode.KG_TOTAL,
                        measurementUnit = MeasurementUnit.REPETITIONS,
                        targetLoad = 40.0,
                        actualLoad = 40.0,
                        targetMeasurement = 10,
                        actualMeasurement = 9,
                        rir = 1,
                        restOverrideSeconds = null,
                        actualConfirmed = true,
                    ),
                    TrainingSet(
                        id = "00000000-0000-0000-0000-000000000006",
                        position = 2,
                        loadMode = LoadMode.KG_TOTAL,
                        measurementUnit = MeasurementUnit.REPETITIONS,
                        targetLoad = 40.0,
                        actualLoad = 999.0,
                        targetMeasurement = 10,
                        actualMeasurement = 99,
                        rir = 0,
                        restOverrideSeconds = 120,
                        actualConfirmed = false,
                    ),
                ),
            ),
        ),
    )
}
