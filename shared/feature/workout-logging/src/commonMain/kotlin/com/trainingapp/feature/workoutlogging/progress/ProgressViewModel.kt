package com.trainingapp.feature.workoutlogging.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.workout.BodyMeasurement
import com.trainingapp.domain.workout.CurrentClient
import com.trainingapp.domain.workout.MeasurementKind
import com.trainingapp.domain.workout.MeasurementRepository
import com.trainingapp.domain.workout.MeasurementValidator
import com.trainingapp.domain.workout.ProgressPoint
import com.trainingapp.domain.workout.ProgressRules
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProgressTab { Exercises, Measurements, History, Records }

enum class ProgressStatus { Loading, Content, Error }

enum class MeasurementError { Required, Invalid, OutOfRange, SaveFailed }

data class MeasurementForm(
    val kind: MeasurementKind = MeasurementKind.BODY_WEIGHT,
    val text: String = "",
    val error: MeasurementError? = null,
    val saving: Boolean = false,
)

data class ProgressUiState(
    val status: ProgressStatus = ProgressStatus.Loading,
    val tab: ProgressTab = ProgressTab.Exercises,
    /** Finished workouts, newest first. */
    val history: List<WorkoutLog> = emptyList(),
    val exercises: Map<String, Exercise> = emptyMap(),
    val loggedExerciseIds: List<String> = emptyList(),
    val selectedExerciseId: String? = null,
    /** Best working set per workout of the selected exercise, oldest first (D1: no estimated 1RM). */
    val points: List<ProgressPoint> = emptyList(),
    val records: List<ProgressPoint> = emptyList(),
    val measurements: List<BodyMeasurement> = emptyList(),
    /** The "Dodaj pomiar" sheet, when open. */
    val form: MeasurementForm? = null,
    val languageTag: String = "pl",
) {
    fun exerciseName(id: String) = exercises[id]?.name(languageTag).orEmpty()
}

/** A3 "Postępy": the client's own history, records and measurements. Private data; nothing leaves the device here. */
class ProgressViewModel(
    private val workouts: WorkoutRepository,
    private val measurementRepository: MeasurementRepository,
    private val exerciseRepository: ExerciseRepository,
    private val client: CurrentClient,
    private val time: TimeSource,
    private val ids: IdGenerator,
    language: AppLanguage,
) : ViewModel() {

    private val _state = MutableStateFlow(ProgressUiState(languageTag = language.current()))
    val state: StateFlow<ProgressUiState> = _state.asStateFlow()
    private var observeJob: Job? = null

    init {
        observe()
    }

    fun retry() = observe()

    private fun observe() {
        observeJob?.cancel()
        _state.update { it.copy(status = ProgressStatus.Loading) }
        observeJob = viewModelScope.launch {
            combine(workouts.observeHistory(client.clientId), measurementRepository.observe(client.clientId)) { history, measurements ->
                history to measurements
            }.catch { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(status = ProgressStatus.Error) }
            }.collect { (history, measurements) ->
                val known = _state.value.exercises
                val missing = history.flatMap { log -> log.exercises.map { it.exerciseId } }.distinct().filterNot { it in known }
                val loaded = known + missing.mapNotNull { id -> runCatching { exerciseRepository.getExercise(id) }.getOrNull() }.associateBy { it.id }
                val logged = ProgressRules.loggedExerciseIds(history)
                _state.update { s ->
                    val selected = s.selectedExerciseId?.takeIf { it in logged } ?: logged.firstOrNull()
                    s.copy(
                        status = ProgressStatus.Content,
                        history = history,
                        exercises = loaded,
                        loggedExerciseIds = logged,
                        selectedExerciseId = selected,
                        points = selected?.let { ProgressRules.bestSetPerWorkout(it, history) }.orEmpty(),
                        records = ProgressRules.records(history),
                        measurements = measurements,
                    )
                }
            }
        }
    }

    fun onTab(tab: ProgressTab) = _state.update { it.copy(tab = tab) }

    fun onSelectExercise(id: String) = _state.update {
        it.copy(selectedExerciseId = id, points = ProgressRules.bestSetPerWorkout(id, it.history))
    }

    fun onAddMeasurement() = _state.update { it.copy(form = MeasurementForm()) }

    fun onDismissMeasurement() = _state.update { if (it.form?.saving == true) it else it.copy(form = null) }

    fun onMeasurementKind(kind: MeasurementKind) = _state.update { s -> s.copy(form = s.form?.copy(kind = kind, error = null)) }

    fun onMeasurementText(text: String) = _state.update { s -> s.copy(form = s.form?.copy(text = text, error = null)) }

    fun onSaveMeasurement() {
        val form = _state.value.form ?: return
        if (form.saving) return
        val thousandths = when (val result = MeasurementValidator.validate(form.kind, form.text)) {
            is MeasurementValidator.Result.Valid -> result.thousandths
            MeasurementValidator.Result.Blank -> return setFormError(MeasurementError.Required)
            MeasurementValidator.Result.Invalid -> return setFormError(MeasurementError.Invalid)
            MeasurementValidator.Result.OutOfRange -> return setFormError(MeasurementError.OutOfRange)
        }
        _state.update { s -> s.copy(form = s.form?.copy(saving = true, error = null)) }
        viewModelScope.launch {
            try {
                measurementRepository.add(BodyMeasurement(ids.newId(), client.clientId, form.kind, thousandths, time.nowEpochMs()))
                _state.update { it.copy(form = null, tab = ProgressTab.Measurements) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The typed value stays in the sheet.
                _state.update { s -> s.copy(form = s.form?.copy(saving = false, error = MeasurementError.SaveFailed)) }
            }
        }
    }

    private fun setFormError(error: MeasurementError) = _state.update { s -> s.copy(form = s.form?.copy(error = error)) }
}
