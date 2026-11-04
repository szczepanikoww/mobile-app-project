package pl.trainerapp.feature.trainernotifications

import pl.trainerapp.feature.trainernotifications.model.NotificationDetail
import pl.trainerapp.feature.trainernotifications.model.NotificationTarget
import pl.trainerapp.feature.trainernotifications.model.NotificationsSnapshot
import pl.trainerapp.feature.trainernotifications.model.TrainerNotification

enum class NotificationsLoadStatus { Loading, Content, Error }

// --- List ---

data class NotificationListUiState(
    val status: NotificationsLoadStatus = NotificationsLoadStatus.Loading,
    val snapshot: NotificationsSnapshot? = null,
    val isRefreshing: Boolean = false,
)

sealed interface NotificationListAction {
    data object Refresh : NotificationListAction

    /** Silent refresh when the list comes back on screen. */
    data object Reload : NotificationListAction
    data class Open(val notification: TrainerNotification) : NotificationListAction
    data object MarkAllRead : NotificationListAction
}

sealed interface NotificationListEvent {
    data class OpenNotification(val id: String) : NotificationListEvent
}

// --- Detail ---

data class NotificationDetailUiState(
    val status: NotificationsLoadStatus = NotificationsLoadStatus.Loading,
    val detail: NotificationDetail? = null,
)

sealed interface NotificationDetailAction {
    data object Retry : NotificationDetailAction
    data class OpenTarget(val target: NotificationTarget) : NotificationDetailAction
}

interface TrainerNotificationsRepository {
    /** Returns fresh or cached data (flagged offline). Throws if there is neither. */
    suspend fun notifications(): NotificationsSnapshot

    /** Opening a notification marks it read. Throws when it does not exist. */
    suspend fun open(id: String): NotificationDetail

    suspend fun markAllRead()
}
