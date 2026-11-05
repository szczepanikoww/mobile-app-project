package pl.trainerapp.shell

import pl.trainer.domain.PolicyRepository
import pl.trainer.domain.TrainerRepository
import pl.trainerapp.feature.chat.ChatMediaSource
import pl.trainerapp.feature.chat.TrainerChatsRepository
import pl.trainerapp.feature.chat.data.DemoChatStore
import pl.trainerapp.feature.trainerfinance.TrainerFinanceRepository
import pl.trainerapp.feature.trainerhome.TrainerHomeRepository
import pl.trainerapp.feature.trainernotifications.TrainerNotificationsRepository
import pl.trainerapp.feature.trainerprofile.TrainerProfileRepository

/** Data sources the trainer shell hands to its features. */
class TrainerRepositories(
    val home: TrainerHomeRepository,
    val finance: TrainerFinanceRepository,
    val chats: TrainerChatsRepository,
    val notifications: TrainerNotificationsRepository,
    val profile: TrainerProfileRepository,
    /** The calendar's sessions; check-in writes through it (A4). */
    val calendar: TrainerRepository,
    /** The shared conversation store behind both chats and the technique-video review (A5). */
    val chatStore: DemoChatStore,
    val chatMedia: ChatMediaSource,
    /** Cancellation policy versions (B5), from the shared demo scheduling store. */
    val policies: PolicyRepository,
)

/**
 * Full-screen views stacked above the bottom-navigation tabs. Encoded as strings so the back stack
 * survives configuration changes and process death.
 */
sealed interface TrainerRoute {
    val key: String

    data object Chats : TrainerRoute {
        override val key = "chats"
    }

    data class Chat(val clientId: String) : TrainerRoute {
        override val key = "chat:$clientId"
    }

    data object Notifications : TrainerRoute {
        override val key = "notifications"
    }

    data class Notification(val id: String) : TrainerRoute {
        override val key = "notification:$id"
    }

    data object Profile : TrainerRoute {
        override val key = "profile"
    }

    data class CheckIn(val sessionId: String) : TrainerRoute {
        override val key = "check-in:$sessionId"
    }

    data class TechniqueVideo(val mediaId: String) : TrainerRoute {
        override val key = "video:$mediaId"
    }

    /** B5 "Polityka odwołań", opened from the profile. */
    data object CancellationPolicy : TrainerRoute {
        override val key = "cancellation-policy"
    }

    companion object {
        fun fromKey(key: String): TrainerRoute? = when {
            key == "chats" -> Chats
            key.startsWith("chat:") -> Chat(key.removePrefix("chat:"))
            key == "notifications" -> Notifications
            key.startsWith("notification:") -> Notification(key.removePrefix("notification:"))
            key == "profile" -> Profile
            key.startsWith("check-in:") -> CheckIn(key.removePrefix("check-in:"))
            key.startsWith("video:") -> TechniqueVideo(key.removePrefix("video:"))
            key == "cancellation-policy" -> CancellationPolicy
            else -> null
        }
    }
}
