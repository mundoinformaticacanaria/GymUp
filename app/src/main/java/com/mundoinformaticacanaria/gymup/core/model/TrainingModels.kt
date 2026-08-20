package com.mundoinformaticacanaria.gymup.core.model

enum class SessionOperationalState {
    PLANNED,
    IN_PROGRESS,
    REALIZED,
}

enum class SessionExecutionResult {
    NOT_STARTED,
    PARTIAL,
    COMPLETED,
}

enum class LoadMode {
    KG_TOTAL,
    KG_PER_HAND,
    KG_PER_SIDE,
    BODYWEIGHT,
    BODYWEIGHT_PLUS_LOAD,
    BODYWEIGHT_MINUS_ASSISTANCE,
    NO_WEIGHT,
}

enum class MeasurementUnit {
    REPETITIONS,
    REPETITIONS_PER_SIDE,
    SECONDS,
    SECONDS_PER_SIDE,
}
