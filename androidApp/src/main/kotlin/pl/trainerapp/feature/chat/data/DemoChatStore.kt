package pl.trainerapp.feature.chat.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.ChatThread
import pl.trainerapp.feature.chat.model.Conversation
import pl.trainerapp.feature.chat.model.MediaRef
import pl.trainerapp.feature.chat.model.PendingReview
import java.time.Instant

/**
 * The one in-memory conversation store shared by the trainer and the client views, keyed by the trainer–client
 * relationship. A message one role sends appears in the other role's thread with an unread count. Demo only:
 * nothing is sent anywhere and it resets on restart.
 */
class DemoChatStore(
    private val fixtures: ChatFixtures = TrainerChatsFixtures,
    /** Display clock of the demo; advances one minute per message. Never used for policy. */
    start: Instant = fixtures.now,
) {
    private val mutex = Mutex()
    private val threads = fixtures.threads.mapValues { it.value.toMutableList() }.toMutableMap()
    private val unread = mutableMapOf<Pair<String, ChatRole>, Int>().apply {
        fixtures.unread.forEach { (id, count) -> put(id to ChatRole.Trainer, count) }
    }
    private val reviewed = mutableSetOf<String>()
    private var clock: Instant = start
    private var counter = 0

    private val _version = MutableStateFlow(0)

    /** Increments on every change, so open screens can refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    val now: Instant get() = clock

    suspend fun conversations(viewer: ChatRole): List<Conversation> = mutex.withLock {
        fixtures.clients.mapNotNull { (id, clientName) ->
            val last = threads[id]?.lastOrNull() ?: return@mapNotNull null
            Conversation(
                relationshipId = id,
                name = if (viewer == ChatRole.Trainer) clientName else fixtures.trainerName,
                lastMessage = last.text,
                lastAt = last.at,
                zone = fixtures.zone,
                lastFromMe = last.author == viewer,
                lastHasVideo = last.attachment != null,
                unread = unread[id to viewer] ?: 0,
            )
        }.sortedByDescending { it.lastAt }
    }

    /** Opening a thread marks it read for [viewer]. */
    suspend fun open(relationshipId: String, viewer: ChatRole): ChatThread = mutex.withLock {
        unread.remove(relationshipId to viewer)
        ChatThread(
            relationshipId = relationshipId,
            name = if (viewer == ChatRole.Trainer) fixtures.clients[relationshipId] ?: relationshipId else fixtures.trainerName,
            messages = threads[relationshipId].orEmpty().toList(),
            generatedAt = clock,
            zone = fixtures.zone,
        ).also { _version.value++ }
    }

    suspend fun send(
        relationshipId: String,
        author: ChatRole,
        text: String,
        attachment: MediaRef? = null,
        quotedMediaId: String? = null,
    ): ChatMessage = mutex.withLock {
        clock = clock.plusSeconds(60)
        val message = ChatMessage("msg-${counter++}", author, text, clock, attachment, quotedMediaId)
        threads.getOrPut(relationshipId) { mutableListOf() } += message
        val other = if (author == ChatRole.Trainer) ChatRole.Client else ChatRole.Trainer
        unread[relationshipId to other] = (unread[relationshipId to other] ?: 0) + 1
        _version.value++
        message
    }

    /** The message that carries [mediaId], with its relationship, or `null`. */
    suspend fun findVideo(mediaId: String): Pair<String, ChatMessage>? = mutex.withLock {
        threads.entries.firstNotNullOfOrNull { (id, messages) ->
            messages.firstOrNull { it.attachment?.mediaId == mediaId }?.let { id to it }
        }
    }

    fun clientName(relationshipId: String): String = fixtures.clients[relationshipId] ?: relationshipId

    suspend fun markReviewed(mediaId: String) = mutex.withLock {
        reviewed += mediaId
        _version.value++
    }

    suspend fun isReviewed(mediaId: String): Boolean = mutex.withLock { mediaId in reviewed }

    /** Client technique videos the trainer has not marked as reviewed, newest first. */
    suspend fun pendingReviews(): List<PendingReview> = mutex.withLock {
        threads.flatMap { (id, messages) ->
            messages.mapNotNull { message ->
                val video = message.attachment
                if (message.author == ChatRole.Client && video != null && video.mediaId !in reviewed) {
                    message.at to PendingReview(video.mediaId, id, fixtures.clients[id] ?: id, video.exerciseName)
                } else {
                    null
                }
            }
        }.sortedByDescending { it.first }.map { it.second }
    }
}
