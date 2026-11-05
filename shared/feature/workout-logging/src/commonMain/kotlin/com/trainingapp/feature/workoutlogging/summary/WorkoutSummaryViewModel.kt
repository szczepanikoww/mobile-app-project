package com.trainingapp.feature.workoutlogging.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.workout.CurrentClient
import com.trainingapp.domain.workout.PersonalRecord
import com.trainingapp.domain.workout.PersonalRecords
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutLogReducer
import com.trainingapp.domain.workout.WorkoutRepository
import com.trainingapp.domain.workout.WorkoutSummary
import com.trainingapp.feature.workoutlogging.WorkoutSummaryDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SummaryStatus { Loading, NotFound, Content }

data class WorkoutSummaryUiState(
    val status: SummaryStatus = SummaryStatus.Loading,
    val log: WorkoutLog? = null,
    val summary: WorkoutSummary? = null,
    val records: List<PersonalRecord> = emptyList(),
    val exercises: Map<String, Exercise> = emptyMap(),
    val note: String = "",
    /** Opened from the history: nothing can be changed. */
    val readOnly: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val clientFirstName: String = "",
    val languageTag: String = "pl",
) {
    fun exerciseName(id: String) = exercises[id]?.name(languageTag).orEmpty()
}

sealed interface WorkoutSummaryEvent {
    data object Saved : WorkoutSummaryEvent
}

/** A2 "Podsumowanie treningu". "Zapisz trening" finishes the workout on the device once, even on a double tap. */
class WorkoutSummaryViewModel(
    private val destination: WorkoutSummaryDestination,
    private val workouts: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val client: CurrentClient,
    private val time: TimeSource,
    language: AppLanguage,
) : ViewModel() {

    private val _state = MutableStateFlow(
        WorkoutSummaryUiState(readOnly = destination.readOnly, clientFirstName = client.firstName, languageTag = language.current()),
    )
    val state: StateFlow<WorkoutSummaryUiState> = _state.asStateFlow()

    private val _events = Channel<WorkoutSummaryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var saved = false

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(status = SummaryStatus.Loading) }
        viewModelScope.launch {
            try {
                val log = workouts.get(destination.workoutLogId)
                if (log == null) {
                    _state.update { it.copy(status = SummaryStatus.NotFound) }
                    return@launch
                }
                val history = workouts.history(client.clientId).filter { it.id != log.id }
                val exercises = log.exercises.map { it.exerciseId }.distinct().mapNotNull { exerciseRepository.getExercise(it) }.associateBy { it.id }
                val now = time.nowEpochMs()
                // A summary opened before saving compares as if the workout ended at its "Zakończ" time.
                val asFinished = if (log.isFinished) log else WorkoutLogReducer.finish(log, now)
                _state.update {
                    it.copy(
                        status = SummaryStatus.Content,
                        log = log,
                        summary = WorkoutSummary.of(asFinished, now),
                        records = PersonalRecords.find(asFinished, history),
                        exercises = exercises,
                        note = if (it.log == null) log.note else it.note,
                        readOnly = destination.readOnly || log.isFinished,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = SummaryStatus.NotFound) }
            }
        }
    }

    fun onNoteChange(text: String) {
        if (_state.value.readOnly) return
        _state.update { it.copy(note = text.take(WorkoutLogReducer.NOTE_MAX_LENGTH)) }
    }

    fun save() {
        val s = _state.value
        val log = s.log ?: return
        if (s.saving || s.readOnly || saved) return
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                val finished = WorkoutLogReducer.finish(WorkoutLogReducer.setNote(log, s.note), time.nowEpochMs())
                workouts.save(finished)
                saved = true
                _state.update { it.copy(saving = false, log = finished) }
                _events.send(WorkoutSummaryEvent.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The note stays in the field; the workout is still on the device as unfinished.
                _state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }
}
