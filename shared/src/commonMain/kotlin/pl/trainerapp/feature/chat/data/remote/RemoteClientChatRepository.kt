package pl.trainerapp.feature.chat.data.remote

import com.trainingapp.contract.chat.MessageDto
import com.trainingapp.contract.chat.SendMessageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import pl.trainerapp.feature.chat.ClientChatRepository
import pl.trainerapp.feature.chat.data.remote.TechniqueCaption.toMediaRef
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.ChatThread
import pl.trainerapp.feature.chat.model.MediaRef
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The client's conversation with their trainer on the server (`/v1/conversations/{relationshipId}/...`). Messages
 * are the same rows the trainer reads through the trainer API. Message text is private and never logged.
 *
 * - "Mine" is every message not sent by the trainer ([ClientRelationship.trainerId]).
 * - A client message with a video becomes a [MediaRef] (exercise and set parsed from [TechniqueCaption]); a trainer
 *   message that carries a video is the trainer's reply and maps to [ChatMessage.quotedMediaId]. Upload state of
 *   the videos comes from the [pl.trainerapp.feature.chat.ChatMediaSource].
 * - A resent draft reuses the id of the failed attempt, so a retry after a lost answer does not duplicate it.
 */
@OptIn(ExperimentalUuidApi::class)
class RemoteClientChatRepository(
    private val api: ChatServerApi,
    private val context: RemoteClientContext,
    private val zone: TimeZone = TimeZone.of("Europe/Warsaw"),
    private val clock: Clock = Clock.System,
) : ClientChatRepository {

    override val relationshipId: String get() = context.relationshipId.orEmpty()

    private val failedSends = mutableMapOf<String, String>() // text to message id

    override suspend fun thread(): ChatThread {
        val relationship = context.refresh() ?: throw IllegalStateException("no trainer relationship")
        val id = relationship.relationshipId
        val messages = mutableListOf<MessageDto>()
        var afterSeq = 0L
        while (true) {
            val page = api.messages(id, afterSeq, PAGE)
            messages += page.messages
            if (page.messages.size < PAGE) break
            afterSeq = page.messages.last().seq
        }
        val newest = messages.maxOfOrNull { it.seq } ?: 0L
        if (newest > 0) {
            // A failed read marker must not hide the messages; it is sent again on the next open.
            try {
                api.markRead(id, newest)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return ChatThread(
            relationshipId = id,
            name = relationship.trainerName,
            messages = messages.sortedBy { it.seq }.map { it.toMessage(relationship.trainerId) },
            generatedAt = clock.now(),
            zone = zone,
        )
    }

    override suspend fun send(text: String): ChatMessage {
        val relationship = context.ensure() ?: throw IllegalStateException("no trainer relationship")
        check(relationship.canSend) { "relationship ended" }
        val messageId = failedSends.getOrPut(text) { Uuid.random().toString() }
        val stored = api.send(relationship.relationshipId, SendMessageRequest(id = messageId, text = text))
        failedSends.remove(text)
        return stored.toMessage(relationship.trainerId)
    }

    private fun MessageDto.toMessage(trainerId: String): ChatMessage {
        val fromTrainer = senderId == trainerId
        val media = attachments.firstOrNull()?.mediaId
        val caption = if (media != null && !fromTrainer) TechniqueCaption.parse(text) else null
        return ChatMessage(
            id = id,
            author = if (fromTrainer) ChatRole.Trainer else ChatRole.Client,
            text = caption?.note ?: text.orEmpty(),
            at = createdAt,
            attachment = if (media != null && !fromTrainer) caption?.toMediaRef(media) ?: MediaRef(media, "", 0, 0) else null,
            quotedMediaId = if (media != null && fromTrainer) media else null,
        )
    }

    private companion object {
        const val PAGE = 200
    }
}
