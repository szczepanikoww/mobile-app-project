package pl.trainerapp.shell

import pl.trainerapp.feature.trainerchats.TrainerChatsRepository
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

    companion object {
        fun fromKey(key: String): TrainerRoute? = when {
            key == "chats" -> Chats
            key.startsWith("chat:") -> Chat(key.removePrefix("chat:"))
            key == "notifications" -> Notifications
            key.startsWith("notification:") -> Notification(key.removePrefix("notification:"))
            key == "profile" -> Profile
            else -> null
        }
    }
}
