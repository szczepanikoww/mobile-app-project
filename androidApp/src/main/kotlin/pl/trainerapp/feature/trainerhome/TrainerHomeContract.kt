package pl.trainerapp.feature.trainerhome

import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.HomeSnapshot
import pl.trainerapp.feature.trainerhome.model.SessionSummary
import pl.trainerapp.feature.trainerhome.model.TrainerHomeSummary
import java.time.Instant

enum class LoadStatus { Loading, Content, Error }

data class TrainerHomeUiState(
    val status: LoadStatus = LoadStatus.Loading,
    val summary: TrainerHomeSummary? = null,
    val isOffline: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastUpdated: Instant? = null,
)

sealed interface TrainerHomeAction {
    data object Refresh : TrainerHomeAction
    data class OpenSession(val session: SessionSummary) : TrainerHomeAction
    data class CheckIn(val session: SessionSummary) : TrainerHomeAction
    data class Message(val session: SessionSummary) : TrainerHomeAction
    data object OpenCalendar : TrainerHomeAction
    data object EmptyStateAction : TrainerHomeAction
    data class OpenAttention(val item: AttentionItem) : TrainerHomeAction
    data object OpenChats : TrainerHomeAction
    data object OpenNotifications : TrainerHomeAction
    data object OpenProfile : TrainerHomeAction
}

/** Destinations owned by other features. Until they exist the shell shows a "not available yet" message. */
sealed interface TrainerHomeDestination {
    data class SessionDetails(val sessionId: String) : TrainerHomeDestination
    data class CheckIn(val sessionId: String) : TrainerHomeDestination
    data class Conversation(val clientId: String) : TrainerHomeDestination
    data class TechniqueVideoReview(val mediaId: String) : TrainerHomeDestination
    data class ClientProfile(val clientId: String) : TrainerHomeDestination
    data object Calendar : TrainerHomeDestination
    data object Availability : TrainerHomeDestination
    data object Chats : TrainerHomeDestination
    data object Notifications : TrainerHomeDestination
    data object Profile : TrainerHomeDestination
}

sealed interface TrainerHomeEvent {
    data class Navigate(val destination: TrainerHomeDestination) : TrainerHomeEvent
}

interface TrainerHomeRepository {
    /** Returns fresh or cached data (flagged offline). Throws if there is neither. */
    suspend fun load(): HomeSnapshot
}
