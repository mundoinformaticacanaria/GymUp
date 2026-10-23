package com.mundoinformaticacanaria.gymup.feature.sessions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class SessionActionRunnerTest {
    @Test
    fun successfulActionRunsOnceAndReturnsToSession() = runBlocking {
        var actionCalls = 0
        var returnToSessionCalls = 0
        var failureCalls = 0

        executeSessionAction(
            action = { actionCalls += 1 },
            onSuccess = { returnToSessionCalls += 1 },
            onFailure = { failureCalls += 1 },
        )

        assertEquals(1, actionCalls)
        assertEquals(1, returnToSessionCalls)
        assertEquals(0, failureCalls)
    }

    @Test
    fun failedActionRunsOnceAndKeepsExerciseOpen() = runBlocking {
        val expectedError = IllegalStateException("No se pudo finalizar")
        var actionCalls = 0
        var returnToSessionCalls = 0
        var receivedError: Throwable? = null

        executeSessionAction(
            action = {
                actionCalls += 1
                throw expectedError
            },
            onSuccess = { returnToSessionCalls += 1 },
            onFailure = { receivedError = it },
        )

        assertEquals(1, actionCalls)
        assertEquals(0, returnToSessionCalls)
        assertSame(expectedError, receivedError)
    }

    @Test
    fun cancelledActionDoesNotNavigateOrReportBusinessFailure() = runBlocking {
        val cancellation = CancellationException("session deletion cancelled")
        var navigated = false
        var reportedFailure = false
        var caught: Throwable? = null

        try {
            executeSessionAction(
                action = { throw cancellation },
                onSuccess = { navigated = true },
                onFailure = { reportedFailure = true },
            )
        } catch (error: CancellationException) {
            caught = error
        }

        assertSame(cancellation, caught)
        assertFalse(navigated)
        assertFalse(reportedFailure)
    }

    @Test
    fun navigationWaitsUntilSessionReloadCompletes() = runBlocking {
        val events = mutableListOf<String>()
        executeSessionActionAndReload(
            action = { events += "save" },
            reload = { events += "reload"; true },
            onSuccess = { events += "navigate" },
            onFailure = { events += "failure" },
        )
        assertEquals(listOf("save", "reload", "navigate"), events)
    }

    @Test
    fun failedActionDoesNotReloadOrNavigate() = runBlocking {
        val events = mutableListOf<String>()
        executeSessionActionAndReload(
            action = { throw IllegalStateException("No se pudo guardar") },
            reload = { events += "reload"; true },
            onSuccess = { events += "navigate" },
            onFailure = { events += "failure" },
        )
        assertEquals(listOf("failure"), events)
    }

    @Test
    fun failedReloadPreventsNavigation() = runBlocking {
        val events = mutableListOf<String>()
        executeSessionActionAndReload(
            action = { events += "save" },
            reload = { events += "reload"; false },
            onSuccess = { events += "navigate" },
            onFailure = { events += "failure" },
        )
        assertEquals(listOf("save", "reload"), events)
    }

    @Test
    fun navigationDoesNotRunWhileAsynchronousReloadIsPending() = runBlocking {
        val reloadStarted = CompletableDeferred<Unit>()
        val allowReload = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            executeSessionActionAndReload(
                action = { events += "save" },
                reload = {
                    events += "reload-start"
                    reloadStarted.complete(Unit)
                    allowReload.await()
                    events += "reload-end"
                    true
                },
                onSuccess = { events += "navigate" },
                onFailure = { events += "failure" },
            )
        }

        reloadStarted.await()
        assertEquals(listOf("save", "reload-start"), events)
        assertFalse(operation.isCompleted)

        allowReload.complete(Unit)
        operation.await()
        assertEquals(listOf("save", "reload-start", "reload-end", "navigate"), events)
    }


    @Test
    fun cancelledActionPropagatesWithoutReloadOrNavigation() = runBlocking {
        val cancellation = CancellationException("action cancelled")
        val events = mutableListOf<String>()
        var caught: Throwable? = null

        try {
            executeSessionActionAndReload(
                action = { events += "action"; throw cancellation },
                reload = { events += "reload"; true },
                onSuccess = { events += "navigate" },
                onFailure = { events += "failure" },
            )
        } catch (error: CancellationException) {
            caught = error
        }

        assertSame(cancellation, caught)
        assertEquals(listOf("action"), events)
    }

    @Test
    fun cancelledReloadPropagatesWithoutNavigationOrFailureMessage() = runBlocking {
        val cancellation = CancellationException("reload cancelled")
        val events = mutableListOf<String>()
        var caught: Throwable? = null

        try {
            executeSessionActionAndReload(
                action = { events += "save" },
                reload = { events += "reload"; throw cancellation },
                onSuccess = { events += "navigate" },
                onFailure = { events += "failure" },
            )
        } catch (error: CancellationException) {
            caught = error
        }

        assertSame(cancellation, caught)
        assertEquals(listOf("save", "reload"), events)
    }
}
