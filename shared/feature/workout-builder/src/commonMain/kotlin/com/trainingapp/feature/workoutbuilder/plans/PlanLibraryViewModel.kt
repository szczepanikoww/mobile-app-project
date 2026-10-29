package com.trainingapp.feature.workoutbuilder.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.plan.GetPlanLibrary
import com.trainingapp.domain.plan.PlanLibraryItem
import com.trainingapp.domain.plan.PlanLibrarySearch
import com.trainingapp.domain.plan.PlanListFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface PlanLibraryUiState {
    data object Loading : PlanLibraryUiState
    data object Error : PlanLibraryUiState

    /** [offline]: the list is what this device has cached. */
    data class Content(
        val items: List<PlanLibraryItem>,
        val totalCount: Int,
        val counts: Map<PlanListFilter, Int>,
        val query: String,
        val filter: PlanListFilter,
        val exercises: Map<String, Exercise>,
        val offline: Boolean,
    ) : PlanLibraryUiState
}

class PlanLibraryViewModel(
    getLibrary: GetPlanLibrary,
    exerciseRepository: ExerciseRepository,
    network: NetworkMonitor,
) : ViewModel() {

    private val reload = MutableStateFlow(0)
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(PlanListFilter.ALL)

    private val items = reload.flatMapLatest {
        getLibrary().map<List<PlanLibraryItem>, List<PlanLibraryItem>?> { it }.catch { emit(null) }
    }
    private val exercises = exerciseRepository.observeExercises()
        .map { list -> list.associateBy(Exercise::id) }
        .catch { emit(emptyMap()) }
        .onStart { emit(emptyMap()) }

    val state: StateFlow<PlanLibraryUiState> = combine(items, exercises, query, filter, network.isOnline) { list, byId, q, f, online ->
        if (list == null) {
            PlanLibraryUiState.Error
        } else {
            PlanLibraryUiState.Content(
                items = PlanLibrarySearch.search(list, q, f),
                totalCount = list.size,
                counts = PlanListFilter.entries.associateWith { chip -> list.count { PlanLibrarySearch.matches(it, chip) } },
                query = q,
                filter = f,
                exercises = byId,
                offline = !online,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanLibraryUiState.Loading)

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onFilterChange(value: PlanListFilter) {
        filter.value = value
    }

    fun retry() {
        reload.value++
    }
}
