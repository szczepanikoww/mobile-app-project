package com.trainingapp.feature.workoutbuilder.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Carries a picker selection from S3/S4/S5 back to the plan editor. The editor opens the picker with a request key;
 * the picker, the detail screen and the exercise form add to that request's selection; finishing hands the
 * selection to the editor, which consumes it once. Selections live in memory only.
 */
class ExercisePickerCoordinator {
    private val selections = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val finished = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Finished selections by request key, for the editor to consume. */
    val results: StateFlow<Map<String, List<String>>> = finished.asStateFlow()

    fun selection(requestKey: String): Flow<List<String>> =
        selections.map { it[requestKey].orEmpty() }.distinctUntilChanged()

    fun toggle(requestKey: String, exerciseId: String, singleSelect: Boolean) = selections.update { all ->
        val current = all[requestKey].orEmpty()
        val next = when {
            exerciseId in current -> current - exerciseId
            singleSelect -> listOf(exerciseId)
            else -> current + exerciseId
        }
        all + (requestKey to next)
    }

    /** Adds without toggling, e.g. an exercise created from the picker. */
    fun select(requestKey: String, exerciseId: String, singleSelect: Boolean = false) = selections.update { all ->
        val current = all[requestKey].orEmpty()
        val next = when {
            singleSelect -> listOf(exerciseId)
            exerciseId in current -> current
            else -> current + exerciseId
        }
        all + (requestKey to next)
    }

    fun finish(requestKey: String) {
        val selected = selections.value[requestKey].orEmpty()
        selections.update { it - requestKey }
        if (selected.isNotEmpty()) finished.update { it + (requestKey to selected) }
    }

    fun cancel(requestKey: String) = selections.update { it - requestKey }

    /** Returns the finished selection once. */
    fun consume(requestKey: String): List<String>? {
        val result = finished.value[requestKey] ?: return null
        finished.update { it - requestKey }
        return result
    }
}

/** Request keys the editor uses: one per draft, day and mode. */
object PickRequest {
    private const val SEPARATOR = "|"

    fun add(draftId: String, dayId: String) = listOf(draftId, dayId, "add").joinToString(SEPARATOR)

    fun replace(draftId: String, dayId: String, plannedExerciseId: String) =
        listOf(draftId, dayId, "replace", plannedExerciseId).joinToString(SEPARATOR)

    data class Parsed(val draftId: String, val dayId: String, val replacePlannedExerciseId: String?)

    fun parse(key: String): Parsed? {
        val parts = key.split(SEPARATOR)
        return when {
            parts.size == 3 && parts[2] == "add" -> Parsed(parts[0], parts[1], null)
            parts.size == 4 && parts[2] == "replace" -> Parsed(parts[0], parts[1], parts[3])
            else -> null
        }
    }
}
