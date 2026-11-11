package com.trainingapp.server.training

import com.trainingapp.contract.sync.SetLogDto
import com.trainingapp.contract.sync.SyncOperationDto
import com.trainingapp.contract.sync.SyncOperationType
import com.trainingapp.contract.sync.SyncOutcome
import com.trainingapp.contract.sync.SyncResultDto
import com.trainingapp.contract.sync.WorkoutExerciseDto
import com.trainingapp.contract.sync.WorkoutLogDto
import com.trainingapp.contract.sync.WorkoutStateDto
import com.trainingapp.contract.sync.WorkoutSyncRequest
import com.trainingapp.contract.sync.WorkoutSyncResponse
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.ApiJson
import com.trainingapp.server.common.ErrorCode
import com.trainingapp.server.common.dbNow
import com.trainingapp.server.common.invalid
import com.trainingapp.server.common.toKotlin
import com.trainingapp.server.common.toMicros
import com.trainingapp.server.common.toOffset
import com.trainingapp.server.db.Db
import com.trainingapp.server.db.SqlState
import com.trainingapp.server.db.attempt
import com.trainingapp.server.db.savepoint
import com.trainingapp.server.db.sqlState
import com.trainingapp.server.identity.CurrentUser
import com.trainingapp.server.platform.IdempotencyKeys
import com.trainingapp.server.scheduling.Sessions
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.ZoneId
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `POST /v1/client/workout-sync` (plan §8.4, architecture §9.2). Every operation runs in its own savepoint, so one
 * conflict does not roll back the rest. Rows are keyed by the phone's UUIDs, so a retried upsert hits the same row:
 * no change → DUPLICATE; the server moved on since `baseRevision` → CONFLICT with the server row. A whole retried batch
 * is replayed from `platform.idempotency_key`. Acceptance test 3.
 */
class WorkoutSyncService(private val db: Db, private val training: TrainingService) {
    companion object {
        const val ENDPOINT = "workout-sync"
        const val MAX_OPERATIONS = 500
    }

    private class Rejected(val reason: String) : RuntimeException(reason)

    suspend fun sync(client: CurrentUser, request: WorkoutSyncRequest): WorkoutSyncResponse {
        val batchId = runCatching { Uuid.parse(request.operationId) }.getOrNull() ?: throw invalid("operationId")
        if (request.operations.size > MAX_OPERATIONS) throw invalid("operations")
        val hash = sha256(ApiJson.encodeToString(ListSerializer(SyncOperationDto.serializer()), request.operations))
        return db.tx {
            replay(client.id, batchId, hash)?.let { return@tx it }
            val results = request.operations.map { op -> applyOne(client, op) }
            val response = WorkoutSyncResponse(results)
            val stored = attempt(SqlState.UNIQUE_VIOLATION) {
                IdempotencyKeys.insert {
                    it[userId] = client.id
                    it[operationId] = batchId
                    it[endpoint] = ENDPOINT
                    it[requestHash] = hash
                    it[responseCode] = 200
                    it[responseBody] = ApiJson.encodeToJsonElement(WorkoutSyncResponse.serializer(), response)
                }
            }
            // A concurrent replay of the same batch committed first: its stored answer wins, this one rolls back.
            if (stored == null) throw ApiException(409, ErrorCode.CONFLICT, "batch is being processed")
            response
        }
    }

    private fun JdbcTransaction.replay(userId: Uuid, batchId: Uuid, hash: String): WorkoutSyncResponse? {
        val row = IdempotencyKeys.selectAll().where { (IdempotencyKeys.userId eq userId) and (IdempotencyKeys.operationId eq batchId) }
            .singleOrNull() ?: return null
        if (row[IdempotencyKeys.endpoint] != ENDPOINT || row[IdempotencyKeys.requestHash] != hash) {
            throw ApiException(422, ErrorCode.IDEMPOTENCY_MISMATCH, "operation id reused with another body")
        }
        return ApiJson.decodeFromJsonElement(WorkoutSyncResponse.serializer(), row[IdempotencyKeys.responseBody]!!)
    }

