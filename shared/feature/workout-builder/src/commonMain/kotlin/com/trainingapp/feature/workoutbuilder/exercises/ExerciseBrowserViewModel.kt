package com.trainingapp.feature.workoutbuilder.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.text.SearchText
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseFilter
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.exercise.ExerciseScope
import com.trainingapp.domain.exercise.ExerciseSearch
import com.trainingapp.feature.workoutbuilder.common.ExerciseBrowserDestination
import com.trainingapp.feature.workoutbuilder.common.ExercisePickerCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class SectionKind { RECENT, MINE, ALL, HIDDEN, RESULTS }

data class ExerciseSection(val kind: SectionKind, val exercises: List<Exercise>)

data class ExerciseBrowserUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val filter: ExerciseFilter = ExerciseFilter(),
    val sections: List<ExerciseSection> = emptyList(),
    /** Exercises matching the query and filters. */
    val matchCount: Int = 0,
    val pickMode: Boolean = false,
    val singleSelect: Boolean = false,
    val selection: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && !error && sections.all { it.exercises.isEmpty() }
}

/**
 * S3 in browse mode (from the plan library) or pick mode (from the editor, with multi-select).
 * Search is local, so the library works offline.
 */
class ExerciseBrowserViewModel(
    private val destination: ExerciseBrowserDestination,
    private val repository: ExerciseRepository,
    private val coordinator: ExercisePickerCoordinator,
    private val language: AppLanguage,
) : ViewModel() {

    private val filter = MutableStateFlow(ExerciseFilter())
    private val reload = MutableStateFlow(0)
    private val pickKey = destination.pickRequestKey

    private val exercises = reload.flatMapLatest {
        repository.observeExercises().map<List<Exercise>, List<Exercise>?> { it }.catch { emit(null) }
    }
    private val selection = pickKey?.let { coordinator.selection(it) } ?: flowOf(emptyList())

    val state: StateFlow<ExerciseBrowserUiState> = combine(
        exercises,
        repository.observeRecentlyUsedIds(),
        filter,
        selection,
    ) { all, recentIds, currentFilter, selected ->
        if (all == null) {
            ExerciseBrowserUiState(loading = false, error = true, filter = currentFilter, pickMode = pickKey != null)
        } else {
            val sections = sectionsFor(all, recentIds, currentFilter)
            ExerciseBrowserUiState(
                loading = false,
                filter = currentFilter,
                sections = sections,
                matchCount = sections.filter { it.kind != SectionKind.RECENT && it.kind != SectionKind.HIDDEN }.sumOf { it.exercises.size },
                pickMode = pickKey != null,
                singleSelect = destination.singleSelect,
                selection = selected,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ExerciseBrowserUiState(pickMode = pickKey != null, singleSelect = destination.singleSelect),
    )

    private fun sectionsFor(all: List<Exercise>, recentIds: List<String>, filter: ExerciseFilter): List<ExerciseSection> {
        val tag = language.current()
        if (filter.hasQuery) {
            return listOf(ExerciseSection(SectionKind.RESULTS, ExerciseSearch.search(all, filter, tag)))
        }
        val visible = ExerciseSearch.search(all, filter, tag)
        val byId = all.associateBy { it.id }
        val recent = if (pickKey != null && filter.scope == ExerciseScope.ALL) {
            recentIds.mapNotNull { byId[it] }.filter { !it.isHidden && ExerciseSearch.matchesFilters(it, filter) }
        } else {
            emptyList()
        }
        val mine = visible.filter { it.isCustom }
        val global = visible.filterNot { it.isCustom }
        // Hidden own exercises are reachable from "Moje" so they can be shown again; never in search.
        val hidden = if (filter.scope == ExerciseScope.MINE) {
            all.filter { it.isCustom && it.isHidden && ExerciseSearch.matchesFilters(it, filter) }
                .sortedBy { SearchText.normalize(it.name(tag)) }
        } else {
            emptyList()
        }
        return listOf(
            ExerciseSection(SectionKind.RECENT, recent),
            ExerciseSection(SectionKind.MINE, mine),
            ExerciseSection(SectionKind.ALL, global),
            ExerciseSection(SectionKind.HIDDEN, hidden),
        ).filter { it.exercises.isNotEmpty() }
    }

    fun onQueryChange(query: String) = filter.update { it.copy(query = query) }

    fun onScopeChange(scope: ExerciseScope) = filter.update { it.copy(scope = scope) }

    fun onMusclesChange(muscles: Set<MuscleGroup>) = filter.update { it.copy(muscles = muscles) }

    fun onEquipmentChange(equipment: Set<Equipment>) = filter.update { it.copy(equipment = equipment) }

    fun toggle(exerciseId: String) {
        val key = pickKey ?: return
        coordinator.toggle(key, exerciseId, destination.singleSelect)
    }

    /** Hands the selection to the editor, in the order it was made. */
    fun confirmSelection() {
        pickKey?.let(coordinator::finish)
    }

    /** Leaving the picker without confirming drops the selection. After confirming there is nothing left to drop. */
    override fun onCleared() {
        pickKey?.let(coordinator::cancel)
    }

    fun retry() {
        reload.value++
    }
}
