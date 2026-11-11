package com.trainingapp.app.remote.media

import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.data.media.UploadResult
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.domain.workout.TechniqueVideoContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pl.trainerapp.feature.chat.data.remote.RemoteClientContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The client's media upload and technique-video outbox against a scripted server. Synthetic data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteClientMediaTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val mediaId = "11111111-1111-4111-8111-111111111111"
    private val conversations = """[{"relationshipId":"rel-1","counterpart":{"id":"t-1","name":"Trener","initials":"T"},"lastSeq":0,"unread":0,"canSend":true}]"""

    private class Server(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request -> requests += request; handler(request) }
        fun calls(method: HttpMethod, suffix: String) = requests.filter { it.method == method && it.url.encodedPath.endsWith(suffix) }
    }

    private fun Server.api() = ApiClient("http://test", engine = engine, token = { "dev.demo-client" })

    private fun Server.context() = RemoteClientContext(ApiClientChatServer(api()))

    private fun request(size: Long) = CreateUploadSessionRequest(mediaId, MediaKind.TECHNIQUE_VIDEO, size, "video/mp4")

    private fun session(offset: Long, size: Long, state: String = "UPLOADING") =
        """{"mediaId":"$mediaId","uploadUrl":"http://public.example/v1/media/uploads/$mediaId","offset":$offset,"sizeBytes":$size,"state":"$state"}"""

    private fun HttpRequestData.bytes() = (body as ByteArrayContent).bytes()

    private fun HttpRequestData.offsetHeader() = headers["Upload-Offset"]

    @Test
    fun uploadsInChunksBoundToTheRelationshipAndReportsProgress() = runTest {
        var offset = 0L
        val server = Server { r ->
            val path = r.url.encodedPath
            when {
                path.endsWith("v1/client/conversations") -> respond(conversations, HttpStatusCode.OK, json)
                path.endsWith("upload-sessions") -> respond(session(0, 10), HttpStatusCode.Created, json)
                r.method == HttpMethod.Patch -> {
                    offset += r.bytes().size
                    respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", offset.toString()))
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val source = RemoteClientMediaRemoteDataSource(server.api(), server.context(), readBytes = { ByteArray(10) { it.toByte() } }, chunkBytes = 4)
        val progress = mutableListOf<Float>()

        val result = source.upload(request(10), "/video.mp4") { progress += it }

        assertEquals(UploadResult.Uploaded, result)
        assertEquals(listOf(0f, 0.4f, 0.8f, 1f, 1f), progress)
        val create = server.calls(HttpMethod.Post, "upload-sessions").single()
        assertTrue((create.body as TextContent).text.contains("\"relationshipId\":\"rel-1\""))
        assertEquals("Bearer dev.demo-client", create.headers[HttpHeaders.Authorization])
        val patches = server.calls(HttpMethod.Patch, "v1/media/uploads/$mediaId")
        assertEquals(listOf("0", "4", "8"), patches.map { it.offsetHeader() })
        assertEquals(listOf(4, 4, 2), patches.map { it.bytes().size })
    }

    @Test
    fun resumesFromTheOffsetTheServerAlreadyHas() = runTest {
        val server = Server { r ->
            val path = r.url.encodedPath
            when {
                path.endsWith("v1/client/conversations") -> respond(conversations, HttpStatusCode.OK, json)
                path.endsWith("upload-sessions") -> respond(session(6, 10), HttpStatusCode.Created, json)
                else -> respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "10"))
            }
        }
        val source = RemoteClientMediaRemoteDataSource(server.api(), server.context(), readBytes = { ByteArray(10) }, chunkBytes = 4)

        assertEquals(UploadResult.Uploaded, source.upload(request(10), "/v.mp4") {})

        val patch = server.calls(HttpMethod.Patch, "uploads/$mediaId").single()
        assertEquals("6", patch.offsetHeader())
        assertEquals(4, patch.bytes().size)
    }

    @Test
    fun anOffsetMismatchAsksTheServerForItsOffsetAndContinues() = runTest {
        var patches = 0
        val server = Server { r ->
            val path = r.url.encodedPath
            when {
                path.endsWith("v1/client/conversations") -> respond(conversations, HttpStatusCode.OK, json)
                path.endsWith("upload-sessions") -> respond(session(0, 8), HttpStatusCode.Created, json)
                r.method == HttpMethod.Patch -> if (patches++ == 0) {
                    respond("""{"code":"CONFLICT","message":"offset mismatch"}""", HttpStatusCode.Conflict, json)
                } else {
                    respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "8"))
                }
                r.method == HttpMethod.Head -> respond("", HttpStatusCode.OK, headersOf("Upload-Offset", "4"))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val source = RemoteClientMediaRemoteDataSource(server.api(), server.context(), readBytes = { ByteArray(8) }, chunkBytes = 8)

        assertEquals(UploadResult.Uploaded, source.upload(request(8), "/v.mp4") {})

        assertEquals(listOf("0", "4"), server.calls(HttpMethod.Patch, "uploads/$mediaId").map { it.offsetHeader() })
        assertEquals(1, server.calls(HttpMethod.Head, "uploads/$mediaId").size)
    }

    @Test
    fun anAlreadyProcessingSessionIsDoneWithoutSendingBytes() = runTest {
        val server = Server { r ->
            val path = r.url.encodedPath
            when {
                path.endsWith("v1/client/conversations") -> respond(conversations, HttpStatusCode.OK, json)
                else -> respond(session(10, 10, "PROCESSING"), HttpStatusCode.Created, json)
            }
        }
        val source = RemoteClientMediaRemoteDataSource(server.api(), server.context(), readBytes = { error("not read") })
        assertEquals(UploadResult.Uploaded, source.upload(request(10), "/v.mp4") {})
        assertTrue(server.calls(HttpMethod.Patch, "").isEmpty())
    }

    @Test
    fun outcomesMapToInterruptedOrRejected() = runTest {
        fun source(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): RemoteClientMediaRemoteDataSource {
            val server = Server { r -> if (r.url.encodedPath.endsWith("conversations")) respond(conversations, HttpStatusCode.OK, json) else handler(r) }
            return RemoteClientMediaRemoteDataSource(server.api(), server.context(), readBytes = { ByteArray(10) })
        }
        // The relationship has ended / media id taken.
        assertEquals(UploadResult.Rejected, source { respond("""{"code":"CONFLICT","message":"x"}""", HttpStatusCode.Conflict, json) }.upload(request(10), "/v") {})
        // The file changed after it was announced.
        assertEquals(UploadResult.Rejected, source { respond(session(0, 99), HttpStatusCode.Created, json) }.upload(request(10), "/v") {})
        // Server trouble and a lost connection are retried later.
        assertEquals(UploadResult.Interrupted, source { respond("", HttpStatusCode.BadGateway) }.upload(request(10), "/v") {})
        assertEquals(UploadResult.Interrupted, source { throw java.io.IOException("down") }.upload(request(10), "/v") {})
    }

    @Test
    fun withoutARelationshipTheUploadStaysQueued() = runTest {
        val server = Server { respond("[]", HttpStatusCode.OK, json) }
        val source = RemoteClientMediaRemoteDataSource(server.api(), server.context(), readBytes = { ByteArray(10) })
        assertEquals(UploadResult.Interrupted, source.upload(request(10), "/v") {})
        assertTrue(server.calls(HttpMethod.Post, "upload-sessions").isEmpty())
    }

    @Test
    fun statusWaitsForProcessingAndRebasesThePlaybackUrl() = runTest {
        val answers = ArrayDeque(
            listOf(
                """{"mediaId":"$mediaId","state":"PROCESSING"}""",
                """{"mediaId":"$mediaId","state":"READY","playbackUrl":"http://localhost:8080/v1/media/$mediaId/content?exp=1&sig=ab"}""",
            ),
        )
        val server = Server { respond(answers.removeFirst(), HttpStatusCode.OK, json) }
        val source = RemoteClientMediaRemoteDataSource(server.api(), server.context(), baseUrl = "http://10.0.2.2:8080", statusDelayMs = 10)

        val status = source.status(mediaId)

        assertEquals(RemoteMediaState.READY, status?.state)
        assertEquals("http://10.0.2.2:8080/v1/media/$mediaId/content?exp=1&sig=ab", status?.playbackUrl)
    }

    @Test
    fun statusGivesUpQuietlyWhenTheServerIsUnreachableOrTheMediaUnknown() = runTest {
        assertNull(RemoteClientMediaRemoteDataSource(Server { throw java.io.IOException("down") }.run { api() }, RemoteClientContext(NoChat)).status(mediaId))
        val notFound = Server { respond("""{"code":"NOT_FOUND","message":"x"}""", HttpStatusCode.NotFound, json) }
        assertNull(RemoteClientMediaRemoteDataSource(notFound.api(), RemoteClientContext(NoChat)).status(mediaId))
    }

    // ---- outbox ----

    private val videoContext = TechniqueVideoContext("w1", "e1", "Martwy ciąg", 3, 4, 110_000, 5)

    private fun outboxFor(server: Server, scope: CoroutineScope) =
        RemoteTechniqueVideoOutbox(
            ApiClientChatServer(server.api()), server.context(), scope,
            dependencyRetryDelaysMs = listOf(100, 100), backgroundRetryDelaysMs = listOf(1_000, 1_000),
        )

    private val stored = """{"id":"$mediaId","seq":1,"senderId":"c-1","text":"x","createdAt":"2026-11-11T10:00:00Z","attachments":[{"mediaId":"$mediaId","state":"UPLOADING"}]}"""

    @Test
    fun sendsTheVideoMessageWithTheMediaIdAsMessageIdAndACaption() = runTest {
        val server = Server { r ->
            if (r.url.encodedPath.endsWith("conversations")) respond(conversations, HttpStatusCode.OK, json)
            else respond(stored, HttpStatusCode.Created, json)
        }

        assertTrue(outboxFor(server, this).send("", mediaId, videoContext))

        val post = server.calls(HttpMethod.Post, "v1/conversations/rel-1/messages").single()
        val body = (post.body as TextContent).text
        assertTrue(body.contains("\"id\":\"$mediaId\""))
        assertTrue(body.contains("\"mediaId\":\"$mediaId\""))
        assertTrue(body.contains("Martwy ciąg · seria 3/4 · 110 kg × 5"))
        assertFalse(body.contains("setLogId"))
    }

    @Test
    fun waitsForTheMediaToExistOnTheServerThenSends() = runTest {
        var posts = 0
        val server = Server { r ->
            when {
                r.url.encodedPath.endsWith("conversations") -> respond(conversations, HttpStatusCode.OK, json)
                posts++ < 2 -> respond("""{"code":"DEPENDENCY_MISSING","message":"x"}""", HttpStatusCode.Conflict, json)
                else -> respond(stored, HttpStatusCode.Created, json)
            }
        }
        assertTrue(outboxFor(server, this).send("rel-1", mediaId, videoContext))
        assertEquals(3, posts)
    }

    @Test
    fun whenOfflineItReturnsFalseAndKeepsRetryingInTheBackground() = runTest {
        var online = false
        var posts = 0
        val server = Server { r ->
            when {
                r.url.encodedPath.endsWith("conversations") -> respond(conversations, HttpStatusCode.OK, json)
                !online -> throw java.io.IOException("down")
                else -> {
                    posts++
                    respond(stored, HttpStatusCode.Created, json)
                }
            }
        }
        val background = TestScope(testScheduler)
        val sent = outboxFor(server, background).send("rel-1", mediaId, videoContext)
        assertFalse(sent)
        assertEquals(0, posts)

        online = true
        background.advanceTimeBy(1_100)
        background.runCurrent()
        // The HTTP engine answers on its own threads: wait in real time.
        withContext(Dispatchers.Default) { repeat(100) { if (posts == 0) delay(20) } }

        assertEquals(1, posts)
        background.advanceTimeBy(10_000)
        background.runCurrent()
        withContext(Dispatchers.Default) { delay(100) }
        assertEquals(1, posts) // stops after success
    }

    @Test
    fun aRefusalIsNotRetried() = runTest {
        var posts = 0
        val server = Server { r ->
            if (r.url.encodedPath.endsWith("conversations")) respond(conversations, HttpStatusCode.OK, json)
            else {
                posts++
                respond("""{"code":"CONFLICT","message":"the relationship is not active"}""", HttpStatusCode.Conflict, json)
            }
        }
        assertFalse(outboxFor(server, this).send("rel-1", mediaId, videoContext))
        assertEquals(1, posts)
    }

    private object NoChat : pl.trainerapp.feature.chat.data.remote.ChatServerApi {
        override suspend fun conversations() = emptyList<com.trainingapp.contract.chat.ConversationDto>()
        override suspend fun messages(relationshipId: String, afterSeq: Long, limit: Int) = error("unused")
        override suspend fun send(relationshipId: String, request: com.trainingapp.contract.chat.SendMessageRequest) = error("unused")
        override suspend fun markRead(relationshipId: String, upToSeq: Long) = Unit
        override suspend fun mediaStatus(mediaId: String) = null
    }
}
