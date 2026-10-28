package com.trainingapp.data.exercise

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.trainingapp.contract.exercise.ExerciseDto
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.UpsertExerciseRequest
import com.trainingapp.data.db.Custom_exercise
import com.trainingapp.data.db.DataJson
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.exercise.SyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Global library from the bundled seed plus own exercises from SQLDelight.
 * Own exercises are saved locally first and synced in the background; a sync only marks a row synced if it
 * was not edited again meanwhile, so a newer local change is never lost.
 */
class LocalExerciseRepository(
    private val db: TrainingDatabase,
    seed: ExerciseSeedSource,
    private val remote: ExerciseRemoteDataSource,
    private val network: NetworkMonitor,
    private val time: TimeSource,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : ExerciseRepository {

    private val queries get() = db.exerciseQueries
    private val syncMutex = Mutex()

    private val global = scope.async(io, start = CoroutineStart.LAZY) { ExerciseSeedParser.parse(seed.read()) }

    private val custom: Flow<List<Exercise>> =
        queries.selectAllCustom().asFlow().mapToList(io).map { rows -> rows.map { it.toExercise() } }

    init {
        // Sends whatever is pending each time the device comes back online.
        scope.launch {
            network.isOnline.filter { it }.collect { syncPending() }
        }
    }

    override fun observeExercises(): Flow<List<Exercise>> =
        flow { emit(global.await()) }.combine(custom) { globalList, own -> globalList + own }

    override fun observeExercise(id: String): Flow<Exercise?> =
        observeExercises().map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    override suspend fun getExercise(id: String): Exercise? = withContext(io) {
        queries.selectCustomById(id).executeAsOneOrNull()?.toExercise() ?: global.await().firstOrNull { it.id == id }
    }

    override suspend fun saveCustomExercise(exercise: Exercise) {
        val stamped = exercise.copy(syncState = SyncState.PENDING)
        withContext(io) { write(stamped) }
        scheduleSync(stamped.id)
    }

    override suspend fun setHidden(id: String, hidden: Boolean) {
        val existing = withContext(io) { queries.selectCustomById(id).executeAsOneOrNull()?.toExercise() } ?: return
        val updated = existing.copy(
            status = if (hidden) ExerciseStatus.HIDDEN else ExerciseStatus.ACTIVE,
            syncState = SyncState.PENDING,
            updatedAtEpochMs = maxOf(time.nowEpochMs(), existing.updatedAtEpochMs + 1),
        )
        withContext(io) { write(updated) }
        scheduleSync(id)
    }

    override suspend fun syncExercise(id: String): Boolean = syncMutex.withLock {
        val row = withContext(io) { queries.selectCustomById(id).executeAsOneOrNull() } ?: return false
        if (row.sync_state == SyncState.SYNCED.name) return true
        if (!network.isOnline.value) return false
        val exercise = row.toExercise()
        val sent = runCatching {
            remote.upsert(exercise.toUpsertRequest()) && remote.setHidden(exercise.id, exercise.isHidden)
        }.getOrDefault(false)
        val state = when {
            sent -> SyncState.SYNCED
            network.isOnline.value -> SyncState.FAILED
            else -> SyncState.PENDING
        }
        withContext(io) { queries.updateSyncState(state.name, id, row.updated_at) }
        sent
    }

    override suspend fun syncPending(): List<String> {
        val pending = withContext(io) { queries.selectPendingCustom().executeAsList().map { it.id } }
        return pending.filterNot { syncExercise(it) }
    }

    override fun observeRecentlyUsedIds(): Flow<List<String>> =
        queries.selectRecentlyUsed(limit = RECENT_LIMIT).asFlow().mapToList(io)

    override suspend fun markUsed(ids: List<String>): Unit = withContext(io) {
        val now = time.nowEpochMs()
        // Later ids in the selection count as more recent, so a multi-select keeps its order.
        db.transaction { ids.forEach { queries.markUsed(it, now) } }
    }

    private fun write(exercise: Exercise) {
        queries.upsertCustom(
            id = exercise.id,
            owner_trainer_id = requireNotNull(exercise.ownerTrainerId) { "Only own exercises are stored locally" },
            json = DataJson.encodeToString(ExerciseDto.serializer(), exercise.toDto()),
            sync_state = exercise.syncState.name,
            updated_at = exercise.updatedAtEpochMs,
        )
    }

    private fun scheduleSync(id: String) {
        if (network.isOnline.value) scope.launch { syncExercise(id) }
    }

    private fun Custom_exercise.toExercise(): Exercise =
        Exercise.fromDto(DataJson.decodeFromString(ExerciseDto.serializer(), json), SyncState.valueOf(sync_state))

    private fun Exercise.toUpsertRequest() = UpsertExerciseRequest(
        id = id,
        names = names,
        primaryMuscle = primaryMuscles.first(),
        secondaryMuscles = secondaryMuscles,
        equipment = equipment,
        trackingType = trackingType,
        instructions = instructions,
        demoMediaId = demoMediaId,
    )

    private companion object {
        const val RECENT_LIMIT = 10L
    }
}
