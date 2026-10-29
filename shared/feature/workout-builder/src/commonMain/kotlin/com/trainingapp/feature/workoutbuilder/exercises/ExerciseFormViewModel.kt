package com.trainingapp.feature.workoutbuilder.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseIssue
import com.trainingapp.contract.exercise.ExerciseValidator
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.common.CurrentUser
import com.trainingapp.domain.exercise.DemoVideoChange
import com.trainingapp.domain.exercise.DuplicateFinder
import com.trainingapp.domain.media.LocalFiles
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaRepository
import com.trainingapp.domain.media.PickedVideo
import com.trainingapp.domain.media.VideoProblem
import com.trainingapp.feature.workoutbuilder.media.VideoPickResult
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseInput
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.exercise.ExerciseUsage
import com.trainingapp.domain.exercise.SaveCustomExercise
import com.trainingapp.domain.exercise.SaveExerciseResult
import com.trainingapp.feature.workoutbuilder.common.ExerciseFormDestination
import com.trainingapp.feature.workoutbuilder.common.ExercisePickerCoordinator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExerciseFormUiState(
    val loading: Boolean,
    val isEdit: Boolean,
    val notFound: Boolean = false,
    val notOwner: Boolean = false,
    val name: String = "",
    val primaryMuscle: MuscleGroup? = null,
    val equipment: Equipment? = null,
    val trackingType: TrackingType = TrackingType.WEIGHT_REPS,
    val secondaryMuscles: List<MuscleGroup> = emptyList(),
    val instructions: String = "",
    val moreDetailsExpanded: Boolean = false,
    /** Errors are shown after the first save attempt and then follow the input. */
    val issues: List<ExerciseIssue> = emptyList(),
    val similar: Exercise? = null,
    val trackingTypeLocked: Boolean = false,
    val trackingLockedAttempt: Boolean = false,
    val saving: Boolean = false,
    val video: FormVideo = FormVideo.None,
    val videoMessage: VideoMessage? = null,
)

/** The demo video in the form. */
sealed interface FormVideo {
    data object None : FormVideo

    /** Picked or recorded now; it is uploaded once the exercise is saved. */
    data class New(val video: PickedVideo) : FormVideo

    /** Already attached to the exercise; [asset] follows its upload state. */
    data class Existing(val mediaId: String, val asset: MediaAsset?) : FormVideo
}

enum class VideoMessage { TOO_LONG, TOO_LARGE, UNREADABLE }

sealed interface ExerciseFormEffect {
    data class Saved(val exerciseId: String) : ExerciseFormEffect
}