    private fun JdbcTransaction.applyOne(client: CurrentUser, op: SyncOperationDto): SyncResultDto = try {
        savepoint("op") {
            val entityId = runCatching { Uuid.parse(op.entityId) }.getOrNull() ?: throw Rejected("entityId")
            when (op.type) {
                SyncOperationType.UPSERT_LOG -> upsertLog(client, entityId, op, op.log ?: throw Rejected("log"))
                SyncOperationType.FINISH_LOG -> upsertLog(client, entityId, op, finished(op.log ?: throw Rejected("log")))
                SyncOperationType.DISCARD_LOG -> discardLog(client, entityId, op)
                SyncOperationType.UPSERT_EXERCISE -> upsertExercise(client, entityId, op, op.exercise ?: throw Rejected("exercise"))
                SyncOperationType.DELETE_EXERCISE -> deleteExercise(client, entityId, op)
                SyncOperationType.UPSERT_SET -> upsertSet(client, entityId, op, op.set ?: throw Rejected("set"))
                SyncOperationType.DELETE_SET -> deleteSet(client, entityId, op)
            }
        }
    } catch (e: Rejected) {
        SyncResultDto(op.operationId, SyncOutcome.REJECTED, reason = e.reason)
    } catch (e: Exception) {
        val state = e.sqlState() ?: throw e
        SyncResultDto(op.operationId, SyncOutcome.REJECTED, reason = "sql:$state")
    }

    private fun finished(log: WorkoutLogDto): WorkoutLogDto {
        if (log.finishedAt == null) throw Rejected("finishedAt")
        return log.copy(state = WorkoutStateDto.FINISHED)
    }

    // ----- Logs -----

    private fun JdbcTransaction.upsertLog(client: CurrentUser, id: Uuid, op: SyncOperationDto, log: WorkoutLogDto): SyncResultDto {
        if (log.id != op.entityId) throw Rejected("entityId")
        val fields = validateLog(client, log)
        val existing = WorkoutLogs.selectAll().where { WorkoutLogs.id eq id }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
        val now = dbNow()
        if (existing == null) {
            WorkoutLogs.insert {
                it[WorkoutLogs.id] = id
                it[clientId] = client.id
                fields.writeTo(it)
                it[revision] = 1
                it[updatedAt] = now.toOffset()
            }
            return SyncResultDto(op.operationId, SyncOutcome.APPLIED, revision = 1)
        }
        if (existing[WorkoutLogs.clientId] != client.id) throw Rejected("not found")
        val current = existing.toLogDto()
        if (fields.sameAs(existing)) return SyncResultDto(op.operationId, SyncOutcome.DUPLICATE, revision = current.revision)
        if (current.revision > op.baseRevision) return SyncResultDto(op.operationId, SyncOutcome.CONFLICT, revision = current.revision, serverLog = current)
        WorkoutLogs.update({ (WorkoutLogs.id eq id) and (WorkoutLogs.revision eq current.revision) }) {
            fields.writeTo(it)
            it[revision] = current.revision + 1
            it[updatedAt] = now.toOffset()
        }
        return SyncResultDto(op.operationId, SyncOutcome.APPLIED, revision = current.revision + 1)
    }

    private fun JdbcTransaction.discardLog(client: CurrentUser, id: Uuid, op: SyncOperationDto): SyncResultDto {
        val existing = WorkoutLogs.selectAll().where { WorkoutLogs.id eq id }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
            ?: return SyncResultDto(op.operationId, SyncOutcome.DUPLICATE) // never reached the server: nothing to discard
        if (existing[WorkoutLogs.clientId] != client.id) throw Rejected("not found")
        val revision = existing[WorkoutLogs.revision]
        if (existing[WorkoutLogs.state] == "DISCARDED") return SyncResultDto(op.operationId, SyncOutcome.DUPLICATE, revision = revision)
        if (revision > op.baseRevision) return SyncResultDto(op.operationId, SyncOutcome.CONFLICT, revision = revision, serverLog = existing.toLogDto())
        val now = dbNow().toOffset()
        WorkoutLogs.update({ WorkoutLogs.id eq id }) {
            it[state] = "DISCARDED"
            it[finishedAt] = null
            it[deletedAt] = now
            it[WorkoutLogs.revision] = revision + 1
            it[updatedAt] = now
        }
        return SyncResultDto(op.operationId, SyncOutcome.APPLIED, revision = revision + 1)
    }

