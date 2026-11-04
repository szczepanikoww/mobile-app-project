package pl.trainerapp.feature.trainerchats

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

class TrainerChatListViewModel(private val repository: TrainerChatsRepository) : ViewModel() {

    private val _state = MutableStateFlow(ChatListUiState())
    val state: StateFlow<ChatListUiState> = _state.asStateFlow()

    private val _events = Channel<ChatListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh(quiet = false)
    }

    fun onAction(action: ChatListAction) {
        when (action) {
            ChatListAction.Refresh -> refresh(quiet = false)
            ChatListAction.Reload -> refresh(quiet = true)
            is ChatListAction.Open -> viewModelScope.launch {
                _events.send(ChatListEvent.OpenChat(action.conversation.clientId))
            }
        }
    }

    private fun refresh(quiet: Boolean) {
        val hadData = _state.value.snapshot != null
        if (!quiet) {
            _state.update {
                if (hadData) it.copy(isRefreshing = true) else it.copy(status = ChatLoadStatus.Loading)
            }
        }
        viewModelScope.launch {
            try {
                val snapshot = repository.conversations()
                _state.update { it.copy(status = ChatLoadStatus.Content, snapshot = snapshot, isRefreshing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing cached content when a refresh fails; otherwise surface a recoverable error.
                _state.update {
                    if (it.snapshot != null) {
                        it.copy(snapshot = it.snapshot.copy(isOffline = true), isRefreshing = false)
                    } else {
                        it.copy(status = ChatLoadStatus.Error, isRefreshing = false)
                    }
                }
            }
        }
    }
}

class TrainerChatViewModel(
    private val clientId: String,
    private val repository: TrainerChatsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        refresh(quiet = false)
    }

    fun onAction(action: ChatAction) {
        when (action) {
            ChatAction.Refresh -> refresh(quiet = false)
            ChatAction.Reload -> refresh(quiet = true)
            is ChatAction.DraftChanged -> _state.update { it.copy(draft = action.text, sendFailed = false) }
            ChatAction.Send -> send()
        }
    }

    private fun refresh(quiet: Boolean) {
        val hadData = _state.value.status == ChatLoadStatus.Content
        if (!quiet) {
            _state.update {
                if (hadData) it.copy(isRefreshing = true) else it.copy(status = ChatLoadStatus.Loading)
            }
        }
        viewModelScope.launch {
            try {
                val thread = repository.thread(clientId)
                _state.update {
                    it.copy(
                        status = ChatLoadStatus.Content,
                        clientName = thread.clientName,
                        messages = thread.messages,
                        now = thread.generatedAt,
                        zone = thread.zone,
                        isRefreshing = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (hadData) it.copy(isRefreshing = false) else it.copy(status = ChatLoadStatus.Error, isRefreshing = false)
                }
            }
        }
    }

    private fun send() {
        val current = _state.value
        val text = current.draft.trim()
        if (text.isEmpty() || current.isSending) return
        _state.update { it.copy(isSending = true, sendFailed = false) }
        viewModelScope.launch {
            try {
                val message = repository.send(clientId, text)
                _state.update {
                    it.copy(
                        messages = it.messages + message,
                        now = maxOf(it.now ?: message.at, message.at),
                        draft = "",
                        isSending = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The draft stays in the input so nothing the trainer typed is lost.
                _state.update { it.copy(isSending = false, sendFailed = true) }
            }
        }
    }
}
