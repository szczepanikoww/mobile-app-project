package com.trainingapp.data.workout

import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.workout.WorkoutSyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

enum class SyncEntryKind { LOG, EXERCISE, SET }

enum class SyncEntryState { SYNCED, CONFLICT, REJECTED }

/** What the server last acknowledged (or refused) for one entity. [content] is the exact payload sent. */
data class SyncEntry(
    val entityId: String,
    val kind: SyncEntryKind,
    val clientId: String,
    val workoutLogId: String,
    val content: String,
    val serverRevision: Int,
    val state: SyncEntryState,
)

/**
 * Sync bookkeeping next to the workout tables: which payload of each log, exercise entry and set the server has,
 * which measurements it has, and pull cursors. Holds no workout content itself and is safe to call repeatedly.
 */
class LocalWorkoutSyncStore(
    private val db: TrainingDatabase,
    private val io: CoroutineDispatcher,
) {
    private val q get() = db.workoutSyncQueries

    suspend fun entries(clientId: String): List<SyncEntry> = withContext(io) {
        q.selectEntriesOfClient(clientId).executeAsList().map { it.toEntry() }
    }

    /** Log entries whose local log is gone (deleted or discarded on this device). */
    suspend fun orphanLogEntries(clientId: String): List<SyncEntry> = withContext(io) {
        q.selectOrphanLogEntries(clientId).executeAsList().map { it.toEntry() }
    }

    /** Ids of logs that are not known to be fully on the server. */
    suspend fun unsyncedLogIds(clientId: String): List<String> = withContext(io) {
        q.selectUnsyncedLogIds(clientId).executeAsList()
    }

    suspend fun putEntry(entry: SyncEntry): Unit = withContext(io) {
        q.upsertEntry(entry.entityId, entry.kind.name, entry.clientId, entry.workoutLogId, entry.content, entry.serverRevision.toLong(), entry.state.name)
    }

    suspend fun deleteEntry(entityId: String): Unit = withContext(io) { q.deleteEntry(entityId) }

    suspend fun deleteEntriesOfLog(workoutLogId: String): Unit = withContext(io) { q.deleteEntriesOfLog(workoutLogId) }

    /** Updates only the sync column of a log (the content is untouched). */
    suspend fun setLogSyncState(logId: String, state: WorkoutSyncState): Unit = withContext(io) {
        q.updateWorkoutSyncState(state.name, logId)
    }

    suspend fun syncedMeasurementIds(clientId: String): Set<String> = withContext(io) {
        q.selectMeasurementSyncIds(clientId).executeAsList().toSet()
    }

    suspend fun markMeasurementSynced(clientId: String, id: String): Unit = withContext(io) { q.insertMeasurementSync(id, clientId) }

    /** The server deleted the measurement: remove it here and forget it. */
    suspend fun removeMeasurement(clientId: String, id: String): Unit = withContext(io) {
        db.transaction {
            q.deleteLocalMeasurement(id, clientId)
            q.deleteMeasurementSync(id)
        }
    }

    suspend fun kv(clientId: String, name: String): String? = withContext(io) { q.selectKv(clientId, name).executeAsOneOrNull() }

    suspend fun putKv(clientId: String, name: String, value: String): Unit = withContext(io) { q.upsertKv(clientId, name, value) }

    private fun com.trainingapp.data.db.Workout_sync_entry.toEntry() = SyncEntry(
        entityId = entity_id,
        kind = SyncEntryKind.valueOf(kind),
        clientId = client_id,
        workoutLogId = workout_log_id,
        content = content,
        serverRevision = server_revision.toInt(),
        state = SyncEntryState.valueOf(state),
    )
}
