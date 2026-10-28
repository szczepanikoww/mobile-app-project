package com.trainingapp.feature.workoutbuilder.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.contract.plan.PlanIssue
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.plan.DraftDay
import com.trainingapp.domain.plan.OpenPlanDraft
import com.trainingapp.domain.plan.PlanDraft
import com.trainingapp.domain.plan.PlanDraftAction
import com.trainingapp.domain.plan.PlanDraftMapper
import com.trainingapp.domain.plan.PlanDraftReducer
import com.trainingapp.domain.plan.PlanRepository
import com.trainingapp.domain.plan.PublishOutcome
import com.trainingapp.domain.plan.PublishPlan
import com.trainingapp.domain.plan.SavePlanDraft
import com.trainingapp.domain.plan.alphabetLabel
import com.trainingapp.feature.workoutbuilder.common.DefaultDayNames
import com.trainingapp.feature.workoutbuilder.common.ExercisePickerCoordinator
import com.trainingapp.feature.workoutbuilder.common.PickRequest
import com.trainingapp.feature.workoutbuilder.common.PlanEditorDestination
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlanEditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val draft: PlanDraft? = null,
    val selectedDayId: String? = null,
    val exercises: Map<String, Exercise> = emptyMap(),
    /** Shown after the first "Zapisz plan" and kept up to date while the trainer fixes them. */
    val issues: List<PlanIssue> = emptyList(),
    val publishing: Boolean = false,
    val banner: EditorBanner? = null,
    val conflictLatestVersion: Int? = null,
    val offline: Boolean = false,
    /** The draft has been written to the device at least once. */
    val savedOnDevice: Boolean = false,
) {
    val isNewPlan: Boolean get() = draft?.templateId == null
    val selectedDay: DraftDay? get() = draft?.days?.firstOrNull { it.id == selectedDayId } ?: draft?.days?.firstOrNull()
}

sealed interface EditorBanner {
    data object Invalid : EditorBanner
    data object Offline : EditorBanner
    data class SyncFailed(val exerciseNames: List<String>) : EditorBanner
    data object Failed : EditorBanner
}

sealed interface PlanEditorEffect {
    data class Published(val templateId: String, val versionNumber: Int) : PlanEditorEffect
    data class ExerciseRemoved(val exerciseName: String, val undo: PlanDraftAction.RestoreExercise) : PlanEditorEffect
    data class OpenPicker(val requestKey: String, val singleSelect: Boolean) : PlanEditorEffect

    /** Replace this editor with another draft, e.g. a duplicate. */
    data class OpenDraft(val draftId: String) : PlanEditorEffect
    data object Close : PlanEditorEffect
}

/**
 * S2. Every edit goes through [PlanDraftReducer] and is written to the device at once; "Zapisz plan" publishes an
 * immutable version. While a publish runs, further taps are ignored, and the operation id is stored with the
 * draft before sending so a retry can never publish twice.
 */
