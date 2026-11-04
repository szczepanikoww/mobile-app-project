package pl.trainerapp.feature.trainerchats.model

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

// Local stand-ins for the shared contract (:contract) until it exists. Keep them free of Android types.

data class Conversation(
    val clientId: String,
    val clientName: String,
    val lastMessage: String,
    val lastAt: Instant,
    val zone: TimeZone,
    val lastFromTrainer: Boolean,
    val unread: Int,
)

data class ChatMessage(
    val id: String,
    val fromTrainer: Boolean,
    val text: String,
    val at: Instant,
)

data class ChatThread(
    val clientId: String,
    val clientName: String,
    val messages: List<ChatMessage>,
    /** Server time of the snapshot; used for display only ("Wczoraj"), never for policy. */
    val generatedAt: Instant,
    val zone: TimeZone,
)

data class ConversationsSnapshot(
    val conversations: List<Conversation>,
    val generatedAt: Instant,
    val isOffline: Boolean,
    val lastUpdated: Instant,
) {
    val totalUnread: Int get() = conversations.sumOf { it.unread }
}
