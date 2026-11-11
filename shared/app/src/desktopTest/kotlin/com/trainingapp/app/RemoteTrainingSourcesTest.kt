package com.trainingapp.app

import com.trainingapp.app.remote.RemoteExerciseRemoteDataSource
import com.trainingapp.app.remote.RemoteMediaRemoteDataSource
import com.trainingapp.app.remote.RemotePlanRemoteDataSource
import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.exercise.UpsertExerciseRequest
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.data.media.UploadResult
import com.trainingapp.domain.plan.PublishResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import pl.trainer.data.remote.TrainerApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server-backed training sources against a scripted server. Synthetic data only. */
class RemoteTrainingSourcesTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Server(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request -> requests += request; handler(request) }
        fun paths() = requests.filterNot { it.url.encodedPath.endsWith("dev/token") }.map { "${it.method.value} ${it.url.encodedPath}" }
    }

    private fun Server.api() = TrainerApiClient("http://test", subject = { "demo-trainer" }, engine = HttpClient(engine))

    private fun MockRequestHandleScope.token() = respond("""{"token":"tok","userId":"t-1"}""", HttpStatusCode.OK, json)

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String) =
        respond("""{"code":"$code","message":"x"}""", status, json)

    private val request = PublishPlanRequest(operationId = "op-1", templateId = "tpl-1", baseVersion = 1, body = PlanBodyDto("Plan", emptyList()))

    private val published = """
        {"template":{"id":"tpl-1","ownerTrainerId":"t-1","name":"Plan","latestVersion":2,"updatedAtEpochMs":5},
         "version":{"id":"v-2","templateId":"tpl-1","number":2,"createdAtEpochMs":5,"basedOnVersion":1,"body":{"name":"Plan","days":[]}}}
    """.trimIndent()

    @Test
    fun publishSuccessUsesVersionsPathForExistingTemplate() = runTest {
        val server = Server { r -> if (r.url.encodedPath.endsWith("dev/token")) token() else respond(published, HttpStatusCode.OK, json) }
        val result = RemotePlanRemoteDataSource(server.api()).publish(request)
        assertEquals(2, (result as PublishResult.Published).response.version.number)
        assertEquals(listOf("POST /api/v1/trainer/plans/tpl-1/versions"), server.paths())
    }

    @Test
    fun newTemplatePostsToPlans() = runTest {
        val server = Server { r -> if (r.url.encodedPath.endsWith("dev/token")) token() else respond(published, HttpStatusCode.Created, json) }
        RemotePlanRemoteDataSource(server.api()).publish(request.copy(templateId = null, baseVersion = null))
        assertEquals(listOf("POST /api/v1/trainer/plans"), server.paths())
    }

    @Test
    fun publishConflictFetchesLatestVersion() = runTest {
        val server = Server { r ->
            when {
                r.url.encodedPath.endsWith("dev/token") -> token()
                r.method == HttpMethod.Post -> error(HttpStatusCode.Conflict, "CONFLICT")
                else -> respond("""{"id":"v-4","templateId":"tpl-1","number":4,"createdAtEpochMs":5,"body":{"name":"Plan","days":[]}}""", HttpStatusCode.OK, json)
            }
        }
        assertEquals(PublishResult.Conflict(4), RemotePlanRemoteDataSource(server.api()).publish(request))
        assertEquals("GET /api/v1/trainer/plans/tpl-1/versions/latest", server.paths().last())
    }

    @Test
    fun publishOfflineAndServerErrors() = runTest {
        val offline = Server { throw java.io.IOException("down") }
        assertEquals(PublishResult.NetworkUnavailable, RemotePlanRemoteDataSource(offline.api()).publish(request))
        val broken = Server { r -> if (r.url.encodedPath.endsWith("dev/token")) token() else error(HttpStatusCode.InternalServerError, "INTERNAL") }
        assertEquals(PublishResult.Failed, RemotePlanRemoteDataSource(broken.api()).publish(request))
    }

    private val exercise = UpsertExerciseRequest(
        id = "ex-1", names = mapOf("pl" to "Test"), primaryMuscle = MuscleGroup.entries.first(),
        equipment = Equipment.entries.first(), trackingType = TrackingType.REPS,
    )

    private val exerciseDto = """{"id":"ex-1","source":"trainer","names":{"pl":"Test"},"primaryMuscles":["ABDOMINALS"],"equipment":"BARBELL","trackingType":"REPS"}"""

    @Test
    fun exerciseUpsertAndHide() = runTest {
        val server = Server { r -> if (r.url.encodedPath.endsWith("dev/token")) token() else respond(exerciseDto, HttpStatusCode.OK, json) }
        val source = RemoteExerciseRemoteDataSource(server.api())
        assertTrue(source.upsert(exercise))
        assertTrue(source.setHidden("ex-1", true))
        assertTrue(source.setHidden("ex-1", false))
        assertEquals(
            listOf("PUT /api/v1/trainer/exercises/ex-1", "POST /api/v1/trainer/exercises/ex-1/hide", "POST /api/v1/trainer/exercises/ex-1/unhide"),
            server.paths(),
        )
        val failing = Server { r -> if (r.url.encodedPath.endsWith("dev/token")) token() else error(HttpStatusCode.Forbidden, "DENIED") }
        assertFalse(RemoteExerciseRemoteDataSource(failing.api()).upsert(exercise))
        val offline = Server { throw java.io.IOException("down") }
        assertFalse(RemoteExerciseRemoteDataSource(offline.api()).setHidden("ex-1", true))
    }

    private val upload = CreateUploadSessionRequest("m-1", MediaKind.EXERCISE_DEMO, 3, "video/mp4")

    @Test
    fun mediaUploadCreatesSessionThenPutsBytesAndReportsProgress() = runTest {
        var putAuth: String? = "unset"
        var putBytes = 0
        val server = Server { r ->
            when {
                r.url.encodedPath.endsWith("dev/token") -> token()
                r.url.encodedPath.endsWith("upload-sessions") ->
                    respond("""{"mediaId":"m-1","uploadUrl":"http://files.test/media/m-1/content?token=abc","expiresAt":"2026-11-11T12:00:00Z"}""", HttpStatusCode.Created, json)
                r.url.host == "files.test" -> {
                    putAuth = r.headers[HttpHeaders.Authorization]
                    putBytes = (r.body as ByteArrayContent).bytes().size
                    respond("", HttpStatusCode.NoContent)
                }
                else -> respond("""{"mediaId":"m-1","state":"READY","playbackUrl":"http://p"}""", HttpStatusCode.OK, json)
            }
        }
        val source = RemoteMediaRemoteDataSource(server.api(), HttpClient(server.engine), readBytes = { byteArrayOf(1, 2, 3) })
        val progress = mutableListOf<Float>()
        assertEquals(UploadResult.Uploaded, source.upload(upload, "/tmp/x.mp4") { progress += it })
        assertEquals(listOf(0f, 1f), progress)
        assertEquals(3, putBytes)
        assertNull(putAuth)
        val status = source.status("m-1")
        assertEquals(RemoteMediaState.READY, status?.state)
        assertEquals("GET /api/v1/trainer/media/m-1", server.paths().last())
    }

    @Test
    fun mediaUploadMapsFailures() = runTest {
        val offline = Server { throw java.io.IOException("down") }
        val source = RemoteMediaRemoteDataSource(offline.api(), HttpClient(offline.engine), readBytes = { byteArrayOf(1) })
        assertEquals(UploadResult.Interrupted, source.upload(upload, "x") {})
        assertNull(source.status("m-1"))

        val rejected = Server { r ->
            when {
                r.url.encodedPath.endsWith("dev/token") -> token()
                r.url.encodedPath.endsWith("upload-sessions") -> error(HttpStatusCode.UnprocessableEntity, "INVALID_INPUT")
                else -> respond("", HttpStatusCode.OK)
            }
        }
        assertEquals(
            UploadResult.Rejected,
            RemoteMediaRemoteDataSource(rejected.api(), HttpClient(rejected.engine), readBytes = { byteArrayOf(1) }).upload(upload, "x") {},
        )
    }
}
