package com.mundoinformaticacanaria.gymup.feature.history

internal fun historyFilterSummary(
    state: String?, result: String?, type: String?, fromText: String, toText: String,
): String {
    val active = listOfNotNull(
        state?.let { "Estado: $it" },
        result?.let { "Resultado: $it" },
        type?.let { "Tipo: $it" },
        fromText.trim().takeIf(String::isNotEmpty)?.let { "Desde: $it" },
        toText.trim().takeIf(String::isNotEmpty)?.let { "Hasta: $it" },
    )
    return if (active.isEmpty()) "Sin filtros activos" else active.joinToString(" · ")
}