    private class LogFields(
        val sessionId: Uuid?, val assignedPlanId: Uuid?, val planVersionId: Uuid?, val planDayId: String?, val name: String,
        val state: String, val startedAt: Instant, val finishedAt: Instant?, val timeZone: String, val place: String?, val note: String?,
    ) {
        fun writeTo(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>) {
            it[WorkoutLogs.sessionId] = sessionId
            it[WorkoutLogs.assignedPlanId] = assignedPlanId
            it[WorkoutLogs.planVersionId] = planVersionId
            it[WorkoutLogs.planDayId] = planDayId
            it[WorkoutLogs.name] = name
            it[WorkoutLogs.state] = state
            it[WorkoutLogs.startedAt] = startedAt.toOffset()
            it[WorkoutLogs.finishedAt] = finishedAt?.toOffset()
            it[WorkoutLogs.timeZone] = timeZone
            it[WorkoutLogs.place] = place
            it[WorkoutLogs.note] = note
        }

        fun sameAs(row: ResultRow) = row[WorkoutLogs.sessionId] == sessionId && row[WorkoutLogs.assignedPlanId] == assignedPlanId &&
            row[WorkoutLogs.planVersionId] == planVersionId && row[WorkoutLogs.planDayId] == planDayId && row[WorkoutLogs.name] == name &&
            row[WorkoutLogs.state] == state && row[WorkoutLogs.startedAt].toKotlin() == startedAt &&
            row[WorkoutLogs.finishedAt]?.toKotlin() == finishedAt && row[WorkoutLogs.timeZone] == timeZone &&
            row[WorkoutLogs.place] == place && row[WorkoutLogs.note] == note
    }

    /** Ownership checks before writing (plan §8.4): own session, own assignment, an existing plan day. */
    private fun JdbcTransaction.validateLog(client: CurrentUser, log: WorkoutLogDto): LogFields {
        val name = log.name.trim()
        if (name.length !in 1..80) throw Rejected("name")
        if (runCatching { ZoneId.of(log.timeZone) }.isFailure) throw Rejected("timeZone")
        if ((log.place?.length ?: 0) > 120 || (log.note?.length ?: 0) > 2000) throw Rejected("text")
        val state = when (log.state) {
            WorkoutStateDto.IN_PROGRESS -> "IN_PROGRESS"
            WorkoutStateDto.FINISHED -> "FINISHED"
            WorkoutStateDto.DISCARDED -> throw Rejected("use DISCARD_LOG")
        }
        if ((state == "FINISHED") != (log.finishedAt != null)) throw Rejected("finishedAt")
        val sessionId = log.sessionId?.let { parse(it, "sessionId") }
        if (sessionId != null && Sessions.selectAll().where { (Sessions.id eq sessionId) and (Sessions.clientId eq client.id) }.none()) throw Rejected("sessionId")
        val assignedPlanId = log.assignedPlanId?.let { parse(it, "assignedPlanId") }
        if (assignedPlanId != null && AssignedPlans.selectAll().where { (AssignedPlans.id eq assignedPlanId) and (AssignedPlans.clientId eq client.id) }.none()) {
            throw Rejected("assignedPlanId")
        }
        val planVersionId = log.planVersionId?.let { parse(it, "planVersionId") }
        if (log.planDayId != null) {
            if (planVersionId == null) throw Rejected("planVersionId")
            if (PlanDays.selectAll().where { (PlanDays.planVersionId eq planVersionId) and (PlanDays.id eq log.planDayId!!) }.none()) throw Rejected("planDayId")
        }
        return LogFields(sessionId, assignedPlanId, planVersionId, log.planDayId, name, state, log.startedAt.toMicros(), log.finishedAt?.toMicros(), log.timeZone, log.place, log.note)
    }

    // ----- Exercises (no revision column yet: last write wins, gap G-10) -----

