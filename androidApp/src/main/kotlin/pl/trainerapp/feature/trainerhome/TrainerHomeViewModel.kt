package pl.trainerapp.feature.trainerhome

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
import pl.trainerapp.feature.trainerhome.model.AttentionItem

class TrainerHomeViewModel(private val repository: TrainerHomeRepository) : ViewModel() {

    private val _state = MutableStateFlow(TrainerHomeUiState())
    val state: StateFlow<TrainerHomeUiState> = _state.asStateFlow()

    private val _events = Channel<TrainerHomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh()
    }

    fun onAction(action: TrainerHomeAction) {
        when (action) {
            TrainerHomeAction.Refresh -> refresh()
            is TrainerHomeAction.OpenSession -> navigate(TrainerHomeDestination.SessionDetails(action.session.id))
            is TrainerHomeAction.CheckIn -> if (!_state.value.isOffline) {
                navigate(TrainerHomeDestination.CheckIn(action.session.id))
            }
            is TrainerHomeAction.Message -> navigate(TrainerHomeDestination.Conversation(action.session.client.id))
            TrainerHomeAction.OpenCalendar -> navigate(TrainerHomeDestination.Calendar)
            TrainerHomeAction.EmptyStateAction -> navigate(TrainerHomeDestination.Availability)
            is TrainerHomeAction.OpenAttention -> openAttention(action.item)
            TrainerHomeAction.OpenChats -> navigate(TrainerHomeDestination.Chats)
            TrainerHomeAction.OpenNotifications -> navigate(TrainerHomeDestination.Notifications)
            TrainerHomeAction.OpenProfile -> navigate(TrainerHomeDestination.Profile)
        }
    }

    private fun refresh() {
        val hadData = _state.value.summary != null
        _state.update {
            if (hadData) it.copy(isRefreshing = true) else it.copy(status = LoadStatus.Loading)
        }
        viewModelScope.launch {
            try {
                val snapshot = repository.load()
                _state.update {
                    it.copy(
                        status = LoadStatus.Content,
                        summary = snapshot.summary,
                        isOffline = snapshot.isOffline,
                        lastUpdated = snapshot.lastUpdated,
                        isRefreshing = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing cached content when a refresh fails; otherwise surface a recoverable error.
                _state.update {
                    if (it.summary != null) {
                        it.copy(isOffline = true, isRefreshing = false)
                    } else {
                        it.copy(status = LoadStatus.Error, isRefreshing = false)
                    }
                }
            }
        }
    }

    private fun openAttention(item: AttentionItem) {
        val destination = when (item) {
            is AttentionItem.TechniqueVideo -> TrainerHomeDestination.TechniqueVideoReview(item.id)
            is AttentionItem.UnreadMessages -> TrainerHomeDestination.Conversation(item.client.id)
            is AttentionItem.OverduePayment -> TrainerHomeDestination.ClientProfile(item.client.id)
        }
        navigate(destination)
    }

    private fun navigate(destination: TrainerHomeDestination) {
        viewModelScope.launch { _events.send(TrainerHomeEvent.Navigate(destination)) }
    }
}
