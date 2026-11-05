package pl.trainerapp.feature.chat.model

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

// Local stand-ins for the shared contract (:contract) until it exists. Keep them free of Android types.

/** Who wrote a message. "My" messages are styled the same in both roles. */
enum class ChatRole { Trainer, Client }

/** Delivery as far as this device knows. Read receipts need a backend. */
enum class Delivery { Sent, NotSent }

/**
 * A technique video attached to a message. Its upload state comes from the media repository by [mediaId]; the
 * bubble is playable only when the media is READY.
 */
data class MediaRef(
    val mediaId: String,
    val exerciseName: String,
    val setNumber: Int,
    val setCount: Int,
    val weightGrams: Int? = null,
    val reps: Int? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
)

/** One conversation row. [relationshipId] identifies the trainer–client relationship, not a global account. */
data class Conversation(
    val relationshipId: String,
    val name: String,
    val lastMessage: String,
    val lastAt: Instant,
    val zone: TimeZone,
    val lastFromMe: Boolean,
    val lastHasVideo: Boolean,
    val unread: Int,
)

data class ChatMessage(
    val id: String,
    val author: ChatRole,
    val text: String,
    val at: Instant,
    val attachment: MediaRef? = null,
    /** A reply to a technique video quotes it. */
    val quotedMediaId: String? = null,
    val delivery: Delivery = Delivery.Sent,
)

data class ChatThread(
    val relationshipId: String,
    /** The other person: the client for the trainer, the trainer for the client. */
    val name: String,
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

/** A technique video waiting for the trainer's review ("Wymaga uwagi"). */
data class PendingReview(val mediaId: String, val relationshipId: String, val clientName: String, val exerciseName: String)