    private fun JdbcTransaction.upsertExercise(client: CurrentUser, id: Uuid, op: SyncOperationDto, exercise: WorkoutExerciseDto): SyncResultDto {
        if (exercise.id != op.entityId) throw Rejected("entityId")
        val logId = parse(exercise.workoutLogId, "workoutLogId")
        ownLog(client, logId)
        val exerciseId = parse(exercise.exerciseId, "exerciseId")
        if (!with(training) { exerciseVisibleTo(exerciseId, client.id) }) throw Rejected("exerciseId")
        if (exercise.position !in 0..Short.MAX_VALUE || (exercise.note?.length ?: 0) > 500) throw Rejected("exercise")
        val existing = WorkoutExercises.selectAll().where { WorkoutExercises.id eq id }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
        val now = dbNow().toOffset()
        if (existing != null) {
            if (existing[WorkoutExercises.workoutLogId] != logId) throw Rejected("workoutLogId")
            val same = existing[WorkoutExercises.position].toInt() == exercise.position && existing[WorkoutExercises.exerciseId] == exerciseId &&
                existing[WorkoutExercises.plannedExerciseId] == exercise.plannedExerciseId && existing[WorkoutExercises.supersetGroup] == exercise.supersetGroup &&
                existing[WorkoutExercises.note] == exercise.note && existing[WorkoutExercises.deletedAt] == null
            if (same) return SyncResultDto(op.operationId, SyncOutcome.DUPLICATE)
            WorkoutExercises.update({ WorkoutExercises.id eq id }) {
                it[position] = exercise.position.toShort()
                it[WorkoutExercises.exerciseId] = exerciseId
                it[plannedExerciseId] = exercise.plannedExerciseId
                it[supersetGroup] = exercise.supersetGroup
                it[note] = exercise.note
                it[deletedAt] = null
                it[updatedAt] = now
            }
        } else {
            WorkoutExercises.insert {
                it[WorkoutExercises.id] = id
                it[workoutLogId] = logId
                it[position] = exercise.position.toShort()
                it[WorkoutExercises.exerciseId] = exerciseId
                it[plannedExerciseId] = exercise.plannedExerciseId
                it[supersetGroup] = exercise.supersetGroup
                it[note] = exercise.note
                it[updatedAt] = now
            }
        }
        return SyncResultDto(op.operationId, SyncOutcome.APPLIED)
    }

    private fun JdbcTransaction.deleteExercise(client: CurrentUser, id: Uuid, op: SyncOperationDto): SyncResultDto {
        val existing = WorkoutExercises.selectAll().where { WorkoutExercises.id eq id }.singleOrNull()
            ?: return SyncResultDto(op.operationId, SyncOutcome.DUPLICATE)
        ownLog(client, existing[WorkoutExercises.workoutLogId])
        if (existing[WorkoutExercises.deletedAt] != null) return SyncResultDto(op.operationId, SyncOutcome.DUPLICATE)
        val now = dbNow().toOffset()
        WorkoutExercises.update({ WorkoutExercises.id eq id }) { it[deletedAt] = now; it[updatedAt] = now }
        return SyncResultDto(op.operationId, SyncOutcome.APPLIED)
    }

    // ----- Sets -----

