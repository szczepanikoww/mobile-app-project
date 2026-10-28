package com.trainingapp.feature.workoutbuilder.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.plan.GetPlanLibrary
import com.trainingapp.domain.plan.PlanLibraryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface PlanLibraryUiState {
    data object Loading : PlanLibraryUiState
    data object Error : PlanLibraryUiState

    /** [offline]: the list is what this device has cached. */
    data class Content(val items: List<PlanLibraryItem>, val offline: Boolean) : PlanLibraryUiState
}

class PlanLibraryViewModel(
    getLibrary: GetPlanLibrary,
    network: NetworkMonitor,
) : ViewModel() {

    private val reload = MutableStateFlow(0)

    private val items = reload.flatMapLatest {
        getLibrary().map<List<PlanLibraryItem>, List<PlanLibraryItem>?> { it }.catch { emit(null) }
    }

    val state: StateFlow<PlanLibraryUiState> = combine(items, network.isOnline) { list, online ->
        if (list == null) PlanLibraryUiState.Error else PlanLibraryUiState.Content(list, offline = !online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanLibraryUiState.Loading)

    fun retry() {
        reload.value++
    }
}
