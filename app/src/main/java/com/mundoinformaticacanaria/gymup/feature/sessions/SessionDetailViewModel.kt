package com.mundoinformaticacanaria.gymup.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mundoinformaticacanaria.gymup.domain.repository.MissingRirException
import com.mundoinformaticacanaria.gymup.domain.repository.SessionDetail
import com.mundoinformaticacanaria.gymup.domain.repository.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SessionUiMessage(
    val text: String,
    val isError: Boolean,
    val setId: String? = null,
)

internal data class SessionDetailUiState(
    val detail: SessionDetail? = null,
    val message: SessionUiMessage? = null,
    val isLoading: Boolean = true,
)

internal fun sessionActionErrorText(error: Throwable): String = when (error) {
    is MissingRirException -> "Falta RIR obligatorio en ${error.missingSetIds.size} serie(s)."
    else -> error.message ?: "No se pudo aplicar el cambio."
}

internal class SessionDetailViewModel(
    private val sessionId: String,
    private val sessionRepository: SessionRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            runCatching { sessionRepository.getSessionDetail(sessionId) }
                .onSuccess { detail ->
                    _uiState.update { it.copy(detail = detail, isLoading = false) }
                }
                .onFailure { error ->
                    reportFailure(error)
                    _uiState.update { it.copy(isLoading = false) }
                }
        }
    }

    fun runAction(
        successMessage: String? = null,
        setId: String? = null,
        onSuccess: (() -> Unit)? = null,
        action: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            executeSessionAction(
                action = action,
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(message = successMessage?.let { SessionUiMessage(it, isError = false, setId = setId) })
                    }
                    reload()
                    onSuccess?.invoke()
                },
                onFailure = { reportFailure(it, setId) },
            )
        }
    }

    fun reportFailure(error: Throwable, setId: String? = null) {
        _uiState.update {
            it.copy(message = SessionUiMessage(sessionActionErrorText(error), isError = true, setId = setId))
        }
    }

    class Factory(
        private val sessionId: String,
        private val sessionRepository: SessionRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SessionDetailViewModel::class.java))
            return SessionDetailViewModel(sessionId, sessionRepository) as T
        }
    }
}