    private fun JdbcTransaction.upsertSet(client: CurrentUser, id: Uuid, op: SyncOperationDto, set: SetLogDto): SyncResultDto {
        if (set.id != op.entityId) throw Rejected("entityId")
        val parentId = parse(set.workoutExerciseId, "workoutExerciseId")
        // workout_log_id and exercise_id are repeated from the parent (denormalised in the DDL).
        val parent = WorkoutExercises.selectAll().where { WorkoutExercises.id eq parentId }.singleOrNull() ?: throw Rejected("workoutExerciseId")
        ownLog(client, parent[WorkoutExercises.workoutLogId])
        validateSet(set)
        val existing = SetLogs.selectAll().where { SetLogs.id eq id }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
        val now = dbNow().toOffset()
        val rpe = set.rpe?.let { BigDecimal.valueOf(it).setScale(1, java.math.RoundingMode.HALF_UP) }
        val completedAt = set.completedAt?.toMicros()
        fun write(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>) {
            it[SetLogs.workoutExerciseId] = parentId
            it[SetLogs.workoutLogId] = parent[WorkoutExercises.workoutLogId]
            it[SetLogs.exerciseId] = parent[WorkoutExercises.exerciseId]
            it[SetLogs.position] = set.position.toShort()
            it[SetLogs.type] = set.type.name
            it[SetLogs.plannedSetId] = set.plannedSetId
            it[SetLogs.weightGrams] = set.weightGrams
            it[SetLogs.reps] = set.reps
            it[SetLogs.rpe] = rpe
            it[SetLogs.durationSeconds] = set.durationSeconds
            it[SetLogs.distanceMeters] = set.distanceMeters
            it[SetLogs.completedAt] = completedAt?.toOffset()
            it[SetLogs.deletedAt] = null
            it[SetLogs.updatedAt] = now
        }
        if (existing == null) {
            SetLogs.insert {
                it[SetLogs.id] = id
                write(it)
                it[revision] = 1
            }
            return SyncResultDto(op.operationId, SyncOutcome.APPLIED, revision = 1)
        }
        if (existing[SetLogs.workoutLogId] != parent[WorkoutExercises.workoutLogId]) throw Rejected("workoutExerciseId")
        val current = existing.toSetDto()
        val same = existing[SetLogs.workoutExerciseId] == parentId && current.position == set.position && current.type == set.type &&
            current.plannedSetId == set.plannedSetId && current.weightGrams == set.weightGrams && current.reps == set.reps &&
            sameDecimal(existing[SetLogs.rpe], rpe) &&
            current.durationSeconds == set.durationSeconds && current.distanceMeters == set.distanceMeters &&
            current.completedAt == completedAt && current.deletedAt == null
        if (same) return SyncResultDto(op.operationId, SyncOutcome.DUPLICATE, revision = current.revision)
        if (current.revision > op.baseRevision) return SyncResultDto(op.operationId, SyncOutcome.CONFLICT, revision = current.revision, serverSet = current)
        SetLogs.update({ (SetLogs.id eq id) and (SetLogs.revision eq current.revision) }) {
            write(it)
            it[revision] = current.revision + 1
        }
        return SyncResultDto(op.operationId, SyncOutcome.APPLIED, revision = current.revision + 1)
    }

    private fun JdbcTransaction.deleteSet(client: CurrentUser, id: Uuid, op: SyncOperationDto): SyncResultDto {
        val existing = SetLogs.selectAll().where { SetLogs.id eq id }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
            ?: return SyncResultDto(op.operationId, SyncOutcome.DUPLICATE)
        ownLog(client, existing[SetLogs.workoutLogId])
        val revision = existing[SetLogs.revision]
        if (existing[SetLogs.deletedAt] != null) return SyncResultDto(op.operationId, SyncOutcome.DUPLICATE, revision = revision)
        if (revision > op.baseRevision) return SyncResultDto(op.operationId, SyncOutcome.CONFLICT, revision = revision, serverSet = existing.toSetDto())
        val now = dbNow().toOffset()
        SetLogs.update({ (SetLogs.id eq id) and (SetLogs.revision eq revision) }) {
            it[deletedAt] = now
            it[updatedAt] = now
            it[SetLogs.revision] = revision + 1
        }
        return SyncResultDto(op.operationId, SyncOutcome.APPLIED, revision = revision + 1)
    }

    private fun validateSet(set: SetLogDto) {
        val ok = set.position in 0..Short.MAX_VALUE &&
            (set.weightGrams?.let { it in 0..1_000_000 } ?: true) &&
            (set.reps?.let { it in 0..1000 } ?: true) &&
            (set.rpe?.let { it in 1.0..10.0 } ?: true) &&
            (set.durationSeconds?.let { it >= 0 } ?: true) &&
            (set.distanceMeters?.let { it >= 0 } ?: true)
        if (!ok) throw Rejected("set values")
    }

    private fun JdbcTransaction.ownLog(client: CurrentUser, logId: Uuid) {
        if (WorkoutLogs.selectAll().where { (WorkoutLogs.id eq logId) and (WorkoutLogs.clientId eq client.id) }.none()) {
            throw Rejected("workout log not synced")
        }
    }

    private fun sameDecimal(a: BigDecimal?, b: BigDecimal?) = if (a == null || b == null) a == b else a.compareTo(b) == 0

    private fun parse(value: String, field: String): Uuid = runCatching { Uuid.parse(value) }.getOrNull() ?: throw Rejected(field)

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
