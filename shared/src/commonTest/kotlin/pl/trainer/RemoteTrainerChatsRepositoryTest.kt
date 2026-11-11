package pl.trainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import pl.trainerapp.feature.chat.data.RemoteTrainerChatsRepository
import pl.trainerapp.feature.chat.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server-backed trainer chats against a scripted server. Synthetic data only. */
class RemoteTrainerChatsRepositoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val conversationsJson = """
        [{"clientId":"rel-1","conversationId":"cv-1","clientName":"Test Klient","unread":2,
          "lastMessage":{"id":"m-2","seq":2,"senderId":"u-1","mine":false,"body":"Wideo","createdAt":"2026-11-11T10:00:00Z",
                         "attachmentMediaIds":["media-1"]}},
         {"clientId":"rel-2","conversationId":"cv-2","clientName":"Inny Klient","unread":0}]
    """.trimIndent()

    private val threadJson = """
        {"conversationId":"cv-1","clientId":"rel-1","lastReadSeq":0,"messages":[
          {"id":"m-1","seq":1,"senderId":"t-1","mine":true,"body":"Cześć","createdAt":"2026-11-11T09:00:00Z"},
          {"id":"m-2","seq":2,"senderId":"u-1","mine":false,"body":"Wideo","createdAt":"2026-11-11T10:00:00Z","attachmentMediaIds":["media-1"]}]}
    """.trimIndent()

    private class Server(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request -> requests += request; handler(request) }
        fun calls(path: String) = requests.filter { it.url.encodedPath.endsWith(path) }
    }

    private fun repository(server: Server) =
        RemoteTrainerChatsRepository(TrainerApiClient("http://test", subject = { "demo-trainer" }, engine = HttpClient(server.engine)))

    private fun MockRequestHandleScope.token() = respond("""{"token":"tok-1","userId":"t-1"}""", HttpStatusCode.OK, json)

    private fun HttpRequestData.text() = (body as TextContent).text

    @Test
    fun conversationsMapToTheAppModel() = runTest {
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("dev/token")) token() else respond(conversationsJson, HttpStatusCode.OK, json)
        }
        val snapshot = repository(server).conversations()

        assertFalse(snapshot.isOffline)
        assertEquals(2, snapshot.totalUnread)
        val first = snapshot.conversations.first()
        assertEquals("rel-1", first.relationshipId)
        assertEquals("Test Klient", first.name)
        assertEquals("Wideo", first.lastMessage)
        assertTrue(first.lastHasVideo)
        assertFalse(first.lastFromMe)
        val empty = snapshot.conversations.last()
        assertEquals("", empty.lastMessage)
        assertFalse(empty.lastHasVideo)
    }

    @Test
    fun openingAThreadMarksItRead() = runTest {
        val server = Server { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("dev/token") -> token()
                path.endsWith("/read") -> respond("", HttpStatusCode.NoContent)
                path.endsWith("/messages") -> respond(threadJson, HttpStatusCode.OK, json)
                else -> respond(conversationsJson, HttpStatusCode.OK, json)
            }
        }
        val thread = repository(server).thread("rel-1")

        assertEquals("Test Klient", thread.name)
        assertEquals(listOf("m-1", "m-2"), thread.messages.map { it.id })
        assertEquals(ChatRole.Trainer, thread.messages.first().author)
        val video = thread.messages.last()
        assertEquals(ChatRole.Client, video.author)
        assertEquals("media-1", video.attachment?.mediaId)
        val read = server.calls("/read").single()
        assertEquals(HttpMethod.Post, read.method)
        assertTrue(read.text().contains("\"seq\":2"))
    }

    @Test
    fun sendPostsTextWithAClientGeneratedIdAndReturnsTheMessage() = runTest {
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("dev/token")) token()
            else respond(
                """{"id":"m-9","seq":3,"senderId":"t-1","mine":true,"body":"Dobra robota","createdAt":"2026-11-11T11:00:00Z"}""",
                HttpStatusCode.Created, json,
            )
        }
        val message = repository(server).send("rel-1", "Dobra robota")

        assertEquals("Dobra robota", message.text)
        assertEquals(ChatRole.Trainer, message.author)
        assertNull(message.attachment)
        val post = server.calls("/rel-1/messages").single()
        assertEquals(HttpMethod.Post, post.method)
        val body = post.text()
        assertTrue(body.contains("\"body\":\"Dobra robota\""))
        assertTrue(Regex("\"id\":\"[0-9a-f-]{36}\"").containsMatchIn(body))
    }

    @Test
    fun replyToVideoReferencesTheMediaAndMapsToAQuote() = runTest {
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("dev/token")) token()
            else respond(
                """{"id":"m-9","seq":3,"senderId":"t-1","mine":true,"body":"Plecy prosto","createdAt":"2026-11-11T11:00:00Z","attachmentMediaIds":["media-1"]}""",
                HttpStatusCode.Created, json,
            )
        }
        val message = repository(server).replyToVideo("rel-1", "media-1", "Plecy prosto")

        assertEquals("media-1", message.quotedMediaId)
        assertNull(message.attachment)
        assertTrue(server.calls("/rel-1/messages").single().text().contains("\"attachmentMediaIds\":[\"media-1\"]"))
    }

    @Test
    fun offlineReturnsTheLastSnapshotFlaggedOffline() = runTest {
        var online = true
        val server = Server { request ->
            when {
                request.url.encodedPath.endsWith("dev/token") -> token()
                online -> respond(conversationsJson, HttpStatusCode.OK, json)
                else -> throw IllegalStateException("down")
            }
        }
        val repository = repository(server)
        val fresh = repository.conversations()
        online = false
        val cached = repository.conversations()

        assertTrue(cached.isOffline)
        assertEquals(fresh.conversations, cached.conversations)
        assertEquals(fresh.lastUpdated, cached.lastUpdated)
    }

    @Test
    fun offlineWithoutASnapshotAndFailedSendThrow() = runTest {
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("dev/token")) token() else throw IllegalStateException("down")
        }
        val repository = repository(server)

        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repository.conversations() }.failure)
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repository.send("rel-1", "x") }.failure)
        assertNotNull(server.requests)
    }
}
