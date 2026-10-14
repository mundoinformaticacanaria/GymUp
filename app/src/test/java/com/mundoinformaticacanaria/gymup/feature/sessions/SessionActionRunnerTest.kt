package com.mundoinformaticacanaria.gymup.feature.sessions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