class PlanEditorViewModel(
    private val destination: PlanEditorDestination,
    private val openDraft: OpenPlanDraft,
    private val saveDraft: SavePlanDraft,
    private val publishPlan: PublishPlan,
    private val plans: PlanRepository,
    private val exerciseRepository: ExerciseRepository,
    private val coordinator: ExercisePickerCoordinator,
    private val network: NetworkMonitor,
    private val language: AppLanguage,
    private val defaultDayNames: DefaultDayNames,
    private val ids: IdGenerator,
) : ViewModel() {

    private val _state = MutableStateFlow(PlanEditorUiState())
    val state: StateFlow<PlanEditorUiState> = _state.asStateFlow()

    private val _effects = Channel<PlanEditorEffect>(Channel.BUFFERED)
    val effects: Flow<PlanEditorEffect> = _effects.receiveAsFlow()

    private val pendingWrite = MutableStateFlow<PlanDraft?>(null)
    private val writeMutex = Mutex()
    private var showIssues = false
    private var dayNames: List<String> = emptyList()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            exerciseRepository.observeExercises().collect { list ->
                _state.update { it.copy(exercises = list.associateBy(Exercise::id)) }
                revalidate()
            }
        }
        viewModelScope.launch { network.isOnline.collect { online -> _state.update { it.copy(offline = !online) } } }
        viewModelScope.launch {
            // StateFlow keeps only the newest draft, so a burst of keystrokes ends in one write of the latest state.
            pendingWrite.filterNotNull().collect { draft -> writeIfCurrent(draft) }
        }
        viewModelScope.launch { coordinator.results.collect { handlePickResults(it) } }
    }

    private suspend fun load() {
        dayNames = defaultDayNames.names(MAX_DEFAULT_DAY_NAMES)
        val draft = when {
            destination.draftId != null -> openDraft.existingDraft(destination.draftId)
            destination.templateId != null -> openDraft.forTemplate(destination.templateId, decimalSeparator(), ::defaultDayName)
            else -> openDraft.newPlan()
        }
        _state.update {
            if (draft == null) {
                it.copy(loading = false, notFound = true)
            } else {
                it.copy(loading = false, draft = draft, selectedDayId = draft.days.first().id, savedOnDevice = destination.draftId != null)
            }
        }
    }

    fun onAction(action: PlanDraftAction) {
        val current = _state.value.draft ?: return
        if (_state.value.publishing) return
        val next = PlanDraftReducer.reduce(current, action, ids)
        if (next == current) return
        val selectDay = when (action) {
            PlanDraftAction.AddDay -> next.days.last().id
            is PlanDraftAction.DuplicateDay -> next.days.getOrNull(current.days.indexOfFirst { it.id == action.dayId } + 1)?.id
            else -> null
        }
        commit(next, selectDay)
    }

    fun selectDay(dayId: String) = _state.update { it.copy(selectedDayId = dayId) }

    fun removeExercise(dayId: String, plannedExerciseId: String) {
        val day = _state.value.draft?.day(dayId) ?: return
        val index = day.exercises.indexOfFirst { it.id == plannedExerciseId }
        if (index < 0) return
        val removed = day.exercises[index]
        onAction(PlanDraftAction.RemoveExercise(dayId, plannedExerciseId))
        val name = _state.value.exercises[removed.exerciseId]?.name(language.current()).orEmpty()
        _effects.trySend(PlanEditorEffect.ExerciseRemoved(name, PlanDraftAction.RestoreExercise(dayId, index, removed)))
    }

    fun addExercises(dayId: String) {
        val draft = _state.value.draft ?: return
        _effects.trySend(PlanEditorEffect.OpenPicker(PickRequest.add(draft.draftId, dayId), singleSelect = false))
    }

    fun replaceExercise(dayId: String, plannedExerciseId: String) {
        val draft = _state.value.draft ?: return
        _effects.trySend(PlanEditorEffect.OpenPicker(PickRequest.replace(draft.draftId, dayId, plannedExerciseId), singleSelect = true))
    }

    fun publish() {
        val current = _state.value
        val draft = current.draft ?: return
        if (current.publishing) return
        val prepared = if (draft.pendingOperationId == null) draft.copy(pendingOperationId = ids.newId()) else draft
        _state.update { it.copy(publishing = true, banner = null, draft = prepared) }
        viewModelScope.launch {
            writeIfCurrent(prepared)
            val outcome = publishPlan(prepared, ::defaultDayName)
            handleOutcome(prepared, outcome)
        }
    }

    private suspend fun handleOutcome(sent: PlanDraft, outcome: PublishOutcome) {
        when (outcome) {
            is PublishOutcome.Published -> {
                showIssues = false
                // Keep editing on top of the new version. Nothing is written until the next change.
                val next = sent.copy(
                    draftId = ids.newId(),
                    templateId = outcome.templateId,
                    baseVersion = outcome.versionNumber,
                    pendingOperationId = null,
                )
                _state.update { it.copy(publishing = false, draft = next, issues = emptyList(), savedOnDevice = false) }
                _effects.send(PlanEditorEffect.Published(outcome.templateId, outcome.versionNumber))
            }
            is PublishOutcome.Invalid -> {
                showIssues = true
                _state.update { it.copy(publishing = false, issues = outcome.issues, banner = EditorBanner.Invalid) }
            }
            PublishOutcome.Offline -> _state.update { it.copy(publishing = false, banner = EditorBanner.Offline) }
            is PublishOutcome.ExerciseSyncFailed -> {
                val names = outcome.exerciseIds.map { id -> _state.value.exercises[id]?.name(language.current()) ?: id }
                _state.update { it.copy(publishing = false, banner = EditorBanner.SyncFailed(names)) }
            }
            is PublishOutcome.Conflict -> _state.update { it.copy(publishing = false, conflictLatestVersion = outcome.latestVersion) }
            PublishOutcome.Failed -> _state.update { it.copy(publishing = false, banner = EditorBanner.Failed) }
        }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }

    fun dismissConflict() = _state.update { it.copy(conflictLatestVersion = null) }

    /** Conflict: publish the local changes as a separate, new plan. */
    fun saveAsNewPlan() {
        val draft = _state.value.draft ?: return
        _state.update { it.copy(conflictLatestVersion = null) }
        commit(draft.copy(templateId = null, baseVersion = null, pendingOperationId = null))
        publish()
    }

    /** Conflict: drop the local changes and continue from the newest version. */
    fun loadLatestVersion() {
        val draft = _state.value.draft ?: return
        val templateId = draft.templateId ?: return
        _state.update { it.copy(conflictLatestVersion = null) }
        viewModelScope.launch {
            writeMutex.withLock { plans.deleteDraft(draft.draftId) }
            val latest = openDraft.forTemplate(templateId, decimalSeparator(), ::defaultDayName) ?: return@launch
            showIssues = false
            _state.update { it.copy(draft = latest, selectedDayId = latest.days.first().id, issues = emptyList(), banner = null, savedOnDevice = false) }
        }
    }

    fun duplicate(copyName: String) {
        val draft = _state.value.draft ?: return
        viewModelScope.launch {
            val copy = openDraft.duplicate(draft, copyName)
            write(copy)
            _effects.send(PlanEditorEffect.OpenDraft(copy.draftId))
        }
    }

    fun archive() {
        val draft = _state.value.draft ?: return
        val templateId = draft.templateId ?: return
        viewModelScope.launch {
            writeMutex.withLock { plans.deleteDraft(draft.draftId) }
            plans.archive(templateId)
            _effects.send(PlanEditorEffect.Close)
        }
    }

    /** A new plan's draft is deleted; a published plan returns to its latest version. */
    fun discardDraft() {
        val draft = _state.value.draft ?: return
        if (draft.templateId == null) {
            viewModelScope.launch {
                writeMutex.withLock { plans.deleteDraft(draft.draftId) }
                _effects.send(PlanEditorEffect.Close)
            }
        } else {
            _state.update { it.copy(conflictLatestVersion = null) }
            loadLatestVersion()
        }
    }

    private fun commit(next: PlanDraft, selectDay: String? = null) {
        _state.update { state ->
            val selected = selectDay ?: state.selectedDayId?.takeIf { id -> next.days.any { it.id == id } } ?: next.days.first().id
            state.copy(draft = next, selectedDayId = selected)
        }
        pendingWrite.value = next
        revalidate()
    }

    private suspend fun write(draft: PlanDraft) {
        writeMutex.withLock { saveDraft(draft) }
        if (!draft.isPristineNewPlan) _state.update { it.copy(savedOnDevice = true) }
    }

    /**
     * Writes [draft] only if it is still the one being edited. A queued write of an older state must not land
     * after a publish deleted the draft, or it would bring a stale draft back.
     */
    private suspend fun writeIfCurrent(draft: PlanDraft) {
        val written = writeMutex.withLock {
            if (draft != _state.value.draft) return@withLock false
            saveDraft(draft)
            true
        }
        if (written && !draft.isPristineNewPlan) _state.update { it.copy(savedOnDevice = true) }
    }

    private fun revalidate() {
        if (!showIssues) return
        val state = _state.value
        val draft = state.draft ?: return
        val issues = PlanDraftMapper.validate(draft, { state.exercises[it]?.trackingType }, ::defaultDayName).issues
        _state.update {
            it.copy(issues = issues, banner = if (issues.isEmpty() && it.banner == EditorBanner.Invalid) null else it.banner)
        }
    }

    private fun handlePickResults(results: Map<String, List<String>>) {
        val draft = _state.value.draft ?: return
        results.keys.forEach { key ->
            val request = PickRequest.parse(key) ?: return@forEach
            if (request.draftId != draft.draftId) return@forEach
            val picked = coordinator.consume(key) ?: return@forEach
            val replaceId = request.replacePlannedExerciseId
            if (replaceId != null) {
                onAction(PlanDraftAction.ReplaceExercise(request.dayId, replaceId, picked.first()))
            } else {
                onAction(PlanDraftAction.AddExercises(request.dayId, picked))
            }
            viewModelScope.launch { exerciseRepository.markUsed(picked) }
        }
    }

    private fun defaultDayName(index: Int): String = dayNames.getOrNull(index) ?: alphabetLabel(index)

    private fun decimalSeparator(): Char = LocaleFormats.decimalSeparator(language.current())

    private companion object {
        const val MAX_DEFAULT_DAY_NAMES = 26
    }
}
