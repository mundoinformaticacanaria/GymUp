package com.mundoinformaticacanaria.gymup.feature.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineRepository
import com.mundoinformaticacanaria.gymup.domain.repository.RoutineSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class RoutineListUiState(
    val isLoading: Boolean = true,
    val routines: List<RoutineSummary> = emptyList(),
)

class RoutineListViewModel(
    routineRepository: RoutineRepository,
) : ViewModel() {
    val uiState: StateFlow<RoutineListUiState> = routineRepository.observeRoutines()
        .map { routines -> RoutineListUiState(isLoading = false, routines = routines) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RoutineListUiState(),
        )

    class Factory(
        private val routineRepository: RoutineRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RoutineListViewModel::class.java))
            return RoutineListViewModel(routineRepository) as T
        }
    }
}
