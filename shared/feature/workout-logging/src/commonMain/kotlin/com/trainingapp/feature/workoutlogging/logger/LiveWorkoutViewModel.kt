package com.trainingapp.feature.workoutlogging.logger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaRepository
import com.trainingapp.domain.media.VideoProblem
import com.trainingapp.domain.media.VideoValidator
import com.trainingapp.domain.workout.CurrentClient
import com.trainingapp.domain.workout.PreviousPerformance
import com.trainingapp.domain.workout.RestAlarmCommand
import com.trainingapp.domain.workout.RestAlarmPlanner
import com.trainingapp.domain.workout.SetField
import com.trainingapp.domain.workout.SetLog
import com.trainingapp.domain.workout.TechniqueVideoContext
import com.trainingapp.domain.workout.TechniqueVideoOutbox
import com.trainingapp.domain.workout.TodayWorkout
import com.trainingapp.domain.workout.TodayWorkoutSource
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutLogReducer
import com.trainingapp.domain.workout.WorkoutRepository
import com.trainingapp.domain.workout.setFields
import com.trainingapp.feature.workoutbuilder.media.VideoPickResult
import com.trainingapp.feature.workoutlogging.rest.RestAlarm
import com.trainingapp.feature.workoutlogging.rest.RestTimerNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LivePhase { Loading, LoadFailed, NoPlan, NotStarted, Active }

/** A set field the client is typing into. */
data class FieldKey(val setId: String, val field: SetField)

sealed interface LiveDialog {
    data class ConfirmFinish(val uncheckedSets: Int) : LiveDialog
    data object ConfirmDiscard : LiveDialog
    data object VideoSource : LiveDialog

    /** One line before the first system prompt for notifications. */
    data object NotificationRationale : LiveDialog
}

sealed interface LiveMessage {
    data object Restored : LiveMessage
    data object VideoSent : LiveMessage

    /** The video is linked to the set, but there is no chat on this platform to send it to. */
    data object VideoKeptOnSet : LiveMessage
    data class VideoRejected(val problem: VideoProblem) : LiveMessage
    data object VideoUnreadable : LiveMessage
    data object VideoFailed : LiveMessage

    /** Notifications were denied: the in-app timer still works. Shown once, with a link to the settings. */
    data object NotificationsDenied : LiveMessage
}

data class LiveWorkoutUiState(
    val phase: LivePhase = LivePhase.Loading,
    val today: TodayWorkout? = null,
    val log: WorkoutLog? = null,
    val exercises: Map<String, Exercise> = emptyMap(),
    /** "Poprzednio" per exercise id. */
    val previous: Map<String, SetLog> = emptyMap(),
    /** Text being typed, kept as typed ("82,") until it can be read. */
    val drafts: Map<FieldKey, String> = emptyMap(),
    val invalid: Set<FieldKey> = emptySet(),
    /** Media on this device by id: technique videos of sets and exercise demo videos. */
    val media: Map<String, MediaAsset> = emptyMap(),
    /** A write to the device is in progress. */
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val starting: Boolean = false,
    val importingVideo: Boolean = false,
    val dialog: LiveDialog? = null,
    val message: LiveMessage? = null,
    val languageTag: String = "pl",
) {
    fun exerciseName(exerciseId: String): String = exercises[exerciseId]?.name(languageTag).orEmpty()
}

sealed interface LiveWorkoutEvent {
    data class OpenSummary(val workoutLogId: String) : LiveWorkoutEvent
    data object Exit : LiveWorkoutEvent

    /** Ask the platform for notification permission (the UI owns the system prompt). */
    data object RequestNotificationPermission : LiveWorkoutEvent
}

/**
 * A1 "Trening na żywo". Every change goes through [WorkoutLogReducer] and is written to the device at once, so the
 * workout survives process death. The rest timer is the stored end time; the notification follows [RestAlarmPlanner].
 */
