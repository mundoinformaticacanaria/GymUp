package com.mundoinformaticacanaria.gymup.feature.sessions

internal suspend fun executeSessionAction(
    action: suspend () -> Unit,
    onSuccess: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    runCatching { action() }
        .onSuccess { onSuccess() }
        .onFailure(onFailure)
}
