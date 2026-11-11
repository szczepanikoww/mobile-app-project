package com.trainingapp.app.remote.client

import com.trainingapp.contract.sync.MeasurementChangesDto
import com.trainingapp.contract.sync.MeasurementDto
import com.trainingapp.contract.sync.MeasurementUpsertDto
import com.trainingapp.contract.sync.SetLogDto
import com.trainingapp.contract.sync.SyncOperationDto
import com.trainingapp.contract.sync.SyncOperationType
import com.trainingapp.contract.sync.SyncOutcome
import com.trainingapp.contract.sync.SyncResultDto
import com.trainingapp.contract.sync.WorkoutChangesDto
import com.trainingapp.contract.sync.WorkoutExerciseDto
import com.trainingapp.contract.sync.WorkoutLogDto
import com.trainingapp.contract.sync.WorkoutStateDto
import com.trainingapp.contract.sync.WorkoutSyncRequest
import com.trainingapp.contract.sync.WorkoutSyncResponse
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.data.remote.ApiHttpException
import com.trainingapp.data.remote.ApiOfflineException
import com.trainingapp.data.workout.LocalWorkoutSyncStore
import com.trainingapp.data.workout.SyncEntry
import com.trainingapp.data.workout.SyncEntryKind
import com.trainingapp.data.workout.SyncEntryState
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.workout.CurrentClient
import com.trainingapp.domain.workout.MeasurementRepository
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutRepository
import com.trainingapp.domain.workout.WorkoutSyncState
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Something the server refused to take over or that changed on both sides. Ids only: no workout content. */
data class SyncProblem(val entityId: String, val kind: SyncEntryKind)

enum class SyncStatus {
    /** Everything that could be sent was answered by the server (see the counters for refused items). */
    OK,

    /** No connection. Nothing was lost: the same changes are sent on the next call. */
    OFFLINE,

    /** The server answered with an error ([SyncResult.errorCode]). Local data is untouched. */
    FAILED,
}

data class SyncResult(
    val status: SyncStatus,
    val errorCode: String? = null,
    /** Operations the server wrote (new or changed). */
    val applied: Int = 0,
    /** Operations the server already had. */
    val duplicates: Int = 0,
    /** The server row changed on its own since this device last saw it; kept as CONFLICT until the content changes. */
    val conflicts: List<SyncProblem> = emptyList(),
    /** Not allowed or invalid on the server; kept as REJECTED until the content changes. */
    val rejected: List<SyncProblem> = emptyList(),
    val pulledWorkouts: Int = 0,
    val pushedMeasurements: Int = 0,
    val pulledMeasurements: Int = 0,
    val failedMeasurements: Int = 0,
) {
    val isClean: Boolean get() = status == SyncStatus.OK && conflicts.isEmpty() && rejected.isEmpty() && failedMeasurements == 0
}

/**
 * Pushes the local workout logs and measurements to the server and pulls history back (remote mode only).
 *
 * How nothing is lost or doubled: every log, exercise entry and set has a device-generated UUID. For each entity the
 * store keeps the exact payload and revision the server last acknowledged; a sync sends only entities whose current
 * payload differs, in one batch per 400 operations. A lost response is harmless because the retry sends the same
 * payload and the server answers DUPLICATE for the same ids. Local rows are never rewritten by a sync except the
 * sync-state column (and logs/measurements that exist only on the server are added).
 *
 * Workout logs are discovered through `sync_state <> SYNCED`: [com.trainingapp.data.workout.LocalWorkoutRepository]
 * downgrades a synced log to PENDING on every save, so only changed logs are examined.
 */
