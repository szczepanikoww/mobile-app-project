package com.trainingapp.feature.workoutbuilder.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.CurrentUser
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.exercise.GetExercise
import com.trainingapp.domain.exercise.SetExerciseHidden
import com.trainingapp.feature.workoutbuilder.common.ExerciseDetailDestination
import com.trainingapp.feature.workoutbuilder.common.ExercisePickerCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaRepository
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ExerciseDetailUiState {
    data object Loading : ExerciseDetailUiState
    data object NotFound : ExerciseDetailUiState
    data class Content(
        val exercise: Exercise,
        val isOwner: Boolean,
        val pickMode: Boolean,
        /** The trainer's demo video, when the exercise has one. */
        val video: MediaAsset? = null,
    ) : ExerciseDetailUiState
}

/** S4. Own exercises can be edited, hidden and shown again; in pick mode the exercise can be added to the day. */
class ExerciseDetailViewModel(
    private val destination: ExerciseDetailDestination,
    getExercise: GetExercise,
    private val setExerciseHidden: SetExerciseHidden,
    private val repository: ExerciseRepository,
    private val coordinator: ExercisePickerCoordinator,
    private val currentUser: CurrentUser,
    private val media: MediaRepository,
) : ViewModel() {

    val state: StateFlow<ExerciseDetailUiState> = getExercise(destination.exerciseId)
        .flatMapLatest { exercise ->
            val mediaId = exercise?.demoMediaId
            val video = if (mediaId == null) flowOf(null) else media.observe(mediaId)
            video.map { asset ->
                if (exercise == null) {
                    ExerciseDetailUiState.NotFound
                } else {
                    ExerciseDetailUiState.Content(
                        exercise = exercise,
                        isOwner = exercise.ownerTrainerId == currentUser.trainerId,
                        pickMode = destination.pickRequestKey != null,
                        video = asset,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseDetailUiState.Loading)

    fun setHidden(hidden: Boolean) {
        viewModelScope.launch { setExerciseHidden(destination.exerciseId, hidden) }
    }

    fun retryVideoUpload() {
        val mediaId = (state.value as? ExerciseDetailUiState.Content)?.video?.id ?: return
        viewModelScope.launch { media.retry(mediaId) }
    }

    fun retrySync() {
        viewModelScope.launch { repository.syncExercise(destination.exerciseId) }
    }

    /** Adds this exercise to the picker's selection and hands the selection to the editor. */
    fun addToDay() {
        val key = destination.pickRequestKey ?: return
        coordinator.select(key, destination.exerciseId)
        coordinator.finish(key)
    }
}
