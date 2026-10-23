package com.mundoinformaticacanaria.gymup.feature.sessions

import android.os.Looper
import com.mundoinformaticacanaria.gymup.core.model.SessionExecutionResult
import com.mundoinformaticacanaria.gymup.core.model.SessionOperationalState
import com.mundoinformaticacanaria.gymup.domain.repository.SessionDetail
import com.mundoinformaticacanaria.gymup.domain.repository.SessionSummary
import com.mundoinformaticacanaria.gymup.domain.repository.SessionRepository
import java.time.LocalDate
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionDetailViewModelReloadTest {
    @Test
    fun successfulWriteReloadsUpdatedSessionBeforeSuccessCallback() {
        var writeCompleted = false
        var reloadCalls = 0
        val repository = Proxy.newProxyInstance(
            SessionRepository::class.java.classLoader,
            arrayOf(SessionRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSessionDetail" -> {
                    reloadCalls++
                    SessionDetail(
                        summary = SessionSummary(
                            id = "session",
                            date = LocalDate.of(2026, 10, 23),
                            orderInDay = 1,
                            name = "Sesión",
                            sessionTypeName = "Fuerza",
                            operationalState = if (writeCompleted) {
                                SessionOperationalState.IN_PROGRESS
                            } else {
                                SessionOperationalState.PLANNED
                            },
                            executionResult = SessionExecutionResult.NOT_STARTED,
                        ),
                        generalNote = null,
                        isAutoName = false,
                        sessionTypeId = "type",
                        exercises = emptyList(),
                    )
                }
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SessionRepository

        val viewModel = SessionDetailViewModel("session", repository)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SessionOperationalState.PLANNED, viewModel.uiState.value.detail?.summary?.operationalState)

        var stateObservedByCallback: SessionOperationalState? = null
        viewModel.runAction(
            successMessage = "Sesión iniciada.",
            onSuccess = { stateObservedByCallback = viewModel.uiState.value.detail?.summary?.operationalState },
        ) { writeCompleted = true }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, reloadCalls)
        assertEquals(SessionOperationalState.IN_PROGRESS, stateObservedByCallback)
        assertEquals(SessionOperationalState.IN_PROGRESS, viewModel.uiState.value.detail?.summary?.operationalState)
        assertEquals("Sesión iniciada.", viewModel.uiState.value.message?.text)
        assertEquals(false, viewModel.uiState.value.message?.isError)
    }

    @Test
    fun missingSessionDoesNotCountAsSuccessfulReloadOrNavigate() {
        // getSessionDetail is nullable: a successful suspend call may return null.
        val repository = Proxy.newProxyInstance(
            SessionRepository::class.java.classLoader,
            arrayOf(SessionRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSessionDetail" -> null
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SessionRepository

        val viewModel = SessionDetailViewModel("missing-session", repository)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(viewModel.uiState.value.detail)
        assertEquals("Sesión no disponible.", viewModel.uiState.value.message?.text)

        var navigated = false
        viewModel.runAction(onSuccess = { navigated = true }) { /* saved by repository */ }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(navigated)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("Sesión no disponible.", viewModel.uiState.value.message?.text)
    }

    @Test
    fun repositoryReloadExceptionPreventsNavigationAndShowsError() {
        val repository = Proxy.newProxyInstance(
            SessionRepository::class.java.classLoader,
            arrayOf(SessionRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSessionDetail" -> throw IllegalStateException("No se pudo recargar")
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SessionRepository

        val viewModel = SessionDetailViewModel("session-with-error", repository)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        var navigated = false
        viewModel.runAction(onSuccess = { navigated = true }) { /* successful write */ }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(navigated)
        assertNull(viewModel.uiState.value.detail)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("No se pudo recargar", viewModel.uiState.value.message?.text)
        assertEquals(true, viewModel.uiState.value.message?.isError)
    }

    @Test
    fun failedWriteDoesNotTriggerReloadOrNavigation() {
        var reloadCalls = 0
        val repository = Proxy.newProxyInstance(
            SessionRepository::class.java.classLoader,
            arrayOf(SessionRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSessionDetail" -> {
                    reloadCalls++
                    null
                }
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as SessionRepository

        val viewModel = SessionDetailViewModel("session-with-failed-write", repository)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, reloadCalls)

        var navigated = false
        viewModel.runAction(onSuccess = { navigated = true }) {
            throw IllegalStateException("No se pudo guardar")
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(navigated)
        assertEquals(1, reloadCalls)
        assertEquals("No se pudo guardar", viewModel.uiState.value.message?.text)
        assertEquals(true, viewModel.uiState.value.message?.isError)
    }
}
