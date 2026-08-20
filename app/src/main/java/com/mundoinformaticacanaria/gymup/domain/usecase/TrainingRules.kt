package com.mundoinformaticacanaria.gymup.domain.usecase

import com.mundoinformaticacanaria.gymup.core.model.ExerciseExecutionStatus
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

fun deriveExerciseStatus(actualConfirmed: List<Boolean>): ExerciseExecutionStatus = when {
    actualConfirmed.isEmpty() || actualConfirmed.none { it } -> ExerciseExecutionStatus.NOT_PERFORMED
    actualConfirmed.all { it } -> ExerciseExecutionStatus.COMPLETED
    else -> ExerciseExecutionStatus.PARTIAL
}

fun deriveSessionExecutionResult(statuses: List<ExerciseExecutionStatus>): SessionExecutionResult = when {
    statuses.isEmpty() || statuses.all { it == ExerciseExecutionStatus.NOT_PERFORMED } -> SessionExecutionResult.NOT_STARTED
    statuses.all { it == ExerciseExecutionStatus.COMPLETED } -> SessionExecutionResult.COMPLETED
    else -> SessionExecutionResult.PARTIAL
}

fun buildAutoSessionName(date: LocalDate, orderInDay: Int): String {
    val locale = Locale.forLanguageTag("es-ES")
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { first ->
        if (first.isLowerCase()) first.titlecase(locale) else first.toString()
    }
    return "$weekday ${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} S$orderInDay"
}

fun hasRealData(actualLoad: Double?, actualMeasurement: Int?, rir: Int?): Boolean =
    actualLoad != null || actualMeasurement != null || rir != null

fun validateRirValue(rir: Int?) {
    require(rir == null || rir in 0..2) { "RIR debe ser 0, 1 o 2" }
}
