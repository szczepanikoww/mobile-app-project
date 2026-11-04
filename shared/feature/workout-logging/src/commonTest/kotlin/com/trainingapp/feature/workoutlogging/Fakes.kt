package com.trainingapp.feature.workoutlogging

import com.trainingapp.contract.media.MediaKind
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaRepository
import com.trainingapp.domain.media.PickedVideo
import com.trainingapp.domain.workout.BodyMeasurement
import com.trainingapp.domain.workout.CurrentClient
import com.trainingapp.domain.workout.MeasurementRepository
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutRepository
import com.trainingapp.domain.workout.WorkoutState
import com.trainingapp.feature.workoutlogging.rest.RestAlarm
import com.trainingapp.feature.workoutlogging.rest.RestTimerNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class FakeClock(var now: Long = 1_000_000) : TimeSource {
    override fun nowEpochMs() = now
}

class FakeIds : IdGenerator {
    private var next = 0
    override fun newId() = "id${++next}"
}

object TestClient : CurrentClient {
    override val clientId = "client1"
    override val firstName = "Kasia"
    override val trainerRelationshipId = "c1"
}

val Polish = AppLanguage { "pl" }

class FakeWorkouts : WorkoutRepository {
    val logs = MutableStateFlow<Map<String, WorkoutLog>>(emptyMap())
    var saves = 0
    override fun observeActive(clientId: String) = logs.map { m -> m.values.firstOrNull { it.clientId == clientId && it.state == WorkoutState.IN_PROGRESS } }
    override suspend fun getActive(clientId: String) = logs.value.values.firstOrNull { it.clientId == clientId && it.state == WorkoutState.IN_PROGRESS }
    override fun observe(id: String) = logs.map { it[id] }
    override suspend fun get(id: String) = logs.value[id]
    override suspend fun save(log: WorkoutLog) { saves++; logs.value = logs.value + (log.id to log) }
    override suspend fun delete(id: String) { logs.value = logs.value - id }
    override fun observeHistory(clientId: String) = logs.map { m -> m.values.filter { it.clientId == clientId && it.isFinished } }
    override suspend fun history(clientId: String) = logs.value.values.filter { it.clientId == clientId && it.isFinished }
}

class FakeMeasurements : MeasurementRepository {
    val items = MutableStateFlow<List<BodyMeasurement>>(emptyList())
    override fun observe(clientId: String) = items
    override suspend fun add(measurement: BodyMeasurement) { items.value = listOf(measurement) + items.value }
}

object NoExercises : ExerciseRepository {
    override fun observeExercises(): Flow<List<Exercise>> = flowOf(emptyList())
    override fun observeExercise(id: String): Flow<Exercise?> = flowOf(null)
    override suspend fun getExercise(id: String): Exercise? = null
    override suspend fun saveCustomExercise(exercise: Exercise) = Unit
    override suspend fun setHidden(id: String, hidden: Boolean) = Unit
    override suspend fun syncExercise(id: String) = true
    override suspend fun syncPending() = emptyList<String>()
    override fun observeRecentlyUsedIds(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun markUsed(ids: List<String>) = Unit
}

object NoMedia : MediaRepository {
    override fun observe(id: String): Flow<MediaAsset?> = flowOf(null)
    override fun observeAll(): Flow<Map<String, MediaAsset>> = flowOf(emptyMap())
    override suspend fun import(video: PickedVideo, kind: MediaKind, ownerUserId: String?) = error("not used")
    override suspend fun retry(id: String) = Unit
    override suspend fun uploadPending() = Unit
}

class FakeNotifier : RestTimerNotifier {
    val calls = mutableListOf<String>()
    override fun schedule(alarm: RestAlarm) { calls += "schedule:${alarm.endsAtEpochMs}" }
    override fun cancel(workoutLogId: String) { calls += "cancel" }
}
