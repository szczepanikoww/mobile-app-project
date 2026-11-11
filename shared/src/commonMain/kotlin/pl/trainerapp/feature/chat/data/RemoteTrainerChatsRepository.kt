package pl.trainerapp.feature.chat.data

import com.trainingapp.contract.api.ConversationDto
import com.trainingapp.contract.api.MarkReadRequest
import com.trainingapp.contract.api.MessageDto
import com.trainingapp.contract.api.SendMessageRequest
import com.trainingapp.contract.api.ThreadDto
import io.ktor.http.HttpMethod
import kotlinx.datetime.TimeZone
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import pl.trainerapp.feature.chat.TrainerChatsRepository
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.ChatThread
import pl.trainerapp.feature.chat.model.Conversation
import pl.trainerapp.feature.chat.model.ConversationsSnapshot
import pl.trainerapp.feature.chat.model.MediaRef
import pl.trainerapp.feature.chat.data.remote.TechniqueCaption
import pl.trainerapp.feature.chat.data.remote.TechniqueCaption.toMediaRef
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The trainer's chats on the server (`trainer/conversations...`). `clientId` is the trainer-client relationship id.
 * Message text is private and is never logged.
 *
 * Mapping limits (the DTOs carry less than the app models):
 * - Messages have no quote field. [replyToVideo] sends the text with the video's media id in `attachmentMediaIds`
 *   (the server accepts READY media of that relationship); a trainer-authored message with an attachment is mapped
 *   back to [ChatMessage.quotedMediaId] and has no [ChatMessage.attachment].
 * - A client's attachment becomes a [MediaRef]; exercise, set, weight and reps come from the caption line the client
 *   app writes ([TechniqueCaption]); without one the exercise name is empty and the set number and count are 0.
 *   Duration and size are null. Upload state comes from the media
 *   repository by id. Only the first attachment is shown.
 * - The server sends no time zone or snapshot time: [zone] is used for display and the device clock stamps
 *   [ConversationsSnapshot.generatedAt] (display only).
 * - The thread has no name field; it comes from the last conversation list (empty until one was loaded).
 * - Delivery is always [pl.trainerapp.feature.chat.model.Delivery.Sent]: a message exists only once the server stored it.
 */
@OptIn(ExperimentalUuidApi::class)
class RemoteTrainerChatsRepository(
    private val api: TrainerApiClient,
    private val zone: TimeZone = TimeZone.of("Europe/Warsaw"),
    private val clock: Clock = Clock.System,
) : TrainerChatsRepository {

    private var snapshot: ConversationsSnapshot? = null
    private val names = mutableMapOf<String, String>()

    override suspend fun conversations(): ConversationsSnapshot = try {
        val now = clock.now()
        val rows = api.get<List<ConversationDto>>("trainer/conversations")
        rows.forEach { names[it.clientId] = it.clientName }
        ConversationsSnapshot(rows.map { it.toConversation(now) }, generatedAt = now, isOffline = false, lastUpdated = now)
            .also { snapshot = it }
    } catch (e: TrainerException) {
        val cached = snapshot
        if (e.failure == Failure.Offline && cached != null) cached.copy(isOffline = true) else throw e
    }

    override suspend fun thread(clientId: String): ChatThread {
        val messages = mutableListOf<MessageDto>()
        var afterSeq = 0L
        var lastReadSeq = 0L
        while (true) {
            val page = api.get<ThreadDto>("trainer/conversations/$clientId/messages?afterSeq=$afterSeq&limit=$PAGE")
            lastReadSeq = page.lastReadSeq
            messages += page.messages
            if (page.messages.size < PAGE) break
            afterSeq = page.messages.last().seq
        }
        val last = messages.maxOfOrNull { it.seq }
        if (last != null && last > lastReadSeq) {
            // A failed read marker must not hide the messages; the thread is marked on the next open.
            try {
                api.command(HttpMethod.Post, "trainer/conversations/$clientId/read", MarkReadRequest(last))
            } catch (_: TrainerException) {
            }
            snapshot = snapshot?.let { s ->
                s.copy(conversations = s.conversations.map { if (it.relationshipId == clientId) it.copy(unread = 0) else it })
            }
        }
        if (names[clientId] == null) {
            // Best effort: the header name comes from the list.
            try {
                conversations()
            } catch (_: TrainerException) {
            }
        }
        val now = clock.now()
        return ChatThread(clientId, names[clientId].orEmpty(), messages.sortedBy { it.seq }.map { it.toMessage() }, now, zone)
    }

    override suspend fun send(clientId: String, text: String): ChatMessage = post(clientId, text, emptyList())

    override suspend fun replyToVideo(clientId: String, mediaId: String, text: String): ChatMessage =
        post(clientId, text, listOf(mediaId))

    private suspend fun post(clientId: String, text: String, attachments: List<String>): ChatMessage =
        api.send<SendMessageRequest, MessageDto>(
            HttpMethod.Post,
            "trainer/conversations/$clientId/messages",
            SendMessageRequest(id = Uuid.random().toString(), body = text, attachmentMediaIds = attachments),
        ).toMessage()

    private fun ConversationDto.toConversation(now: Instant) = Conversation(
        relationshipId = clientId,
        name = clientName,
        lastMessage = lastMessage?.body.orEmpty(),
        lastAt = lastMessage?.createdAt ?: now,
        zone = zone,
        lastFromMe = lastMessage?.mine ?: false,
        lastHasVideo = lastMessage?.attachmentMediaIds?.isNotEmpty() == true,
        unread = unread,
    )

    private fun MessageDto.toMessage(): ChatMessage {
        val author = if (mine) ChatRole.Trainer else ChatRole.Client
        val media = attachmentMediaIds.firstOrNull()
        // A client's video message starts with a caption line (exercise and set), see TechniqueCaption.
        val caption = if (media != null && !mine) TechniqueCaption.parse(body) else null
        return ChatMessage(
            id = id,
            author = author,
            text = caption?.note ?: body,
            at = createdAt,
            attachment = if (media != null && !mine) caption?.toMediaRef(media) ?: MediaRef(media, exerciseName = "", setNumber = 0, setCount = 0) else null,
            quotedMediaId = if (media != null && mine) media else null,
        )
    }

    private companion object {
        const val PAGE = 200
    }
}
