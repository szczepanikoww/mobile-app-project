package com.trainingapp.server

import com.trainingapp.contract.chat.ConversationDto
import com.trainingapp.contract.chat.MessageDto
import com.trainingapp.contract.chat.MessagesDto
import com.trainingapp.contract.chat.SendMessageRequest
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.contract.media.UploadSessionDto
import com.trainingapp.contract.sync.SetLogDto
import com.trainingapp.contract.sync.SyncOperationDto
import com.trainingapp.contract.sync.SyncOperationType
import com.trainingapp.contract.sync.SyncOutcome
import com.trainingapp.contract.sync.TodayWorkoutDto
import com.trainingapp.contract.sync.WorkoutChangesDto
import com.trainingapp.contract.sync.WorkoutExerciseDto
import com.trainingapp.contract.sync.WorkoutLogDto
import com.trainingapp.contract.sync.WorkoutStateDto
import com.trainingapp.contract.sync.WorkoutSyncRequest
import com.trainingapp.contract.sync.WorkoutSyncResponse
import com.trainingapp.server.common.Ids
import com.trainingapp.server.dev.DemoSeed
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class WorkoutAndChatTest : ApiTest() {
    init {
        runBlocking { DemoSeed(services).seed() }
    }

    private val clientSubject = DemoSeed.CLIENT_SUBJECT
    private val squat = DemoSeed.id("ex-squat").toString()

    private suspend fun HttpClient.sync(vararg ops: SyncOperationDto, batch: Uuid = Ids.v7()) =
        post("/v1/client/workout-sync") { auth(clientSubject); json(); setBody(WorkoutSyncRequest(batch.toString(), ops.toList())) }.body<WorkoutSyncResponse>()

    private fun log(id: Uuid, today: TodayWorkoutDto?, state: WorkoutStateDto = WorkoutStateDto.IN_PROGRESS) = WorkoutLogDto(
        id = id.toString(), assignedPlanId = today?.assignedPlanId, planVersionId = today?.planVersion?.id, planDayId = today?.dayId,
        name = "Dzień A", state = state, startedAt = Clock.System.now() - 30.minutes,
        finishedAt = if (state == WorkoutStateDto.FINISHED) Clock.System.now() else null, timeZone = "Europe/Warsaw",
    )

    @Test
    fun `today's workout comes from the active assignment`() = api { http ->
        val today = http.get("/v1/client/today") { auth(clientSubject) }.body<TodayWorkoutDto>()
        assertEquals("day-a", today.dayId)
        assertEquals(2, today.planVersion.body.days.size)
        assertEquals(3, today.planVersion.body.days.first().exercises.first().sets.size)
        // A trainer-only account is not a client.
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/client/today") { auth("demo-trainer-only") }.status)
    }

    @Test
    fun `acceptance test 3 - retried uploads never duplicate, stale writes are conflicts, finishing moves today on`() = api { http ->
        val today = http.get("/v1/client/today") { auth(clientSubject) }.body<TodayWorkoutDto>()
        val logId = Ids.v7(); val exId = Ids.v7(); val setId = Ids.v7()
        val ex = WorkoutExerciseDto(exId.toString(), logId.toString(), 0, squat, plannedExerciseId = "pe-a1")
        val set = SetLogDto(setId.toString(), exId.toString(), 0, SetType.WORKING, plannedSetId = "ps-a1-1", weightGrams = 80_000, reps = 5, completedAt = Clock.System.now())
        val batch = Ids.v7()
        val ops = arrayOf(
            SyncOperationDto(Ids.v7().toString(), SyncOperationType.UPSERT_LOG, logId.toString(), 0, log = log(logId, today)),
            SyncOperationDto(Ids.v7().toString(), SyncOperationType.UPSERT_EXERCISE, exId.toString(), exercise = ex),
            SyncOperationDto(Ids.v7().toString(), SyncOperationType.UPSERT_SET, setId.toString(), 0, set = set),
        )
        val first = http.sync(*ops, batch = batch)
        assertEquals(listOf(SyncOutcome.APPLIED, SyncOutcome.APPLIED, SyncOutcome.APPLIED), first.results.map { it.outcome })
        // The same batch again (process killed before the response arrived) replays the stored answer.
        assertEquals(first, http.sync(*ops, batch = batch))
        // The same operations in a new batch: duplicates, not new rows.
        assertEquals(listOf(SyncOutcome.DUPLICATE, SyncOutcome.DUPLICATE, SyncOutcome.DUPLICATE), http.sync(*ops).results.map { it.outcome })
        assertEquals(1, count("SELECT count(*) FROM training.set_log"))

        // Edit at revision 1 → revision 2; a second device still at revision 1 gets a conflict with the server row.
        val edited = http.sync(SyncOperationDto(Ids.v7().toString(), SyncOperationType.UPSERT_SET, setId.toString(), 1, set = set.copy(reps = 6)))
        assertEquals(2, edited.results.single().revision)
        val stale = http.sync(SyncOperationDto(Ids.v7().toString(), SyncOperationType.UPSERT_SET, setId.toString(), 1, set = set.copy(reps = 4)))
        assertEquals(SyncOutcome.CONFLICT, stale.results.single().outcome)
        assertEquals(6, stale.results.single().serverSet!!.reps)

        // A set of someone else's / unknown exercise row is rejected without failing the batch.
        val mixed = http.sync(
            SyncOperationDto(Ids.v7().toString(), SyncOperationType.UPSERT_SET, Ids.v7().toString(), 0, set = set.copy(id = Ids.v7().toString(), workoutExerciseId = Ids.v7().toString())),
            SyncOperationDto(Ids.v7().toString(), SyncOperationType.FINISH_LOG, logId.toString(), 1, log = log(logId, today, WorkoutStateDto.FINISHED)),
        )
        assertEquals(listOf(SyncOutcome.REJECTED, SyncOutcome.APPLIED), mixed.results.map { it.outcome })

        val pulled = http.get("/v1/client/workouts") { auth(clientSubject) }.body<WorkoutChangesDto>()
        assertEquals(WorkoutStateDto.FINISHED, pulled.logs.single().state)
        assertEquals(6, pulled.sets.single().reps)
        // Day A is done: today moves to day B.
        assertEquals("day-b", http.get("/v1/client/today") { auth(clientSubject) }.body<TodayWorkoutDto>().dayId)
        // The PR view counts the finished working set.
        assertEquals(1, count("SELECT count(*) FROM training.personal_record"))
    }

    @Test
    fun `chat messages are idempotent by id, ordered by seq and private to the relationship`() = api { http ->
        val conversation = http.get("/v1/client/conversations") { auth(clientSubject) }.body<List<ConversationDto>>().single()
        assertEquals("Michał Kowalczyk", conversation.counterpart.name)
        val rel = conversation.relationshipId
        val id = Ids.v7().toString()
        val sent = http.post("/v1/conversations/$rel/messages") { auth(clientSubject); json(); setBody(SendMessageRequest(id, "Dzień dobry")) }.body<MessageDto>()
        val again = http.post("/v1/conversations/$rel/messages") { auth(clientSubject); json(); setBody(SendMessageRequest(id, "Dzień dobry")) }.body<MessageDto>()
        assertEquals(sent.seq, again.seq)
        val reply = http.post("/v1/conversations/$rel/messages") { auth(DemoSeed.TRAINER_SUBJECT); json(); setBody(SendMessageRequest(Ids.v7().toString(), "Cześć!")) }
            .body<MessageDto>()
        assertEquals(sent.seq + 1, reply.seq)
        assertEquals(1, http.get("/v1/client/conversations") { auth(clientSubject) }.body<List<ConversationDto>>().single().unread)
        val thread = http.get("/v1/conversations/$rel/messages?afterSeq=${sent.seq}") { auth(clientSubject) }.body<MessagesDto>()
        assertEquals(listOf("Cześć!"), thread.messages.map { it.text })

        // Another user is not a party: 404 and an audit record. The outbox carries ids only.
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/conversations/$rel/messages") { auth("tr-anna") }.status)
        assertEquals(1, count("SELECT count(*) FROM platform.audit_record WHERE subject_type = 'conversation'"))
        assertEquals(0, count("SELECT count(*) FROM platform.outbox_event WHERE payload::text LIKE '%Dzień%' OR payload::text LIKE '%Cześć%'"))
    }

    @Test
    fun `technique video - resumable upload, processing, signed playback, linked to the synced set`() = api { http ->
        val rel = http.get("/v1/client/conversations") { auth(clientSubject) }.body<List<ConversationDto>>().single().relationshipId
        // The log and set must be on the server before the message.
        val logId = Ids.v7(); val exId = Ids.v7(); val setId = Ids.v7()
        http.sync(
            SyncOperationDto(Ids.v7().toString(), SyncOperationType.UPSERT_LOG, logId.toString(), log = log(logId, null)),
            SyncOperationDto(Ids.v7().toString(), SyncOperationType.UPSERT_EXERCISE, exId.toString(), exercise = WorkoutExerciseDto(exId.toString(), logId.toString(), 0, squat)),
            SyncOperationDto(Ids.v7().toString(), SyncOperationType.UPSERT_SET, setId.toString(), set = SetLogDto(setId.toString(), exId.toString(), 0, SetType.WORKING, weightGrams = 90_000, reps = 3)),
        )
        val video = tinyMp4(durationSeconds = 12)
        val mediaId = Ids.v7().toString()
        val request = CreateUploadSessionRequest(mediaId, MediaKind.TECHNIQUE_VIDEO, video.size.toLong(), "video/mp4", rel)

        // A message before the upload exists stays queued (409 DEPENDENCY_MISSING).
        val early = http.post("/v1/conversations/$rel/messages") { auth(clientSubject); json(); setBody(SendMessageRequest(Ids.v7().toString(), mediaId = mediaId, workoutLogId = logId.toString(), setLogId = setId.toString())) }
        assertEquals(HttpStatusCode.Conflict, early.status)

        val upload = http.post("/v1/media/upload-sessions") { auth(clientSubject); json(); setBody(request) }.body<UploadSessionDto>()
        assertEquals(0, upload.offset)
        assertEquals(RemoteMediaState.UPLOADING, upload.state)
        val path = "/v1/media/uploads/$mediaId"
        // First half, then the connection "drops"; the retry asks for the offset and sends the rest.
        val half = video.size / 2
        http.patch(path) { auth(clientSubject); header("Upload-Offset", "0"); setBody(video.copyOfRange(0, half)) }
        assertEquals(half.toString(), http.head(path) { auth(clientSubject) }.headers["Upload-Offset"])
        assertEquals(HttpStatusCode.Conflict, http.patch(path) { auth(clientSubject); header("Upload-Offset", "0"); setBody(video) }.status)
        http.patch(path) { auth(clientSubject); header("Upload-Offset", half.toString()); setBody(video.copyOfRange(half, video.size)) }

        // Not playable before the worker has checked it.
        val processing = http.get("/v1/media/$mediaId") { auth(clientSubject) }.body<MediaStatusDto>()
        assertEquals(RemoteMediaState.PROCESSING, processing.state)
        assertNull(processing.playbackUrl)
        runBlocking { services.worker.runDueJobs() }
        val ready = http.get("/v1/media/$mediaId") { auth(DemoSeed.TRAINER_SUBJECT) }.body<MediaStatusDto>()
        assertEquals(RemoteMediaState.READY, ready.state)
        val playback = ready.playbackUrl!!.substringAfter(config.media.publicBaseUrl)
        assertEquals(HttpStatusCode.OK, http.get(playback).status)
        assertEquals(HttpStatusCode.NotFound, http.get(playback.replace("sig=", "sig=0")).status)
        // Another trainer cannot see it (acceptance test 4 for media).
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/media/$mediaId") { auth("tr-anna") }.status)

        val message = http.post("/v1/conversations/$rel/messages") {
            auth(clientSubject); json(); setBody(SendMessageRequest(Ids.v7().toString(), "Technika?", mediaId, logId.toString(), setId.toString()))
        }.body<MessageDto>()
        assertEquals(RemoteMediaState.READY, message.attachments.single().state)
        val set = http.get("/v1/client/workouts") { auth(clientSubject) }.body<WorkoutChangesDto>().sets.single { it.id == setId.toString() }
        assertEquals(mediaId, set.techniqueMediaId)
        assertNotNull(set.revision)
    }

    @Test
    fun `an over-long video fails processing and is never playable`() = api { http ->
        val rel = http.get("/v1/client/conversations") { auth(clientSubject) }.body<List<ConversationDto>>().single().relationshipId
        val video = tinyMp4(durationSeconds = 75)
        val mediaId = Ids.v7().toString()
        http.post("/v1/media/upload-sessions") { auth(clientSubject); json(); setBody(CreateUploadSessionRequest(mediaId, MediaKind.TECHNIQUE_VIDEO, video.size.toLong(), "video/mp4", rel)) }
        http.patch("/v1/media/uploads/$mediaId") { auth(clientSubject); header("Upload-Offset", "0"); setBody(video) }
        runBlocking { services.worker.runDueJobs() }
        val status = http.get("/v1/media/$mediaId") { auth(clientSubject) }.body<MediaStatusDto>()
        assertEquals(RemoteMediaState.FAILED, status.state)
        assertNull(status.playbackUrl)
        assertEquals(1, count("SELECT count(*) FROM media.media_asset WHERE failure_reason = 'TOO_LONG'"))
        // Without a relationship id a technique video is refused before it reaches the DB check.
        val noRel = http.post("/v1/media/upload-sessions") { auth(clientSubject); json(); setBody(CreateUploadSessionRequest(Ids.v7().toString(), MediaKind.TECHNIQUE_VIDEO, 10, "video/mp4")) }
        assertEquals(HttpStatusCode.UnprocessableEntity, noRel.status)
    }

    @Test
    fun `the seeded schedule has a confirmed session and an open request`() = api { http ->
        val schedule = http.get("/v1/client/schedule") { auth(clientSubject) }.body<com.trainingapp.contract.schedule.ClientScheduleDto>()
        assertEquals(setOf("CONFIRMED", "REQUESTED"), schedule.sessions.map { it.status.name }.toSet())
        assertTrue(schedule.sessions.all { it.location == "Studio Centrum" })
    }

    /** A minimal ISO-BMFF file: `ftyp`, then `moov/mvhd` with the given duration, then filler `mdat`. */
    private fun tinyMp4(durationSeconds: Int): ByteArray {
        fun box(type: String, body: ByteArray): ByteArray {
            val size = 8 + body.size
            return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + type.encodeToByteArray() + body
        }
        fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val mvhd = box("mvhd", byteArrayOf(0, 0, 0, 0) + int(0) + int(0) + int(1000) + int(durationSeconds * 1000) + ByteArray(80))
        return box("ftyp", "isom".encodeToByteArray() + int(0)) + box("moov", mvhd) + box("mdat", ByteArray(4096) { (it % 251).toByte() })
    }
}
