package com.trainingapp.feature.client.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.workout.CurrentClient
import com.trainingapp.domain.workout.TodayWorkoutSource
import com.trainingapp.domain.workout.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayWorkoutUiState(
    val loading: Boolean = true,
    /** The plan day's name, e.g. "Push A"; null when there is no plan for today. */
    val dayName: String? = null,
    val exerciseCount: Int = 0,
    /** An unfinished workout on this device: the action becomes "Wróć do treningu". */
    val inProgress: Boolean = false,
    val inProgressName: String? = null,
)

/** "Dzisiejszy trening" on the client's Start: today's plan day or the workout in progress (A1 opens it). */
class TodayWorkoutViewModel(
    private val today: TodayWorkoutSource,
    private val workouts: WorkoutRepository,
    private val client: CurrentClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TodayWorkoutUiState())
    val state: StateFlow<TodayWorkoutUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val workout = today.today(client.clientId)
                mutableState.update {
                    it.copy(loading = false, dayName = workout?.day?.name, exerciseCount = workout?.day?.exercises?.size ?: 0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false) }
            }
        }
        viewModelScope.launch {
            workouts.observeActive(client.clientId).collect { active ->
                mutableState.update { it.copy(inProgress = active != null, inProgressName = active?.name) }
            }
        }
    }
}
