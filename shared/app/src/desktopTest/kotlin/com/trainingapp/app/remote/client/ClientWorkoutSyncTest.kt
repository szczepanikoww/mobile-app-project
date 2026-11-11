package com.trainingapp.app.remote.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.sync.MeasurementChangesDto
import com.trainingapp.contract.sync.SetLogDto
import com.trainingapp.contract.sync.SyncOperationDto
import com.trainingapp.contract.sync.SyncOperationType
import com.trainingapp.contract.sync.SyncOutcome
import com.trainingapp.contract.sync.SyncResultDto
import com.trainingapp.contract.sync.TodayWorkoutDto
import com.trainingapp.contract.sync.WorkoutChangesDto
import com.trainingapp.contract.sync.WorkoutSyncRequest
import com.trainingapp.contract.sync.WorkoutSyncResponse
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.data.workout.LocalMeasurementRepository
import com.trainingapp.data.workout.LocalWorkoutRepository
import com.trainingapp.data.workout.LocalWorkoutSyncStore
import com.trainingapp.domain.workout.BodyMeasurement
import com.trainingapp.domain.workout.CurrentClient
import com.trainingapp.domain.workout.LoggedExercise
import com.trainingapp.domain.workout.MeasurementKind
import com.trainingapp.domain.workout.SetLog
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutState
import com.trainingapp.domain.workout.WorkoutSyncState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Today mapping and workout sync against a scripted server. Synthetic data only. */
class ClientWorkoutSyncTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val cursor = Instant.fromEpochMilliseconds(1_000)

    private val client = object : CurrentClient {
        override val clientId = "client-1"
        override val firstName = "Test"
        override val trainerRelationshipId = "rel-1"
    }

    /** A tiny in-memory workout server: same id and payload = DUPLICATE, new = APPLIED, stale base = CONFLICT. */
    private class FakeServer(val json: Json) {
        val rows = mutableMapOf<String, Pair<String, Int>>()
        val syncRequests = mutableListOf<WorkoutSyncRequest>()
        val measurementPuts = mutableListOf<String>()
        var offline = false

        /** The server applies the batch but the answer never arrives. */
        var loseResponse = false
        var forceConflict: ((SyncOperationDto) -> SyncResultDto?)? = null
        var today: String? = null

        fun apply(op: SyncOperationDto): SyncResultDto {
            forceConflict?.invoke(op)?.let { return it }
            val payload = when (op.type) {
                SyncOperationType.UPSERT_SET -> json.encodeToString(SetLogDto.serializer(), op.set!!.copy(revision = 0))
                else -> json.encodeToString(SyncOperationDto.serializer(), op.copy(operationId = "", baseRevision = 0))
            }
            val existing = rows[op.entityId]
            return when {
                existing == null -> { rows[op.entityId] = payload to 1; SyncResultDto(op.operationId, SyncOutcome.APPLIED, revision = 1) }
                existing.first == payload -> SyncResultDto(op.operationId, SyncOutcome.DUPLICATE, revision = existing.second)
                op.baseRevision < existing.second -> SyncResultDto(op.operationId, SyncOutcome.CONFLICT, revision = existing.second)
                else -> { rows[op.entityId] = payload to existing.second + 1; SyncResultDto(op.operationId, SyncOutcome.APPLIED, revision = existing.second + 1) }
            }
        }
    }

    private class Env(val server: FakeServer, val syncer: WorkoutSyncer, val workouts: LocalWorkoutRepository, val measurements: LocalMeasurementRepository, val store: LocalWorkoutSyncStore, val source: RemoteTodayWorkoutSource)

    private fun env(server: FakeServer = FakeServer(json)): Env {
        val engine = MockEngine { request -> handle(server, request) }
        val api = ApiClient("http://test", engine = engine, token = { "dev.demo-client" })
        val db = TrainingDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrainingDatabase.Schema))
        val io = Dispatchers.Unconfined
        val workouts = LocalWorkoutRepository(db, io)
        val measurements = LocalMeasurementRepository(db, io)
        val store = LocalWorkoutSyncStore(db, io)
        return Env(server, WorkoutSyncer(api, workouts, measurements, store, client), workouts, measurements, store, RemoteTodayWorkoutSource(api, store))
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.handle(server: FakeServer, request: HttpRequestData): io.ktor.client.request.HttpResponseData {
        if (server.offline) throw IOException("no network")
        val path = request.url.encodedPath
        return when {
            path.endsWith("/v1/client/today") ->
                server.today?.let { respond(it, HttpStatusCode.OK, jsonHeaders) } ?: respond("", HttpStatusCode.NoContent)
            path.endsWith("/v1/client/workout-sync") -> {
                val body = json.decodeFromString(WorkoutSyncRequest.serializer(), (request.body as TextContent).text)
                server.syncRequests += body
                val response = WorkoutSyncResponse(body.operations.map { server.apply(it) })
                if (server.loseResponse) {
                    respond("""{"code":"INTERNAL","message":"x"}""", HttpStatusCode.InternalServerError, jsonHeaders)
                } else {
                    respond(json.encodeToString(WorkoutSyncResponse.serializer(), response), HttpStatusCode.OK, jsonHeaders)
                }
            }
            path.endsWith("/v1/client/workouts") ->
                respond(json.encodeToString(WorkoutChangesDto.serializer(), WorkoutChangesDto(emptyList(), emptyList(), emptyList(), cursor)), HttpStatusCode.OK, jsonHeaders)
            path.endsWith("/v1/client/measurements") ->
                respond(json.encodeToString(MeasurementChangesDto.serializer(), MeasurementChangesDto(emptyList(), cursor)), HttpStatusCode.OK, jsonHeaders)
            request.method == HttpMethod.Put && path.contains("/v1/client/measurements/") -> {
                server.measurementPuts += path.substringAfterLast('/')
                respond("""{"id":"${path.substringAfterLast('/')}","kind":"BODY_WEIGHT","valueThousandths":72500,"measuredAt":"1970-01-01T00:00:01Z"}""", HttpStatusCode.OK, jsonHeaders)
            }
            else -> respond("""{"code":"NOT_FOUND","message":"x"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    private fun log(id: String = "log-1", reps: Int = 8) = WorkoutLog(
        id = id,
        clientId = client.clientId,
        name = "FBW A",
        startedAtEpochMs = 1_000,
        finishedAtEpochMs = 2_000,
        state = WorkoutState.FINISHED,
        exercises = listOf(
            LoggedExercise(
                id = "entry-1", exerciseId = "ex-1", trackingType = TrackingType.WEIGHT_REPS,
                sets = listOf(
                    SetLog(id = "set-1", type = SetType.WORKING, weightGrams = 60_000, reps = reps, completedAtEpochMs = 1_500),
                    SetLog(id = "set-2", type = SetType.WORKING, weightGrams = 60_000, reps = 8, completedAtEpochMs = 1_600),
                ),
            ),
        ),
    )

    private val todayJson = json.encodeToString(
        TodayWorkoutDto.serializer(),
        TodayWorkoutDto(
            planVersion = PlanVersionDto("pv-1", "tpl-1", 1, 0, null, PlanBodyDto("FBW", listOf(PlanDayDto("day-1", "Dzień 1", emptyList())))),
            dayId = "day-1", assignedPlanId = "ap-1", sessionId = "s-1", place = "Siłownia",
        ),
    )

    @Test
    fun todayIsMappedCachedAndUsedOffline() = runTest {
        val e = env()
        e.server.today = todayJson
        val today = assertNotNull(e.source.today(client.clientId))
        assertEquals("day-1", today.day.id)
        assertEquals("s-1", today.sessionId)
        assertEquals("Siłownia", today.place)

        e.server.offline = true
        assertEquals("day-1", e.source.today(client.clientId)?.dayId, "cached plan is shown offline")

        e.server.offline = false
        e.server.today = null // 204: nothing today, and that is cached too
        assertNull(e.source.today(client.clientId))
        e.server.offline = true
        assertNull(e.source.today(client.clientId))
    }

    @Test
    fun unknownDayIsNothingToDo() = runTest {
        val e = env()
        e.server.today = todayJson.replace("\"dayId\":\"day-1\"", "\"dayId\":\"missing\"")
        assertNull(e.source.today(client.clientId))
    }

    @Test
    fun pendingOperationsAreSentOnceAndMarkedSynced() = runTest {
        val e = env()
        e.workouts.save(log())
        val first = e.syncer.syncOnce()
        assertEquals(SyncStatus.OK, first.status)
        assertEquals(4, first.applied) // log + exercise + 2 sets
        assertEquals(4, e.server.rows.size)
        assertEquals(WorkoutSyncState.SYNCED, e.workouts.get("log-1")?.syncState)
        assertEquals(SyncOperationType.FINISH_LOG, e.server.syncRequests.single().operations.first().type)

        val again = e.syncer.syncOnce()
        assertEquals(SyncStatus.OK, again.status)
        assertEquals(1, e.server.syncRequests.size, "nothing left to send")
    }

    @Test
    fun failureKeepsEverythingAndRetryDoesNotDuplicate() = runTest {
        val e = env()
        e.workouts.save(log())
        e.server.offline = true
        assertEquals(SyncStatus.OFFLINE, e.syncer.syncOnce().status)
        assertTrue(e.server.rows.isEmpty())
        assertTrue(e.workouts.get("log-1")?.syncState != WorkoutSyncState.SYNCED)

        e.server.offline = false
        e.server.loseResponse = true // applied on the server, answer lost
        assertEquals(SyncStatus.FAILED, e.syncer.syncOnce().status)
        assertEquals(4, e.server.rows.size)
        assertEquals(WorkoutSyncState.FAILED, e.workouts.get("log-1")?.syncState)

        e.server.loseResponse = false
        val retry = e.syncer.syncOnce()
        assertEquals(4, retry.duplicates)
        assertEquals(0, retry.applied)
        assertEquals(4, e.server.rows.size, "no set was duplicated")
        assertEquals(WorkoutSyncState.SYNCED, e.workouts.get("log-1")?.syncState)
    }

    @Test
    fun editedSetIsResentAlone() = runTest {
        val e = env()
        e.workouts.save(log())
        e.syncer.syncOnce()
        e.workouts.save(log(reps = 10).copy(syncState = WorkoutSyncState.SYNCED)) // stale in-memory state is downgraded
        assertEquals(WorkoutSyncState.PENDING, e.workouts.get("log-1")?.syncState)
        assertTrue(e.workouts.get("log-1")?.syncState != WorkoutSyncState.SYNCED, "an edit is never shown as synced")

        val result = e.syncer.syncOnce()
        assertEquals(1, result.applied)
        val sent = e.server.syncRequests.last().operations.single()
        assertEquals(SyncOperationType.UPSERT_SET, sent.type)
        assertEquals(1, sent.baseRevision)
        assertEquals(10, sent.set?.reps)
    }

    @Test
    fun removedSetIsDeletedOnTheServer() = runTest {
        val e = env()
        e.workouts.save(log())
        e.syncer.syncOnce()
        val trimmed = log().let { l -> l.copy(exercises = l.exercises.map { it.copy(sets = it.sets.take(1)) }) }
        e.workouts.save(trimmed)
        e.syncer.syncOnce()
        assertEquals(listOf(SyncOperationType.DELETE_SET), e.server.syncRequests.last().operations.map { it.type })
    }

    @Test
    fun conflictIsReportedAndNotRetriedUntilTheSetChanges() = runTest {
        val e = env()
        e.workouts.save(log())
        e.syncer.syncOnce()
        e.server.forceConflict = { op ->
            if (op.entityId == "set-1") SyncResultDto(op.operationId, SyncOutcome.CONFLICT, revision = 5, serverSet = op.set!!.copy(reps = 99)) else null
        }
        e.workouts.save(log(reps = 12))
        val result = e.syncer.syncOnce()
        assertEquals(listOf("set-1"), result.conflicts.map { it.entityId })
        assertEquals(WorkoutSyncState.CONFLICT, e.workouts.get("log-1")?.syncState)

        val requests = e.server.syncRequests.size
        e.syncer.syncOnce()
        assertEquals(requests, e.server.syncRequests.size, "unchanged conflicting set is not re-sent")

        // The user keeps their value: re-armed with the server's revision, then accepted.
        e.server.forceConflict = null
        e.syncer.keepLocalAfterConflict("log-1")
        val resolved = e.syncer.syncOnce()
        assertTrue(resolved.conflicts.isEmpty())
        assertEquals(5, e.server.syncRequests.last().operations.single().baseRevision)
    }

    @Test
    fun serverOnlyFieldChangeIsNotAConflict() = runTest {
        val e = env()
        e.workouts.save(log())
        e.syncer.syncOnce()
        e.server.forceConflict = { op ->
            if (op.entityId == "set-1") SyncResultDto(op.operationId, SyncOutcome.CONFLICT, revision = 2, serverSet = op.set!!.copy(techniqueMediaId = "media-9", revision = 2)) else null
        }
        e.workouts.save(log().let { l -> l.copy(exercises = l.exercises.map { it.copy(sets = it.sets.map { s -> if (s.id == "set-1") s.copy(weightGrams = 61_000) else s }) }) })
        // The server's set differs only in the technique video, so its revision is adopted: no conflict.
        val result = e.syncer.syncOnce()
        assertTrue(result.conflicts.isEmpty())
        assertEquals(WorkoutSyncState.SYNCED, e.workouts.get("log-1")?.syncState)
    }

    @Test
    fun measurementsAreUploadedOnce() = runTest {
        val e = env()
        e.measurements.add(BodyMeasurement("m-1", client.clientId, MeasurementKind.BODY_WEIGHT, 72_500, 1_000))
        assertEquals(1, e.syncer.syncOnce().pushedMeasurements)
        assertEquals(0, e.syncer.syncOnce().pushedMeasurements)
        assertEquals(listOf("m-1"), e.server.measurementPuts)
    }
}
