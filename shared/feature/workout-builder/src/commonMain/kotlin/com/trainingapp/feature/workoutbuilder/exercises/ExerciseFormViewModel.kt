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
import com.trainingapp.domain.exercise.DuplicateFinder
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
)

sealed interface ExerciseFormEffect {
    data class Saved(val exerciseId: String) : ExerciseFormEffect
}

/** S5. Create or edit an own exercise, local-first; it appears at once as waiting to sync. */
class ExerciseFormViewModel(
    private val destination: ExerciseFormDestination,
    private val save: SaveCustomExercise,
    private val repository: ExerciseRepository,
    private val usage: ExerciseUsage,
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
                        )
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
            )
            when (val result = save(destination.exerciseId, input, language.current())) {
                is SaveExerciseResult.Saved -> {
                    destination.pickRequestKey?.let { coordinator.select(it, result.exercise.id) }
                    _effects.send(ExerciseFormEffect.Saved(result.exercise.id))
                }
                is SaveExerciseResult.Invalid -> _state.update { it.copy(saving = false, issues = result.issues) }
                SaveExerciseResult.TrackingTypeLocked -> _state.update { it.copy(saving = false, trackingTypeLocked = true, trackingLockedAttempt = true) }
                SaveExerciseResult.NotOwner -> _state.update { it.copy(saving = false, notOwner = true) }
                SaveExerciseResult.NotFound -> _state.update { it.copy(saving = false, notFound = true) }
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
