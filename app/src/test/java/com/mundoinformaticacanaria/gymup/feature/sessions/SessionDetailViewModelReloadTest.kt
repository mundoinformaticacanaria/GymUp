package com.mundoinformaticacanaria.gymup.feature.sessions

import android.os.Looper
import com.mundoinformaticacanaria.gymup.domain.repository.SessionRepository
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
}
