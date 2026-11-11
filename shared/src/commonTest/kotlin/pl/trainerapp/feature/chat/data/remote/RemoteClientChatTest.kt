package pl.trainerapp.feature.chat.data.remote

import com.trainingapp.contract.chat.AttachmentDto
import com.trainingapp.contract.chat.ConversationDto
import com.trainingapp.contract.chat.MessageDto
import com.trainingapp.contract.chat.MessagesDto
import com.trainingapp.contract.chat.SendMessageRequest
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.contract.schedule.TrainerRefDto
import com.trainingapp.domain.media.MediaState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import pl.trainerapp.feature.chat.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The client's chat and context against a scripted server. Synthetic data only. */
class RemoteClientChatTest {
    private val at = Instant.parse("2026-11-11T10:00:00Z")

    private class FakeServer : ChatServerApi {
        var conversations = listOf(
            ConversationDto("rel-1", TrainerRefDto("trainer-1", "Test Trener", "TT"), lastSeq = 2, unread = 1, canSend = true),
        )
        var messages = emptyList<MessageDto>()
        val sent = mutableListOf<SendMessageRequest>()
        val reads = mutableListOf<Long>()
        var failSends = 0
        var failConversations = false
        var status: MediaStatusDto? = null

        override suspend fun conversations(): List<ConversationDto> {
            if (failConversations) error("offline")
            return conversations
        }

        override suspend fun messages(relationshipId: String, afterSeq: Long, limit: Int) =
            MessagesDto(messages.filter { it.seq > afterSeq }.take(limit), lastSeq = messages.maxOfOrNull { it.seq } ?: 0, lastReadSeq = 0)

        override suspend fun send(relationshipId: String, request: SendMessageRequest): MessageDto {
            sent += request
            if (failSends > 0) {
                failSends--
                error("offline")
            }
            return MessageDto(request.id, 3, "client-1", request.text, Instant.parse("2026-11-11T11:00:00Z"))
        }

        override suspend fun markRead(relationshipId: String, upToSeq: Long) {
            reads += upToSeq
        }

        override suspend fun mediaStatus(mediaId: String) = status
    }

    private fun msg(id: String, seq: Long, sender: String, text: String?, vararg media: String) =
        MessageDto(id, seq, sender, text, at, attachments = media.map { AttachmentDto(it, RemoteMediaState.READY) })

    @Test
    fun contextFindsTheActiveRelationshipAndKeepsItWhenRefreshFails() = runTest {
        val server = FakeServer()
        val context = RemoteClientContext(server)
        assertNull(context.relationshipId)

        assertEquals("rel-1", context.refresh()?.relationshipId)
        assertEquals("rel-1", context.relationshipId)
        assertEquals("Test Trener", context.relationship.value?.trainerName)

        server.failConversations = true
        assertEquals("rel-1", context.refresh()?.relationshipId)

        context.clear()
        assertNull(context.relationshipId)
    }

    @Test
    fun contextPrefersAConversationThatCanStillSend() = runTest {
        val server = FakeServer()
        server.conversations = listOf(
            ConversationDto("old", TrainerRefDto("t0", "Dawny", "D"), 1, 0, canSend = false),
            ConversationDto("rel-2", TrainerRefDto("t2", "Obecny", "O"), 1, 0, canSend = true),
        )
        assertEquals("rel-2", RemoteClientContext(server).refresh()?.relationshipId)
    }

    @Test
    fun threadMapsRolesVideosAndRepliesAndMarksItRead() = runTest {
        val server = FakeServer()
        server.messages = listOf(
            msg("m1", 1, "trainer-1", "Cześć"),
            msg("m2", 2, "client-1", "Martwy ciąg · seria 3/4 · 110 kg × 5\nCzy plecy ok?", "media-1"),
            msg("m3", 3, "trainer-1", "Plecy prosto", "media-1"),
        )
        val repository = RemoteClientChatRepository(server, RemoteClientContext(server))

        val thread = repository.thread()

        assertEquals("Test Trener", thread.name)
        assertEquals("rel-1", repository.relationshipId)
        assertEquals(listOf(ChatRole.Trainer, ChatRole.Client, ChatRole.Trainer), thread.messages.map { it.author })
        val video = thread.messages[1]
        assertEquals("Czy plecy ok?", video.text)
        assertEquals("media-1", video.attachment?.mediaId)
        assertEquals("Martwy ciąg", video.attachment?.exerciseName)
        assertEquals(3, video.attachment?.setNumber)
        assertEquals(4, video.attachment?.setCount)
        assertEquals(110_000, video.attachment?.weightGrams)
        assertEquals(5, video.attachment?.reps)
        val reply = thread.messages[2]
        assertEquals("media-1", reply.quotedMediaId)
        assertNull(reply.attachment)
        assertEquals(listOf(3L), server.reads)
    }

