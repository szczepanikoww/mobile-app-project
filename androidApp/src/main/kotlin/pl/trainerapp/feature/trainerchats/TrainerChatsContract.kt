package pl.trainerapp.feature.trainerchats

import pl.trainerapp.feature.trainerchats.model.ChatMessage
import pl.trainerapp.feature.trainerchats.model.ChatThread
import pl.trainerapp.feature.trainerchats.model.Conversation
import pl.trainerapp.feature.trainerchats.model.ConversationsSnapshot
import java.time.Instant
import java.time.ZoneId

enum class ChatLoadStatus { Loading, Content, Error }

// --- Conversation list ---

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
    data class OpenChat(val clientId: String) : ChatListEvent
}

// --- Single conversation ---

data class ChatUiState(
    val status: ChatLoadStatus = ChatLoadStatus.Loading,
    val clientName: String = "",
    val messages: List<ChatMessage> = emptyList(),
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
}

interface TrainerChatsRepository {
    /** Returns fresh or cached data (flagged offline). Throws if there is neither. */
    suspend fun conversations(): ConversationsSnapshot

    /** Opening a thread marks it read. */
    suspend fun thread(clientId: String): ChatThread

    /** Throws when the message could not be delivered. */
    suspend fun send(clientId: String, text: String): ChatMessage
}
