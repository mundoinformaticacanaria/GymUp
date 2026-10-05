package com.mundoinformaticacanaria.gymup.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mundoinformaticacanaria.gymup.core.model.ThemeMode
import com.mundoinformaticacanaria.gymup.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferencesRepository.themeMode.collect { persistedMode ->
                _themeMode.value = persistedMode
            }
        }
    }

    fun selectThemeMode(mode: ThemeMode) {
        // Update the UI synchronously. DataStore remains the durable source of truth.
        _themeMode.value = mode
        viewModelScope.launch {
            userPreferencesRepository.setThemeMode(mode)
        }
    }

    class Factory(
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppViewModel::class.java))
            return AppViewModel(userPreferencesRepository) as T
        }
    }
}
