package pl.trainerapp.feature.chat.data.remote

import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import pl.trainer.data.remote.TrainerApiClient
import pl.trainerapp.feature.chat.data.RemoteTrainerChatsRepository
import pl.trainerapp.feature.chat.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TechniqueCaptionAndReviewsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun captionRoundTrips() {
        val line = TechniqueCaption.format("Martwy ciąg", 3, 4, 112_500, 5)
        assertEquals("Martwy ciąg · seria 3/4 · 112.5 kg × 5", line)
        val parsed = TechniqueCaption.parse("$line\nNotatka")!!
        assertEquals(112_500, parsed.weightGrams)
        assertEquals(5, parsed.reps)
        assertEquals("Notatka", parsed.note)
        assertEquals("Wiosłowanie", TechniqueCaption.parse(TechniqueCaption.format("Wiosłowanie", 1, 3, null, null))?.exerciseName)
        assertNull(TechniqueCaption.parse("Zwykła wiadomość"))
        assertNull(TechniqueCaption.parse(null))
    }

    private val conversations = """
        [{"clientId":"rel-1","conversationId":"cv-1","clientName":"Test Klient","unread":2,
          "lastMessage":{"id":"m-3","seq":3,"senderId":"u-1","mine":false,"body":"x","createdAt":"2026-11-11T10:00:00Z"}},
         {"clientId":"rel-2","conversationId":"cv-2","clientName":"Inny Klient","unread":0,
          "lastMessage":{"id":"n-1","seq":1,"senderId":"u-2","mine":false,"body":"Wideo","createdAt":"2026-11-11T08:00:00Z","attachmentMediaIds":["media-3"]}}]
    """.trimIndent()

    private val thread1 = """
        {"conversationId":"cv-1","clientId":"rel-1","lastReadSeq":0,"messages":[
          {"id":"m-1","seq":1,"senderId":"u-1","mine":false,"body":"Martwy ciąg · seria 3/4 · 110 kg × 5\nSprawdzisz?","createdAt":"2026-11-11T09:00:00Z","attachmentMediaIds":["media-1"]},
          {"id":"m-2","seq":2,"senderId":"u-1","mine":false,"body":"Przysiad · seria 1/3","createdAt":"2026-11-11T09:30:00Z","attachmentMediaIds":["media-2"]},
          {"id":"m-3","seq":3,"senderId":"t-1","mine":true,"body":"Dobrze","createdAt":"2026-11-11T10:00:00Z","attachmentMediaIds":["media-2"]}]}
    """.trimIndent()

    private val thread2 = """
        {"conversationId":"cv-2","clientId":"rel-2","lastReadSeq":0,"messages":[
          {"id":"n-1","seq":1,"senderId":"u-2","mine":false,"body":"Wideo","createdAt":"2026-11-11T08:00:00Z","attachmentMediaIds":["media-3"]}]}
    """.trimIndent()

    private fun reviews(states: Map<String, RemoteMediaState>): RemoteTechniqueVideoReviews {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("dev/token") -> respond("""{"token":"t","userId":"t-1"}""", HttpStatusCode.OK, json)
                path.endsWith("rel-1/messages") -> respond(thread1, HttpStatusCode.OK, json)
                path.endsWith("rel-2/messages") -> respond(thread2, HttpStatusCode.OK, json)
                else -> respond(conversations, HttpStatusCode.OK, json)
            }
        }
        val api = TrainerApiClient("http://test", subject = { "demo-trainer" }, engine = HttpClient(engine))
        return RemoteTechniqueVideoReviews(api, { id -> states[id]?.let { MediaStatusDto(id, it) } })
    }

    @Test
    fun attentionListsOnlyReadyUnrepliedClientVideos() = runTest {
        val r = reviews(mapOf("media-1" to RemoteMediaState.READY, "media-2" to RemoteMediaState.READY, "media-3" to RemoteMediaState.PROCESSING))

        val items = r.techniqueVideos()

        // media-2 was answered by the trainer, media-3 is still processing.
        assertEquals(listOf("media-1"), items.map { it.id })
        assertEquals("Martwy ciąg", items.single().exercise)
        assertEquals("rel-1", items.single().client.id)
        assertEquals("Test Klient", items.single().client.name)
    }

    @Test
    fun markingAVideoReviewedRemovesItFromTheList() = runTest {
        val r = reviews(mapOf("media-1" to RemoteMediaState.READY, "media-3" to RemoteMediaState.READY))
        assertEquals(listOf("media-1", "media-3"), r.techniqueVideos().map { it.id })

        r.markReviewed("media-1")

        assertTrue(r.isReviewed("media-1"))
        assertEquals(listOf("media-3"), r.techniqueVideos().map { it.id })
        assertTrue(r.isReviewed("media-2")) // replied
    }

    @Test
    fun findVideoReturnsTheClientMessageWithItsCaption() = runTest {
        val r = reviews(emptyMap())
        val (relationship, message) = r.findVideo("media-1")!!
        assertEquals("rel-1", relationship)
        assertEquals(ChatRole.Client, message.author)
        assertEquals("Sprawdzisz?", message.text)
        assertEquals(3, message.attachment?.setNumber)
        assertEquals("Test Klient", r.clientName("rel-1"))
        assertNull(r.findVideo("nope"))
    }

    @Test
    fun trainerChatShowsTheCaptionAsAVideoCard() = runTest {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("dev/token") -> respond("""{"token":"t","userId":"t-1"}""", HttpStatusCode.OK, json)
                path.endsWith("/read") -> respond("", HttpStatusCode.NoContent)
                path.endsWith("rel-1/messages") -> respond(thread1, HttpStatusCode.OK, json)
                else -> respond(conversations, HttpStatusCode.OK, json)
            }
        }
        val repository = RemoteTrainerChatsRepository(TrainerApiClient("http://test", subject = { "demo-trainer" }, engine = HttpClient(engine)))

        val video = repository.thread("rel-1").messages.first()

        assertEquals("Sprawdzisz?", video.text)
        assertEquals("Martwy ciąg", video.attachment?.exerciseName)
        assertEquals(110_000, video.attachment?.weightGrams)
    }
}
