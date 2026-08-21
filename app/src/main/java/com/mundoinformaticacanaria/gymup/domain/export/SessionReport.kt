package com.mundoinformaticacanaria.gymup.domain.export

import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.SessionDetail
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SessionReportV1(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("exported_at") val exportedAt: String,
    val session: ReportSession,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class ReportSession(
    val id: String,
    val date: String,
    @SerialName("order_in_day") val orderInDay: Int,
    val name: String,
    @SerialName("session_type") val sessionType: NamedSnapshot,
    @SerialName("operational_state") val operationalState: String,
    @SerialName("execution_result") val executionResult: String,
    @SerialName("general_note") val generalNote: String?,
    val exercises: List<ReportExercise>,
)

@Serializable
data class NamedSnapshot(val id: String, val name: String)

@Serializable
data class ReportExercise(
    @SerialName("session_exercise_id") val sessionExerciseId: String,
    @SerialName("exercise_id") val exerciseId: String,
    val position: Int,
    @SerialName("name_es") val nameEs: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("muscle_group") val muscleGroup: String,
    val equipment: String?,
    @SerialName("load_mode_default") val loadModeDefault: String,
    @SerialName("measurement_unit_default") val measurementUnitDefault: String,
    @SerialName("rir_required") val rirRequired: Boolean,
    @SerialName("execution_state") val executionState: String,
    val note: String?,
    @SerialName("incomplete_reason") val incompleteReason: String?,
    @SerialName("exercise_rest_seconds") val exerciseRestSeconds: Int?,
    val sets: List<ReportSet>,
)

@Serializable
data class ReportSet(
    val id: String,
    val position: Int,
    @SerialName("load_mode") val loadMode: String,
    @SerialName("measurement_unit") val measurementUnit: String,
    val target: ReportValues,
    val actual: ReportValues,
    val performed: Boolean,
    val rir: Int?,
    @SerialName("rest_seconds") val restSeconds: Int?,
)

@Serializable
data class ReportValues(
    val load: Double?,
    val measurement: Int?,
)

object SessionReportFactory {
    private val json = Json {
        prettyPrint = true
        explicitNulls = true
        encodeDefaults = true
    }

    fun create(detail: SessionDetail, exportedAt: Instant = Instant.now()): SessionReportV1 {
        require(detail.summary.operationalState == SessionOperationalState.REALIZED) {
            "Solo las sesiones Realizadas pueden generar informe"
        }
        return SessionReportV1(
            exportedAt = exportedAt.toString(),
            session = ReportSession(
                id = detail.summary.id,
                date = detail.summary.date.toString(),
                orderInDay = detail.summary.orderInDay,
                name = detail.summary.name,
                sessionType = NamedSnapshot(detail.sessionTypeId, detail.summary.sessionTypeName),
                operationalState = detail.summary.operationalState.name,
                executionResult = detail.summary.executionResult.name,
                generalNote = detail.generalNote,
                exercises = detail.exercises.map { exercise ->
                    ReportExercise(
                        sessionExerciseId = exercise.id,
                        exerciseId = exercise.exerciseId,
                        position = exercise.position,
                        nameEs = exercise.nameEs,
                        nameEn = exercise.nameEn,
                        muscleGroup = exercise.muscleGroupName,
                        equipment = exercise.equipmentName,
                        loadModeDefault = exercise.loadMode.name,
                        measurementUnitDefault = exercise.measurementUnit.name,
                        rirRequired = exercise.rirRequired,
                        executionState = exercise.status.name,
                        note = exercise.note,
                        incompleteReason = exercise.incompleteReason,
                        exerciseRestSeconds = exercise.exerciseRestSeconds,
                        sets = exercise.sets.map { set ->
                            ReportSet(
                                id = set.id,
                                position = set.position,
                                loadMode = set.loadMode.name,
                                measurementUnit = set.measurementUnit.name,
                                target = ReportValues(set.targetLoad, set.targetMeasurement),
                                actual = ReportValues(
                                    load = if (set.actualConfirmed) set.actualLoad else null,
                                    measurement = if (set.actualConfirmed) set.actualMeasurement else null,
                                ),
                                performed = set.actualConfirmed,
                                rir = if (set.actualConfirmed) set.rir else null,
                                restSeconds = set.restOverrideSeconds ?: exercise.exerciseRestSeconds,
                            )
                        },
                    )
                },
            ),
        )
    }

    fun encode(report: SessionReportV1): String = json.encodeToString(report)

    fun fileName(detail: SessionDetail): String {
        val safeName = detail.summary.name
            .replace(Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]"), "_")
            .trim()
            .trim('.')
            .ifBlank { "sesion" }
        return "${detail.summary.date}_$safeName.json"
    }
}
