package pl.trainerapp.feature.chat.data.remote

import com.trainingapp.contract.api.ConversationDto
import com.trainingapp.contract.api.MessageDto
import com.trainingapp.contract.api.ThreadDto
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.trainer.data.remote.TrainerApiClient
import pl.trainerapp.feature.chat.data.remote.TechniqueCaption.toMediaRef
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.MediaRef
import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.ClientRef
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the technique-video review screen needs from "the chat store". `DemoChatStore` has the same members; the
 * server-backed implementation is [RemoteTechniqueVideoReviews].
 */
interface VideoReviewStore {
    /** Display clock of the screen; never used for policy. */
    val now: Instant

    suspend fun findVideo(mediaId: String): Pair<String, ChatMessage>?

    fun clientName(relationshipId: String): String

    suspend fun isReviewed(mediaId: String): Boolean

    suspend fun markReviewed(mediaId: String)
}

/**
 * The trainer's technique videos from the server: client messages that carry a video, read from the trainer API
 * (`trainer/conversations/...`), with the media state from [mediaStatus] (`GET /v1/media/{id}` with the trainer's
 * token). A video counts as reviewed once the trainer has replied to it (a trainer message that carries the same
 * media) or marked it in this session; "marked" is kept in memory only because the server has no review marker yet.
 */
class RemoteTechniqueVideoReviews(
    private val api: TrainerApiClient,
    private val mediaStatus: suspend (mediaId: String) -> MediaStatusDto?,
    private val clock: Clock = Clock.System,
    /** Shown when the caption could not be read. */
    private val unknownExercise: String = "",
) : VideoReviewStore {

    private data class Found(val relationshipId: String, val message: ChatMessage, val hasReply: Boolean)

    private val lock = Mutex()
    private val names = mutableMapOf<String, String>()
    private val videos = mutableMapOf<String, Found>()
    private val marked = mutableSetOf<String>()

    override val now: Instant get() = clock.now()

    /** "Wymaga uwagi": unreviewed videos that are READY on the server, newest first. Unfinished uploads are left out. */
    suspend fun techniqueVideos(): List<AttentionItem.TechniqueVideo> {
        val found = scan()
        return found.entries
            .filter { it.key !in marked && !it.value.hasReply }
            .sortedByDescending { it.value.message.at }
            .filter { ready(it.key) }
            .map { (mediaId, f) ->
                val exercise = f.message.attachment?.exerciseName?.takeIf { it.isNotBlank() } ?: unknownExercise
                AttentionItem.TechniqueVideo(mediaId, ClientRef(f.relationshipId, names[f.relationshipId].orEmpty()), exercise)
            }
    }

    override suspend fun findVideo(mediaId: String): Pair<String, ChatMessage>? {
        val known = lock.withLock { videos[mediaId] } ?: scan()[mediaId]
        return known?.let { it.relationshipId to it.message }
    }

    override fun clientName(relationshipId: String): String = names[relationshipId] ?: relationshipId

    override suspend fun isReviewed(mediaId: String): Boolean =
        mediaId in marked || (lock.withLock { videos[mediaId]?.hasReply } ?: false)

    override suspend fun markReviewed(mediaId: String) {
        marked += mediaId
    }

    private suspend fun ready(mediaId: String): Boolean = try {
        mediaStatus(mediaId)?.state == RemoteMediaState.READY
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Reads every thread; a thread that fails to load is skipped, the list failing is an error. */
    private suspend fun scan(): Map<String, Found> {
        val conversations = api.get<List<ConversationDto>>("trainer/conversations")
        val result = mutableMapOf<String, Found>()
        for (conversation in conversations.filter { it.lastMessage != null }) {
            names[conversation.clientId] = conversation.clientName
            val messages = try {
                allMessages(conversation.clientId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val replied = messages.filter { it.mine }.flatMap { it.attachmentMediaIds }.toSet()
            for (m in messages) {
                val mediaId = m.attachmentMediaIds.firstOrNull() ?: continue
                if (m.mine) continue
                val caption = TechniqueCaption.parse(m.body)
                val ref = caption?.toMediaRef(mediaId) ?: MediaRef(mediaId, "", 0, 0)
                val message = ChatMessage(m.id, ChatRole.Client, caption?.note ?: m.body, m.createdAt, ref)
                result[mediaId] = Found(conversation.clientId, message, mediaId in replied)
            }
        }
        lock.withLock {
            videos.clear()
            videos += result
        }
        return result
    }

    private suspend fun allMessages(relationshipId: String): List<MessageDto> {
        val all = mutableListOf<MessageDto>()
        var afterSeq = 0L
        repeat(MAX_PAGES) {
            val page = api.get<ThreadDto>("trainer/conversations/$relationshipId/messages?afterSeq=$afterSeq&limit=$PAGE")
            all += page.messages
            if (page.messages.size < PAGE) return all
            afterSeq = page.messages.last().seq
        }
        return all
    }

    private companion object {
        const val PAGE = 200
        const val MAX_PAGES = 10
    }
}
