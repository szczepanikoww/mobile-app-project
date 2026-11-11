package pl.trainerapp.feature.chat.data.remote

import pl.trainerapp.feature.chat.ClientChatRepository
import pl.trainerapp.feature.chat.model.ChatMessage
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The client's own technique videos for the read-only player: found in the client's conversation with the trainer.
 * Review is a trainer-side concept, so nothing is ever "reviewed" here.
 */
class RemoteClientVideoStore(
    private val chat: ClientChatRepository,
    private val clock: Clock = Clock.System,
) : VideoReviewStore {
    private var trainerName: String = ""

    override val now: Instant get() = clock.now()

    override suspend fun findVideo(mediaId: String): Pair<String, ChatMessage>? {
        val thread = chat.thread()
        trainerName = thread.name
        val message = thread.messages.firstOrNull { it.attachment?.mediaId == mediaId } ?: return null
        return thread.relationshipId to message
    }

    override fun clientName(relationshipId: String): String = trainerName.ifEmpty { relationshipId }

    override suspend fun isReviewed(mediaId: String): Boolean = false

    override suspend fun markReviewed(mediaId: String) = Unit
}
