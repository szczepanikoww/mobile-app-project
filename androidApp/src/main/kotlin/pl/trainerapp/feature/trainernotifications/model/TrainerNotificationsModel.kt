package pl.trainerapp.feature.trainernotifications.model

import java.time.Instant
import java.time.ZoneId

// Local stand-ins for the shared contract (:contract) until it exists. Keep them free of Android types.

enum class NotificationType { Payment, Session, Message, Video, System }

/** Where the "open" button of a notification leads. */
sealed interface NotificationTarget {
    data class Chat(val clientId: String, val clientName: String) : NotificationTarget
    data object Finance : NotificationTarget
    data class Session(val sessionId: String) : NotificationTarget
    data object None : NotificationTarget
}

data class TrainerNotification(
    val id: String,
    val type: NotificationType,
    val title: String,
    /** One-line preview shown in the list. */
    val body: String,
    /** Full text shown in the detail view. */
    val detail: String,
    val at: Instant,
    val zone: ZoneId,
    val read: Boolean,
    val target: NotificationTarget = NotificationTarget.None,
)

data class NotificationsSnapshot(
    val items: List<TrainerNotification>,
    /** Server time of the snapshot; used for display only ("Wczoraj"), never for policy. */
    val generatedAt: Instant,
    val isOffline: Boolean,
    val lastUpdated: Instant,
) {
    val unreadCount: Int get() = items.count { !it.read }
}

data class NotificationDetail(
    val notification: TrainerNotification,
    val generatedAt: Instant,
)
