package pl.trainerapp.feature.trainernotifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TrainerNotificationListViewModel(private val repository: TrainerNotificationsRepository) : ViewModel() {

    private val _state = MutableStateFlow(NotificationListUiState())
    val state: StateFlow<NotificationListUiState> = _state.asStateFlow()

    private val _events = Channel<NotificationListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh(quiet = false)
    }

    fun onAction(action: NotificationListAction) {
        when (action) {
            NotificationListAction.Refresh -> refresh(quiet = false)
            NotificationListAction.Reload -> refresh(quiet = true)
            is NotificationListAction.Open -> viewModelScope.launch {
                _events.send(NotificationListEvent.OpenNotification(action.notification.id))
            }
            NotificationListAction.MarkAllRead -> markAllRead()
        }
    }

    private fun markAllRead() {
        viewModelScope.launch {
            try {
                repository.markAllRead()
                refresh(quiet = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing changed on the server; the list keeps showing the unread items.
            }
        }
    }

    private fun refresh(quiet: Boolean) {
        val hadData = _state.value.snapshot != null
        if (!quiet) {
            _state.update {
                if (hadData) it.copy(isRefreshing = true) else it.copy(status = NotificationsLoadStatus.Loading)
            }
        }
        viewModelScope.launch {
            try {
                val snapshot = repository.notifications()
                _state.update {
                    it.copy(status = NotificationsLoadStatus.Content, snapshot = snapshot, isRefreshing = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing cached content when a refresh fails; otherwise surface a recoverable error.
                _state.update {
                    if (it.snapshot != null) {
                        it.copy(snapshot = it.snapshot.copy(isOffline = true), isRefreshing = false)
                    } else {
                        it.copy(status = NotificationsLoadStatus.Error, isRefreshing = false)
                    }
                }
            }
        }
    }
}

class TrainerNotificationDetailViewModel(
    private val id: String,
    private val repository: TrainerNotificationsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationDetailUiState())
    val state: StateFlow<NotificationDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        _state.update { it.copy(status = NotificationsLoadStatus.Loading) }
        viewModelScope.launch {
            try {
                val detail = repository.open(id)
                _state.update { it.copy(status = NotificationsLoadStatus.Content, detail = detail) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = NotificationsLoadStatus.Error) }
            }
        }
    }
}