/** S5. Create or edit an own exercise, local-first; it appears at once as waiting to sync. */
class ExerciseFormViewModel(
    private val destination: ExerciseFormDestination,
    private val save: SaveCustomExercise,
    private val repository: ExerciseRepository,
    private val usage: ExerciseUsage,
    private val media: MediaRepository,
    private val localFiles: LocalFiles,
    private val coordinator: ExercisePickerCoordinator,
    private val currentUser: CurrentUser,
    private val language: AppLanguage,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ExerciseFormUiState(
            loading = destination.exerciseId != null,
            isEdit = destination.exerciseId != null,
            name = destination.prefillName.orEmpty(),
        ),
    )
    val state: StateFlow<ExerciseFormUiState> = _state.asStateFlow()

    private val _effects = Channel<ExerciseFormEffect>(Channel.BUFFERED)
    val effects: Flow<ExerciseFormEffect> = _effects.receiveAsFlow()

    private var showIssues = false
    private var library: List<Exercise> = emptyList()
    private var originalMediaId: String? = null
    private var saved = false

    init {
        viewModelScope.launch {
            library = repository.observeExercises().first()
            val id = destination.exerciseId
            if (id == null) {
                updateSimilar()
                return@launch
            }
            val existing = repository.getExercise(id)
            when {
                existing == null -> _state.update { it.copy(loading = false, notFound = true) }
                existing.ownerTrainerId != currentUser.trainerId -> _state.update { it.copy(loading = false, notOwner = true) }
                else -> {
                    val tag = language.current()
                    _state.update {
                        it.copy(
                            loading = false,
                            name = existing.name(tag),
                            primaryMuscle = existing.primaryMuscle,
                            equipment = existing.equipment,
                            trackingType = existing.trackingType,
                            secondaryMuscles = existing.secondaryMuscles,
                            instructions = existing.instructions(tag).joinToString("\n"),
                            moreDetailsExpanded = existing.secondaryMuscles.isNotEmpty() || existing.instructions.isNotEmpty(),
                            trackingTypeLocked = usage.isInUse(existing.id),
                            video = existing.demoMediaId?.let { FormVideo.Existing(it, null) } ?: FormVideo.None,
                        )
                    }
                    existing.demoMediaId?.let { mediaId ->
                        originalMediaId = mediaId
                        launch {
                            media.observe(mediaId).collect { asset ->
                                _state.update { s ->
                                    val video = s.video
                                    if (video is FormVideo.Existing && video.mediaId == mediaId) s.copy(video = video.copy(asset = asset)) else s
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun onNameChange(name: String) {
        _state.update { it.copy(name = name) }
        updateSimilar()
        revalidate()
    }

    fun onPrimaryMuscleChange(muscle: MuscleGroup) = edit { it.copy(primaryMuscle = muscle, secondaryMuscles = it.secondaryMuscles - muscle) }

    fun onEquipmentChange(equipment: Equipment) = edit { it.copy(equipment = equipment) }

    fun onTrackingTypeChange(type: TrackingType) {
        if (_state.value.trackingTypeLocked) return
        edit { it.copy(trackingType = type) }
    }

    fun onSecondaryMusclesChange(muscles: Set<MuscleGroup>) =
        edit { state -> state.copy(secondaryMuscles = MuscleGroup.entries.filter { it in muscles && it != state.primaryMuscle }) }

    fun onInstructionsChange(text: String) = edit { it.copy(instructions = text) }

    fun toggleMoreDetails() = _state.update { it.copy(moreDetailsExpanded = !it.moreDetailsExpanded) }

    fun onVideoPicked(result: VideoPickResult) {
        when (result) {
            is VideoPickResult.Picked -> {
                discardNewVideo()
                _state.update { it.copy(video = FormVideo.New(result.video), videoMessage = null) }
            }
            is VideoPickResult.Rejected -> _state.update {
                it.copy(videoMessage = if (result.problem == VideoProblem.TOO_LONG) VideoMessage.TOO_LONG else VideoMessage.TOO_LARGE)
            }
            VideoPickResult.Unreadable -> _state.update { it.copy(videoMessage = VideoMessage.UNREADABLE) }
            VideoPickResult.Cancelled -> Unit
        }
    }

    fun removeVideo() {
        discardNewVideo()
        _state.update { it.copy(video = FormVideo.None, videoMessage = null) }
    }

    fun retryVideoUpload() {
        val video = _state.value.video as? FormVideo.Existing ?: return
        viewModelScope.launch { media.retry(video.mediaId) }
    }

    /** A video picked in this form and not saved is deleted from the device. */
    private fun discardNewVideo() {
        val video = _state.value.video as? FormVideo.New ?: return
        localFiles.delete(video.video.localPath)
        video.video.thumbnailPath?.let(localFiles::delete)
    }

    override fun onCleared() {
        if (!saved) discardNewVideo()
    }

    fun submit() {
        val current = _state.value
        if (current.saving || current.loading) return
        showIssues = true
        revalidate()
        if (_state.value.issues.isNotEmpty()) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val input = ExerciseInput(
                name = current.name,
                primaryMuscle = current.primaryMuscle,
                equipment = current.equipment,
                trackingType = current.trackingType,
                secondaryMuscles = current.secondaryMuscles,
                instructions = current.instructions,
                demoVideo = when (val video = current.video) {
                    FormVideo.None -> if (originalMediaId != null) DemoVideoChange.Remove else DemoVideoChange.Keep
                    is FormVideo.New -> DemoVideoChange.Replace(video.video)
                    is FormVideo.Existing -> DemoVideoChange.Keep
                },
            )
            when (val result = save(destination.exerciseId, input, language.current())) {
                is SaveExerciseResult.Saved -> {
                    saved = true
                    destination.pickRequestKey?.let { coordinator.select(it, result.exercise.id) }
                    _effects.send(ExerciseFormEffect.Saved(result.exercise.id))
                }
                is SaveExerciseResult.Invalid -> _state.update { it.copy(saving = false, issues = result.issues) }
                SaveExerciseResult.TrackingTypeLocked -> _state.update { it.copy(saving = false, trackingTypeLocked = true, trackingLockedAttempt = true) }
                SaveExerciseResult.NotOwner -> _state.update { it.copy(saving = false, notOwner = true) }
                SaveExerciseResult.NotFound -> _state.update { it.copy(saving = false, notFound = true) }
                is SaveExerciseResult.VideoInvalid -> _state.update {
                    it.copy(saving = false, videoMessage = if (result.problem == VideoProblem.TOO_LONG) VideoMessage.TOO_LONG else VideoMessage.TOO_LARGE)
                }
            }
        }
    }

    private inline fun edit(transform: (ExerciseFormUiState) -> ExerciseFormUiState) {
        _state.update(transform)
        revalidate()
    }

    private fun revalidate() {
        if (!showIssues) return
        val s = _state.value
        val issues = ExerciseValidator.validate(s.name, s.primaryMuscle, s.equipment, s.trackingType, s.secondaryMuscles, s.instructions)
        _state.update { it.copy(issues = issues) }
    }

    private fun updateSimilar() {
        val similar = DuplicateFinder.findSimilar(_state.value.name, library, excludeId = destination.exerciseId)
        _state.update { it.copy(similar = similar) }
    }
}
