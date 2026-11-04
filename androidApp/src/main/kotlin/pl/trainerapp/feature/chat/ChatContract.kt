package pl.trainerapp.feature.chat

import com.trainingapp.domain.media.MediaAsset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.ChatThread
import pl.trainerapp.feature.chat.model.Conversation
import pl.trainerapp.feature.chat.model.ConversationsSnapshot
import java.time.Instant
import java.time.ZoneId

enum class ChatLoadStatus { Loading, Content, Error }

// --- Conversation list (trainer) ---

data class ChatListUiState(
    val status: ChatLoadStatus = ChatLoadStatus.Loading,
    val snapshot: ConversationsSnapshot? = null,
    val isRefreshing: Boolean = false,
)

sealed interface ChatListAction {
    data object Refresh : ChatListAction

    /** Silent refresh when the list comes back on screen. */
    data object Reload : ChatListAction
    data class Open(val conversation: Conversation) : ChatListAction
}

sealed interface ChatListEvent {
    data class OpenChat(val relationshipId: String) : ChatListEvent
}

// --- Single conversation (both roles) ---

data class ChatUiState(
    /** Whose messages are "mine": the same bubble style in both roles. */
    val viewer: ChatRole = ChatRole.Trainer,
    val status: ChatLoadStatus = ChatLoadStatus.Loading,
    /** The other person's name, shown in the header. */
    val name: String = "",
    val messages: List<ChatMessage> = emptyList(),
    /** Upload state of attached technique videos, by media id. */
    val media: Map<String, MediaAsset> = emptyMap(),
    val now: Instant? = null,
    val zone: ZoneId = ZoneId.of("Europe/Warsaw"),
    val draft: String = "",
    val isSending: Boolean = false,
    val sendFailed: Boolean = false,
    val isRefreshing: Boolean = false,
)

sealed interface ChatAction {
    data object Refresh : ChatAction
    data object Reload : ChatAction
    data class DraftChanged(val text: String) : ChatAction
    data object Send : ChatAction
    data class OpenVideo(val mediaId: String) : ChatAction
    data class RetryVideo(val mediaId: String) : ChatAction
}

interface TrainerChatsRepository {
    /** Returns fresh or cached data (flagged offline). Throws if there is neither. */
    suspend fun conversations(): ConversationsSnapshot

    /** Opening a thread marks it read. [clientId] is the relationship id. */
    suspend fun thread(clientId: String): ChatThread

    /** Throws when the message could not be delivered. */
    suspend fun send(clientId: String, text: String): ChatMessage

    /** A reply from the technique-video review; it quotes the video. Throws when not delivered. */
    suspend fun replyToVideo(clientId: String, mediaId: String, text: String): ChatMessage
}

/** The client's single conversation with their trainer. */
interface ClientChatRepository {
    val relationshipId: String

    /** Opening the thread marks it read for the client. */
    suspend fun thread(): ChatThread

    /** Throws when the message could not be delivered. */
    suspend fun send(text: String): ChatMessage
}

/** Upload state of chat media. The Android app reads the shared media repository; tests and previews use [None]. */
interface ChatMediaSource {
    fun observe(mediaId: String): Flow<MediaAsset?>

    suspend fun retry(mediaId: String)

    object None : ChatMediaSource {
        override fun observe(mediaId: String): Flow<MediaAsset?> = flowOf(null)
        override suspend fun retry(mediaId: String) = Unit
    }
}
