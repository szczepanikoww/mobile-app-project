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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import com.trainingapp.domain.exercise.TrainerExerciseNote
import com.trainingapp.domain.exercise.TrainerExerciseNotes
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.domain.media.PickedVideo
import kotlinx.coroutines.launch

sealed interface ExerciseDetailUiState {
    data object Loading : ExerciseDetailUiState
    data object NotFound : ExerciseDetailUiState
    data class Content(
        val exercise: Exercise,
        val isOwner: Boolean,
        val pickMode: Boolean,
        /** The trainer's demo video, when the exercise has one (own exercise) or the trainer added one ("Od trenera"). */
        val video: MediaAsset? = null,
        /** The signed-in trainer's note; null for clients, who do not see it here. */
        val trainerNote: TrainerExerciseNote? = null,
        val noteSaving: Boolean = false,
        val noteFailed: Boolean = false,
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
    private val notes: TrainerExerciseNotes,
) : ViewModel() {

    private data class NoteState(val note: TrainerExerciseNote? = null, val saving: Boolean = false, val failed: Boolean = false)
    private val note = MutableStateFlow(NoteState())

    init {
        // Only a trainer has notes; a failed load leaves the section empty rather than blocking the screen.
        if (currentUser.trainerId != null) viewModelScope.launch {
            val loaded = runCatching { notes.load(destination.exerciseId) }.getOrNull()
            note.update { it.copy(note = loaded ?: TrainerExerciseNote(destination.exerciseId)) }
        }
    }

    val state: StateFlow<ExerciseDetailUiState> = combine(getExercise(destination.exerciseId), note) { exercise, note -> exercise to note }
        .flatMapLatest { (exercise, noteState) ->
            // The trainer's own video comes first: the exercise's demo (own exercise), else the note's video.
            val mediaId = exercise?.demoMediaId ?: noteState.note?.demoMediaId
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
                        trainerNote = noteState.note,
                        noteSaving = noteState.saving,
                        noteFailed = noteState.failed,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseDetailUiState.Loading)

    /** Saves the note text, keeping the video. */
    fun saveNote(text: String) {
        val current = note.value.note ?: return
        persist(current.copy(note = text.trim()))
    }

    /** "Dodaj swoje wideo": the video uploads in the background; the note refers to it at once. */
    fun setVideo(video: PickedVideo) {
        val current = note.value.note ?: return
        viewModelScope.launch {
            val asset = runCatching { media.import(video, MediaKind.EXERCISE_DEMO) }.getOrNull()
            if (asset == null) note.update { it.copy(failed = true) } else persist(current.copy(demoMediaId = asset.id))
        }
    }

    fun removeVideo() {
        val current = note.value.note ?: return
        persist(current.copy(demoMediaId = null))
    }

    private fun persist(updated: TrainerExerciseNote) {
        if (note.value.saving) return
        note.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            try {
                val saved = notes.save(updated)
                note.update { NoteState(saved) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                note.update { it.copy(saving = false, failed = true) }
            }
        }
    }

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