class LiveWorkoutViewModel(
    private val workouts: WorkoutRepository,
    private val todaySource: TodayWorkoutSource,
    private val exerciseRepository: ExerciseRepository,
    private val media: MediaRepository,
    private val outbox: TechniqueVideoOutbox,
    private val client: CurrentClient,
    private val notifier: RestTimerNotifier,
    private val time: TimeSource,
    private val ids: IdGenerator,
    language: AppLanguage,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveWorkoutUiState(languageTag = language.current()))
    val state: StateFlow<LiveWorkoutUiState> = _state.asStateFlow()

    private val _events = Channel<LiveWorkoutEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val writes = Mutex()
    private val pendingSaves = Channel<WorkoutLog>(Channel.CONFLATED)
    private var discarded = false
    private var permissionAsked = false

    /** Whether the system already allows notifications; reported by the screen. */
    private var notificationsGranted = true
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            for (log in pendingSaves) {
                val ok = runCatching { writes.withLock { if (!discarded) workouts.save(log) } }
                    .onFailure { if (it is CancellationException) throw it }
                    .isSuccess
                _state.update { it.copy(saving = !pendingSaves.isEmpty, saveFailed = !ok) }
            }
        }
        viewModelScope.launch {
            // Technique videos linked to sets and the trainer's exercise demo videos.
            media.observeAll().collect { assets -> _state.update { s -> s.copy(media = assets) } }
        }
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(phase = LivePhase.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val active = workouts.getActive(client.clientId)
                val today = if (active == null) todaySource.today(client.clientId) else null
                val exerciseIds = active?.exercises?.map { it.exerciseId } ?: today?.day?.exercises?.map { it.exerciseId }.orEmpty()
                val exercises = exerciseIds.distinct().mapNotNull { exerciseRepository.getExercise(it) }.associateBy { it.id }
                val history = workouts.history(client.clientId)
                val previous = exerciseIds.distinct().mapNotNull { id ->
                    PreviousPerformance.lastWorkingSet(id, history, excludeWorkoutId = active?.id)?.let { id to it }
                }.toMap()
                // A workout ended in the summary but not saved there resumes here.
                val restored = active?.let { if (it.finishedAtEpochMs != null) WorkoutLogReducer.resume(it) else it }
                _state.update {
                    it.copy(
                        phase = when {
                            restored != null -> LivePhase.Active
                            today != null -> LivePhase.NotStarted
                            else -> LivePhase.NoPlan
                        },
                        log = restored,
                        today = today,
                        exercises = exercises,
                        previous = previous,
                        message = if (restored != null) LiveMessage.Restored else null,
                    )
                }
                if (restored != null) {
                    if (restored != active) enqueueSave(restored)
                    applyAlarm(RestAlarmPlanner.onRestore(restored, time.nowEpochMs()), restored)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = LivePhase.LoadFailed) }
            }
        }
    }

    /** The screen is shown again, e.g. back from the summary. */
    fun onScreenShown() {
        val log = _state.value.log ?: return
        if (log.finishedAtEpochMs != null && !log.isFinished) mutateLog { WorkoutLogReducer.resume(it) }
    }

    fun start() {
        val s = _state.value
        val today = s.today ?: return
        if (s.starting || s.log != null) return
        _state.update { it.copy(starting = true) }
        val trackingTypes = s.exercises.mapValues { it.value.trackingType } +
            today.day.exercises.map { it.exerciseId }.filterNot { it in s.exercises }.associateWith { TrackingType.WEIGHT_REPS }
        val log = WorkoutLogReducer.startFromPlan(
            id = ids.newId(),
            clientId = client.clientId,
            name = today.day.name.ifBlank { today.planVersion.body.name },
            planVersionId = today.planVersion.id,
            day = today.day,
            trackingTypes = trackingTypes,
            nowEpochMs = time.nowEpochMs(),
            ids = ids,
            sessionId = today.sessionId,
            place = today.place,
        )
        _state.update { it.copy(phase = LivePhase.Active, log = log, starting = false) }
        enqueueSave(log)
    }

    fun onFieldChange(setId: String, field: SetField, text: String) {
        val log = _state.value.log ?: return
        val key = FieldKey(setId, field)
        when (val result = WorkoutLogReducer.edit(log, setId, field, text)) {
            is WorkoutLogReducer.EditResult.Changed -> {
                _state.update { it.copy(drafts = it.drafts + (key to text), invalid = it.invalid - key) }
                persist(result.log)
            }
            WorkoutLogReducer.EditResult.Invalid ->
                _state.update { it.copy(drafts = it.drafts + (key to text), invalid = it.invalid + key) }
        }
    }

    fun onToggleSet(setId: String) {
        val log = _state.value.log ?: return
        val exercise = log.exerciseOfSet(setId) ?: return
        val set = log.set(setId) ?: return
        if (!set.isCompleted && !WorkoutLogReducer.canComplete(exercise, set)) {
            // Point at the missing values instead of silently ignoring the tap.
            val missing = exercise.trackingType.setFields.filter { WorkoutFormatsCheck.isMissing(set, it) }.map { FieldKey(setId, it) }
            _state.update { it.copy(invalid = it.invalid + missing) }
            return
        }
        val updated = WorkoutLogReducer.toggleComplete(log, setId, time.nowEpochMs())
        persist(updated)
        if (updated.rest != null && updated.rest != log.rest) maybeAskForNotifications()
    }

    fun onAddSet(exerciseEntryId: String) = mutateLog { WorkoutLogReducer.addSet(it, exerciseEntryId, ids.newId()) }

    fun onRemoveSet(setId: String) {
        _state.update { s -> s.copy(drafts = s.drafts.filterKeys { it.setId != setId }, invalid = s.invalid.filterNot { it.setId == setId }.toSet()) }
        mutateLog { WorkoutLogReducer.removeSet(it, setId) }
    }

    fun onSelectExercise(index: Int) = mutateLog { WorkoutLogReducer.selectExercise(it, index) }

    fun onAdjustRest(deltaSeconds: Int) = mutateLog { WorkoutLogReducer.adjustRest(it, deltaSeconds, time.nowEpochMs()) }

    fun onSkipRest() = mutateLog { WorkoutLogReducer.skipRest(it) }

    /** The in-app bar reached zero while the app is open: it gives the feedback, so the notification is cancelled. */
    fun onRestEnded() {
        val log = _state.value.log ?: return
        val rest = log.rest ?: return
        if (!rest.isOver(time.nowEpochMs())) return
        applyAlarm(RestAlarmPlanner.onEndedInForeground(log), log)
        val cleared = WorkoutLogReducer.skipRest(log)
        _state.update { it.copy(log = cleared) }
        enqueueSave(cleared)
    }

    fun onFinishClick() {
        val log = _state.value.log ?: return
        val unchecked = log.allSets.count { !it.isCompleted }
        if (unchecked > 0) _state.update { it.copy(dialog = LiveDialog.ConfirmFinish(unchecked)) } else confirmFinish()
    }

    fun confirmFinish() {
        val log = _state.value.log ?: return
        val ended = WorkoutLogReducer.end(log, time.nowEpochMs())
        _state.update { it.copy(dialog = null, log = ended) }
        // Replaces any older queued write, so the ended workout is the last one written.
        pendingSaves.trySend(ended)
        applyAlarm(RestAlarmPlanner.onChange(log, ended, time.nowEpochMs()), ended)
        viewModelScope.launch {
            // The summary reads the stored workout, so it is written before navigating.
            val ok = runCatching { writes.withLock { workouts.save(ended) } }.isSuccess
            if (ok) _events.send(LiveWorkoutEvent.OpenSummary(ended.id)) else _state.update { it.copy(saveFailed = true) }
        }
    }

    fun onDiscardClick() = _state.update { it.copy(dialog = LiveDialog.ConfirmDiscard) }

    fun confirmDiscard() {
        val log = _state.value.log ?: return
        discarded = true
        _state.update { it.copy(dialog = null) }
        notifier.cancel(log.id)
        viewModelScope.launch {
            writes.withLock { runCatching { workouts.delete(log.id) } }
            _events.send(LiveWorkoutEvent.Exit)
        }
    }

    fun onRecordTechniqueClick() = _state.update { it.copy(dialog = LiveDialog.VideoSource) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun onNotificationRationaleAccepted() {
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch { _events.send(LiveWorkoutEvent.RequestNotificationPermission) }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        notificationsGranted = granted
        if (!granted) _state.update { it.copy(message = LiveMessage.NotificationsDenied) }
    }

    /** The screen reports whether notifications are already allowed, so the client is asked at most once. */
    fun onNotificationPermissionKnown(granted: Boolean) {
        notificationsGranted = granted
    }

    /** The set a technique video belongs to: the last ticked set of the current exercise, else its current set. */
    fun videoTargetSetId(log: WorkoutLog): String? {
        val exercise = log.currentExercise ?: return null
        return exercise.sets.lastOrNull { it.isCompleted }?.id ?: exercise.currentSet?.id ?: exercise.sets.firstOrNull()?.id
    }

    fun onVideoPicked(result: VideoPickResult) {
        _state.update { it.copy(dialog = null) }
        when (result) {
            VideoPickResult.Cancelled -> Unit
            VideoPickResult.Unreadable -> _state.update { it.copy(message = LiveMessage.VideoUnreadable) }
            is VideoPickResult.Rejected -> _state.update { it.copy(message = LiveMessage.VideoRejected(result.problem)) }
            is VideoPickResult.Picked -> attachVideo(result)
        }
    }

    private fun attachVideo(result: VideoPickResult.Picked) {
        val log = _state.value.log ?: return
        val setId = videoTargetSetId(log) ?: return
        VideoValidator.validate(result.video, MediaKind.TECHNIQUE_VIDEO)?.let { problem ->
            _state.update { it.copy(message = LiveMessage.VideoRejected(problem)) }
            return
        }
        if (_state.value.importingVideo) return
        _state.update { it.copy(importingVideo = true) }
        viewModelScope.launch {
            try {
                val asset = media.import(result.video, MediaKind.TECHNIQUE_VIDEO, ownerUserId = client.clientId)
                val current = _state.value.log ?: return@launch
                persist(WorkoutLogReducer.attachTechniqueVideo(current, setId, asset.id))
                val exercise = current.exerciseOfSet(setId)
                val setIndex = exercise?.sets?.indexOfFirst { it.id == setId } ?: -1
                val set = current.set(setId)
                val context = TechniqueVideoContext(
                    workoutLogId = current.id,
                    exerciseId = exercise?.exerciseId.orEmpty(),
                    exerciseName = _state.value.exerciseName(exercise?.exerciseId.orEmpty()),
                    setNumber = setIndex + 1,
                    setCount = exercise?.sets?.size ?: 0,
                    weightGrams = set?.weightGrams,
                    reps = set?.reps,
                )
                val sent = outbox.send(client.trainerRelationshipId, asset.id, context)
                _state.update { it.copy(importingVideo = false, message = if (sent) LiveMessage.VideoSent else LiveMessage.VideoKeptOnSet) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importingVideo = false, message = LiveMessage.VideoFailed) }
            }
        }
    }

    fun onRetryVideo(mediaId: String) {
        viewModelScope.launch { runCatching { media.retry(mediaId) } }
    }

    private fun maybeAskForNotifications() {
        if (permissionAsked || notificationsGranted) return
        permissionAsked = true
        _state.update { it.copy(dialog = LiveDialog.NotificationRationale) }
    }

    private inline fun mutateLog(change: (WorkoutLog) -> WorkoutLog) {
        val log = _state.value.log ?: return
        persist(change(log))
    }

    private fun persist(updated: WorkoutLog) {
        val before = _state.value.log
        if (before == updated) return
        _state.update { it.copy(log = updated) }
        applyAlarm(RestAlarmPlanner.onChange(before, updated, time.nowEpochMs()), updated)
        enqueueSave(updated)
    }

    private fun enqueueSave(log: WorkoutLog) {
        _state.update { it.copy(saving = true) }
        pendingSaves.trySend(log)
    }

    private fun applyAlarm(command: RestAlarmCommand, log: WorkoutLog) {
        when (command) {
            RestAlarmCommand.None -> Unit
            is RestAlarmCommand.Cancel -> notifier.cancel(command.workoutLogId)
            is RestAlarmCommand.Schedule -> {
                // "Czas na serię 3: Wyciskanie…": the next unticked set after the rest.
                val exercise = log.exercises.firstOrNull { e -> e.sets.any { !it.isCompleted } }
                val nextSetNumber = exercise?.let { e -> e.currentSet?.let { n -> e.sets.indexOfFirst { it.id == n.id } + 1 } }
                notifier.schedule(
                    RestAlarm(
                        workoutLogId = command.workoutLogId,
                        endsAtEpochMs = command.endsAtEpochMs,
                        nextSetNumber = nextSetNumber,
                        exerciseName = exercise?.let { _state.value.exerciseName(it.exerciseId) }?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    override fun onCleared() {
        pendingSaves.close()
        super.onCleared()
    }
}

/** Which required values a set is missing. */
internal object WorkoutFormatsCheck {
    fun isMissing(set: SetLog, field: SetField): Boolean = when (field) {
        SetField.WEIGHT -> set.weightGrams == null
        SetField.REPS -> (set.reps ?: 0) <= 0
        SetField.RPE -> false
        SetField.DURATION -> (set.durationSeconds ?: 0) <= 0
        SetField.DISTANCE -> (set.distanceMeters ?: 0) <= 0
    }
}
