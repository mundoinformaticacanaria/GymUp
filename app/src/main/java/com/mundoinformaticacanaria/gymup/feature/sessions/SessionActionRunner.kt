package com.mundoinformaticacanaria.gymup.feature.sessions

import kotlinx.coroutines.CancellationException

internal suspend fun executeSessionAction(
    action: suspend () -> Unit,
    onSuccess: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    val result = runCatching { action() }
    // Cancellation is control flow: never turn a cancelled screen operation
    // into a repository error or a success/navigation event.
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    result.onSuccess { onSuccess() }.onFailure(onFailure)
}

/**
 * Do not navigate away from the exercise until the persisted change has been
 * reloaded into UI state. A failed refresh leaves the caller on the same screen
 * with the refresh error already reported by [reload].
 */
internal suspend fun executeSessionActionAndReload(
    action: suspend () -> Unit,
    reload: suspend () -> Boolean,
    onSuccess: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    var actionSucceeded = false
    executeSessionAction(
        action = action,
        onSuccess = { actionSucceeded = true },
        onFailure = onFailure,
    )
    if (actionSucceeded && reload()) {
        onSuccess()
    }
}