class WorkoutSyncer(
    private val api: ApiClient,
    private val workouts: WorkoutRepository,
    private val measurements: MeasurementRepository,
    private val store: LocalWorkoutSyncStore,
    private val client: CurrentClient,
    private val ids: IdGenerator = IdGenerator.Uuids,
    private val timeZone: () -> String = { "Europe/Warsaw" },
) {
    private val mutex = Mutex()
    private val compare = Json { encodeDefaults = true; explicitNulls = false }

    /** One push + pull round. Safe to call repeatedly and concurrently (calls run one after another). */
    suspend fun syncOnce(): SyncResult = mutex.withLock {
        try {
            val push = pushWorkouts()
            if (push.status != SyncStatus.OK) return@withLock push
            val measurementPush = pushMeasurements()
            if (measurementPush.status != SyncStatus.OK) return@withLock push.mergeMeasurements(measurementPush)
            val pulled = pullWorkouts()
            val pulledMeasurements = pullMeasurements()
            push.mergeMeasurements(measurementPush).copy(pulledWorkouts = pulled, pulledMeasurements = pulledMeasurements)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiOfflineException) {
            SyncResult(SyncStatus.OFFLINE)
        } catch (e: ApiHttpException) {
            SyncResult(SyncStatus.FAILED, errorCode = e.code)
        }
    }

    /**
     * After the client chose to keep their own data over the server's: re-arms every CONFLICT entry of [logId] with
     * the server's current revision, so the next sync overwrites the server row with the local one.
     */
    suspend fun keepLocalAfterConflict(logId: String) = mutex.withLock {
        store.entries(client.clientId).filter { it.workoutLogId == logId && it.state == SyncEntryState.CONFLICT }.forEach {
            // The revision already holds the server's; an empty payload makes the entry differ from the local row.
            store.putEntry(it.copy(content = "", state = SyncEntryState.SYNCED))
        }
        store.setLogSyncState(logId, WorkoutSyncState.PENDING)
    }

    /**
     * Syncs when [isOnline] turns true and then every [interval] while it stays true. Cancel the returned job to stop.
     */
    fun startPeriodicSync(scope: CoroutineScope, isOnline: Flow<Boolean>, interval: Duration = 5.minutes): Job = scope.launch {
        isOnline.collectLatest { online ->
            if (!online) return@collectLatest
            while (true) {
                try {
                    syncOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Local data is untouched; the next round retries.
                }
                delay(interval)
            }
        }
    }

    // ---- Push: workout logs ----

    private class Pending(
        val op: SyncOperationDto,
        val kind: SyncEntryKind,
        val logId: String,
        val content: String,
        val discard: Boolean = false,
        val log: WorkoutLogDto? = null,
        val set: SetLogDto? = null,
    )

    private suspend fun pushWorkouts(): SyncResult {
        val clientId = client.clientId
        val entries = store.entries(clientId).associateBy { it.entityId }
        val pending = mutableListOf<Pending>()
        val candidateIds = store.unsyncedLogIds(clientId)
        for (id in candidateIds) {
            val log = workouts.get(id) ?: continue
            pending += plan(log, entries)
        }
        for (orphan in store.orphanLogEntries(clientId)) {
            pending += Pending(
                op = SyncOperationDto(ids.newId(), SyncOperationType.DISCARD_LOG, orphan.entityId, baseRevision = orphan.serverRevision),
                kind = SyncEntryKind.LOG, logId = orphan.entityId, content = "", discard = true,
            )
        }

        var applied = 0
        var duplicates = 0
        val conflicts = mutableListOf<SyncProblem>()
        val rejected = mutableListOf<SyncProblem>()
        for (batch in pending.chunked(BATCH_SIZE)) {
            val response = try {
                api.send(
                    method = HttpMethod.Post,
                    path = "v1/client/workout-sync",
                    body = WorkoutSyncRequest(ids.newId(), batch.map { it.op }),
                    bodySerializer = WorkoutSyncRequest.serializer(),
                    response = WorkoutSyncResponse.serializer(),
                )
            } catch (e: ApiHttpException) {
                batch.map { it.logId }.distinct().forEach { store.setLogSyncState(it, WorkoutSyncState.FAILED) }
                throw e
            } ?: return SyncResult(SyncStatus.FAILED, errorCode = "EMPTY_RESPONSE", applied = applied, duplicates = duplicates, conflicts = conflicts, rejected = rejected)
            val byOperation = response.results.associateBy { it.operationId }
            for (p in batch) {
                val result = byOperation[p.op.operationId] ?: continue // answer missing: stays pending, sent again next time
                when (result.outcome) {
                    SyncOutcome.APPLIED -> applied++
                    SyncOutcome.DUPLICATE -> duplicates++
                    SyncOutcome.CONFLICT -> Unit
                    SyncOutcome.REJECTED -> Unit
                }
                record(p, result, clientId)?.let { (isConflict, problem) -> if (isConflict) conflicts += problem else rejected += problem }
            }
        }

        refreshLogStates(clientId, candidateIds)
        return SyncResult(SyncStatus.OK, applied = applied, duplicates = duplicates, conflicts = conflicts, rejected = rejected)
    }

    /** Operations for the parts of [log] that differ from what the server last acknowledged. */
    private fun plan(log: WorkoutLog, entries: Map<String, SyncEntry>): List<Pending> {
        val bundle = log.toBundle(timeZone())
        val out = mutableListOf<Pending>()

        fun changed(entityId: String, content: String) = entries[entityId]?.content != content

        val logContent = compare.encodeToString(WorkoutLogDto.serializer(), bundle.log)
        if (changed(log.id, logContent)) {
            val type = if (bundle.log.state == WorkoutStateDto.FINISHED) SyncOperationType.FINISH_LOG else SyncOperationType.UPSERT_LOG
            out += Pending(
                SyncOperationDto(ids.newId(), type, log.id, baseRevision = entries[log.id]?.serverRevision ?: 0, log = bundle.log),
                SyncEntryKind.LOG, log.id, logContent, log = bundle.log,
            )
        }
        for (e in bundle.exercises) {
            val content = compare.encodeToString(WorkoutExerciseDto.serializer(), e)
            if (changed(e.id, content)) {
                out += Pending(SyncOperationDto(ids.newId(), SyncOperationType.UPSERT_EXERCISE, e.id, exercise = e), SyncEntryKind.EXERCISE, log.id, content)
            }
        }
        for (s in bundle.sets) {
            val content = compare.encodeToString(SetLogDto.serializer(), s)
            if (changed(s.id, content)) {
                out += Pending(
                    SyncOperationDto(ids.newId(), SyncOperationType.UPSERT_SET, s.id, baseRevision = entries[s.id]?.serverRevision ?: 0, set = s),
                    SyncEntryKind.SET, log.id, content, set = s,
                )
            }
        }
        // Removed locally but known to the server: delete there too (sets first).
        val currentSets = bundle.sets.mapTo(HashSet()) { it.id }
        val currentExercises = bundle.exercises.mapTo(HashSet()) { it.id }
        entries.values.filter { it.workoutLogId == log.id && it.kind == SyncEntryKind.SET && it.entityId !in currentSets }.forEach {
            out += Pending(SyncOperationDto(ids.newId(), SyncOperationType.DELETE_SET, it.entityId, baseRevision = it.serverRevision), SyncEntryKind.SET, log.id, "", discard = true)
        }
        entries.values.filter { it.workoutLogId == log.id && it.kind == SyncEntryKind.EXERCISE && it.entityId !in currentExercises }.forEach {
            out += Pending(SyncOperationDto(ids.newId(), SyncOperationType.DELETE_EXERCISE, it.entityId), SyncEntryKind.EXERCISE, log.id, "", discard = true)
        }
        return out
    }

    /** Stores the server's answer for one operation. Returns the problem (and whether it is a conflict) if there is one. */
    private suspend fun record(p: Pending, result: SyncResultDto, clientId: String): Pair<Boolean, SyncProblem>? {
        val entityId = p.op.entityId
        val problem = SyncProblem(entityId, p.kind)
        fun entry(state: SyncEntryState, revision: Int) = SyncEntry(entityId, p.kind, clientId, p.logId, p.content, revision, state)
        val previousRevision = p.op.baseRevision
        when (result.outcome) {
            SyncOutcome.APPLIED, SyncOutcome.DUPLICATE -> {
                if (p.discard && p.op.type == SyncOperationType.DISCARD_LOG) store.deleteEntriesOfLog(p.logId)
                else if (p.discard) store.deleteEntry(entityId)
                else store.putEntry(entry(SyncEntryState.SYNCED, result.revision ?: previousRevision))
                return null
            }
            SyncOutcome.CONFLICT -> {
                val revision = result.revision ?: previousRevision
                if (!p.discard && sameAsServer(p, result)) {
                    // Only server-owned fields moved (e.g. the technique video was attached): adopt its revision.
                    store.putEntry(entry(SyncEntryState.SYNCED, revision))
                    return null
                }
                if (p.discard) return true to problem
                store.putEntry(entry(SyncEntryState.CONFLICT, revision))
                return true to problem
            }
            SyncOutcome.REJECTED -> {
                when {
                    p.discard && p.op.type == SyncOperationType.DISCARD_LOG -> store.deleteEntriesOfLog(p.logId)
                    p.discard -> store.deleteEntry(entityId)
                    else -> store.putEntry(entry(SyncEntryState.REJECTED, previousRevision))
                }
                return false to problem
            }
        }
    }

    private fun sameAsServer(p: Pending, result: SyncResultDto): Boolean {
        p.log?.let { local ->
            val server = result.serverLog ?: return false
            return server.copy(revision = 0, updatedAt = null, deletedAt = null, assignedPlanId = local.assignedPlanId) == local
        }
        p.set?.let { local ->
            val server = result.serverSet ?: return false
            return server.copy(revision = 0, updatedAt = null, deletedAt = null, techniqueMediaId = local.techniqueMediaId) == local
        }
        return false
    }

    /** Sets each examined log's sync column from what is now stored: CONFLICT, FAILED (refused), SYNCED or PENDING. */
    private suspend fun refreshLogStates(clientId: String, logIds: List<String>) {
        if (logIds.isEmpty()) return
        val entries = store.entries(clientId).groupBy { it.workoutLogId }
        for (id in logIds) {
            val log = workouts.get(id) ?: continue
            val own = entries[id].orEmpty()
            val bundle = log.toBundle(timeZone())
            val byId = own.associateBy { it.entityId }
            fun matches(entityId: String, content: String) = byId[entityId]?.content == content
            val upToDate = matches(id, compare.encodeToString(WorkoutLogDto.serializer(), bundle.log)) &&
                bundle.exercises.all { matches(it.id, compare.encodeToString(WorkoutExerciseDto.serializer(), it)) } &&
                bundle.sets.all { matches(it.id, compare.encodeToString(SetLogDto.serializer(), it)) } &&
                own.size == 1 + bundle.exercises.size + bundle.sets.size
            val state = when {
                own.any { it.state == SyncEntryState.CONFLICT } -> WorkoutSyncState.CONFLICT
                own.any { it.state == SyncEntryState.REJECTED } -> WorkoutSyncState.FAILED
                upToDate -> WorkoutSyncState.SYNCED
                else -> WorkoutSyncState.PENDING
            }
            if (state != log.syncState) store.setLogSyncState(id, state)
        }
    }

    // ---- Push: measurements ----

    private suspend fun pushMeasurements(): SyncResult {
        val clientId = client.clientId
        val known = store.syncedMeasurementIds(clientId)
        var pushed = 0
        var failed = 0
        for (m in measurements.observe(clientId).first()) {
            if (m.id in known) continue
            try {
                api.send<MeasurementUpsertDto, MeasurementDto>(
                    method = HttpMethod.Put,
                    path = "v1/client/measurements/${m.id}",
                    body = MeasurementUpsertDto(m.kind.toDto(), m.valueThousandths.toLong(), kotlin.time.Instant.fromEpochMilliseconds(m.measuredAtEpochMs)),
                    bodySerializer = MeasurementUpsertDto.serializer(),
                    response = MeasurementDto.serializer(),
                )
                store.markMeasurementSynced(clientId, m.id)
                pushed++
            } catch (e: ApiHttpException) {
                if (e.status >= 500) throw e
                failed++ // invalid on the server (e.g. a non-UUID id): skip it, retry next time
            }
        }
        return SyncResult(SyncStatus.OK, pushedMeasurements = pushed, failedMeasurements = failed)
    }

    private fun SyncResult.mergeMeasurements(m: SyncResult) =
        copy(pushedMeasurements = m.pushedMeasurements, failedMeasurements = m.failedMeasurements)

    // ---- Pull ----

    /** Finished workouts the server has and this device does not. Existing local logs are never overwritten. */
    private suspend fun pullWorkouts(): Int {
        val clientId = client.clientId
        val since = store.kv(clientId, CURSOR_WORKOUTS)
        val changes = api.get(
            "v1/client/workouts", WorkoutChangesDto.serializer(),
            query = if (since == null) emptyList() else listOf("since" to since),
        )
        val known = store.entries(clientId).mapTo(HashSet()) { it.entityId }
        val exercises = changes.exercises.groupBy { it.workoutLogId }
        val sets = changes.sets.groupBy { it.workoutExerciseId }
        var added = 0
        for (dto in changes.logs) {
            if (dto.state != WorkoutStateDto.FINISHED || dto.deletedAt != null) continue
            if (dto.id in known || workouts.get(dto.id) != null) continue
            val logExercises = exercises[dto.id].orEmpty()
            val logSets = logExercises.flatMap { sets[it.id].orEmpty() }
            val local = fromServer(clientId, dto, logExercises, logSets)
            workouts.save(local)
            val bundle = local.toBundle(timeZone())
            val revisions = logSets.associate { it.id to it.revision }
            store.putEntry(SyncEntry(local.id, SyncEntryKind.LOG, clientId, local.id, compare.encodeToString(WorkoutLogDto.serializer(), bundle.log), dto.revision, SyncEntryState.SYNCED))
            bundle.exercises.forEach {
                store.putEntry(SyncEntry(it.id, SyncEntryKind.EXERCISE, clientId, local.id, compare.encodeToString(WorkoutExerciseDto.serializer(), it), 0, SyncEntryState.SYNCED))
            }
            bundle.sets.forEach {
                store.putEntry(SyncEntry(it.id, SyncEntryKind.SET, clientId, local.id, compare.encodeToString(SetLogDto.serializer(), it), revisions[it.id] ?: 0, SyncEntryState.SYNCED))
            }
            store.setLogSyncState(local.id, WorkoutSyncState.SYNCED)
            added++
        }
        store.putKv(clientId, CURSOR_WORKOUTS, changes.cursor.toString())
        return added
    }

    private suspend fun pullMeasurements(): Int {
        val clientId = client.clientId
        val since = store.kv(clientId, CURSOR_MEASUREMENTS)
        val changes = api.get(
            "v1/client/measurements", MeasurementChangesDto.serializer(),
            query = if (since == null) emptyList() else listOf("since" to since),
        )
        val local = measurements.observe(clientId).first().mapTo(HashSet()) { it.id }
        var added = 0
        for (dto in changes.measurements) {
            if (dto.deletedAt != null) {
                if (dto.id in local) store.removeMeasurement(clientId, dto.id)
                continue
            }
            if (dto.id !in local) {
                measurements.add(dto.toLocal(clientId))
                added++
            }
            store.markMeasurementSynced(clientId, dto.id)
        }
        store.putKv(clientId, CURSOR_MEASUREMENTS, changes.cursor.toString())
        return added
    }

    private companion object {
        const val BATCH_SIZE = 400
        const val CURSOR_WORKOUTS = "cursor.workouts"
        const val CURSOR_MEASUREMENTS = "cursor.measurements"
    }
}