    @Test
    fun aFailedReadMarkerDoesNotHideTheThread() = runTest {
        val server = object : ChatServerApi by FakeServer() {
            override suspend fun markRead(relationshipId: String, upToSeq: Long) = error("offline")
            override suspend fun conversations() = FakeServer().conversations
            override suspend fun messages(relationshipId: String, afterSeq: Long, limit: Int) =
                MessagesDto(listOf(MessageDto("m1", 1, "trainer-1", "Hej", Instant.parse("2026-11-11T10:00:00Z"))), 1, 0)
        }
        val thread = RemoteClientChatRepository(server, RemoteClientContext(server)).thread()
        assertEquals(1, thread.messages.size)
    }

    @Test
    fun threadFailsWithoutARelationship() = runTest {
        val server = FakeServer().apply { conversations = emptyList() }
        assertFailsWith<IllegalStateException> { RemoteClientChatRepository(server, RemoteClientContext(server)).thread() }
    }

    @Test
    fun aResentDraftReusesTheMessageIdSoARetryDoesNotDuplicate() = runTest {
        val server = FakeServer().apply { failSends = 1 }
        val repository = RemoteClientChatRepository(server, RemoteClientContext(server))

        assertFailsWith<IllegalStateException> { repository.send("Dzień dobry") }
        val message = repository.send("Dzień dobry")
        val next = repository.send("Dzień dobry")

        assertEquals(server.sent[0].id, server.sent[1].id)
        assertNotEquals(server.sent[1].id, server.sent[2].id)
        assertEquals(ChatRole.Client, message.author)
        assertEquals("Dzień dobry", message.text)
        assertEquals(3, server.sent.size)
        assertTrue(next.id == server.sent[2].id)
    }

    @Test
    fun cannotSendAfterTheRelationshipEnded() = runTest {
        val server = FakeServer()
        server.conversations = listOf(ConversationDto("rel-1", TrainerRefDto("t", "T", "T"), 1, 0, canSend = false))
        assertFailsWith<IllegalStateException> { RemoteClientChatRepository(server, RemoteClientContext(server)).send("x") }
        assertTrue(server.sent.isEmpty())
    }

    @Test
    fun remoteMediaSourcePollsUntilReadyAndOnlyThenGivesAPlaybackUrl() = runTest {
        val answers = ArrayDeque(
            listOf(
                MediaStatusDto("v", RemoteMediaState.UPLOADING),
                MediaStatusDto("v", RemoteMediaState.PROCESSING),
                MediaStatusDto("v", RemoteMediaState.READY, "http://public.example/v1/media/v/content?exp=1&sig=s"),
            ),
        )
        val source = RemoteChatMediaSource({ answers.removeFirst() }, pollMs = 10, rebase = { rebaseUrl(it, "http://10.0.2.2:8080") })

        val seen = source.observe("v").toList()

        assertEquals(listOf(MediaState.UPLOADING, MediaState.PROCESSING, MediaState.READY), seen.map { it?.state })
        assertNull(seen[0]?.playbackUrl)
        assertNull(seen[1]?.playbackUrl)
        assertEquals("http://10.0.2.2:8080/v1/media/v/content?exp=1&sig=s", seen[2]?.playbackUrl)
    }

    @Test
    fun remoteMediaSourceStopsOnFailureAndSurvivesErrors() = runTest {
        var calls = 0
        val source = RemoteChatMediaSource({
            calls++
            when (calls) {
                1 -> error("offline")
                2 -> null
                else -> MediaStatusDto("v", RemoteMediaState.FAILED)
            }
        }, pollMs = 10)
        assertEquals(listOf(MediaState.FAILED), source.observe("v").toList().map { it?.state })
    }

    @Test
    fun rebaseKeepsPathAndQuery() {
        assertEquals("http://h:1/a?b=c", rebaseUrl("https://x.y/a?b=c", "http://h:1/"))
        assertEquals("/rel", rebaseUrl("/rel", "http://h:1"))
    }
}
