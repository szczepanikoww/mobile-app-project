package pl.trainerapp.feature.chat

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
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.ChatThread

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
                _events.send(ChatListEvent.OpenChat(action.conversation.relationshipId))
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

/** Where a conversation comes from: the trainer's thread with one client, or the client's thread with their trainer. */
interface ConversationSource {
    suspend fun thread(): ChatThread
    suspend fun send(text: String): ChatMessage
}

sealed interface ChatEvent {
    data class OpenVideo(val mediaId: String) : ChatEvent
}

/**
 * One conversation, role-neutral: the trainer chat and the client chat (A6) are the same screen and state, only the
 * [viewer] and the [source] differ.
 */
class ChatViewModel(
    private val viewer: ChatRole,
    private val source: ConversationSource,
    private val media: ChatMediaSource = ChatMediaSource.None,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(viewer = viewer))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _events = Channel<ChatEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val observedMedia = mutableSetOf<String>()

    init {
        refresh(quiet = false)
    }

    fun onAction(action: ChatAction) {
        when (action) {
            ChatAction.Refresh -> refresh(quiet = false)
            ChatAction.Reload -> refresh(quiet = true)
            is ChatAction.DraftChanged -> _state.update { it.copy(draft = action.text, sendFailed = false) }
            ChatAction.Send -> send()
            is ChatAction.OpenVideo -> viewModelScope.launch { _events.send(ChatEvent.OpenVideo(action.mediaId)) }
            is ChatAction.RetryVideo -> viewModelScope.launch { runCatching { media.retry(action.mediaId) } }
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
                val thread = source.thread()
                _state.update {
                    it.copy(
                        status = ChatLoadStatus.Content,
                        name = thread.name,
                        messages = thread.messages,
                        now = thread.generatedAt,
                        zone = thread.zone,
                        isRefreshing = false,
                    )
                }
                observeMedia(thread.messages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (hadData) it.copy(isRefreshing = false) else it.copy(status = ChatLoadStatus.Error, isRefreshing = false)
                }
            }
        }
    }

    /** Follows the upload state of each attached video, so a bubble shows progress and becomes playable only when READY. */
    private fun observeMedia(messages: List<ChatMessage>) {
        messages.mapNotNull { it.attachment?.mediaId }.filter { observedMedia.add(it) }.forEach { id ->
            viewModelScope.launch {
                media.observe(id).collect { asset ->
                    _state.update { s -> if (asset == null) s.copy(media = s.media - id) else s.copy(media = s.media + (id to asset)) }
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
                val message = source.send(text)
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
                // The draft stays in the input so nothing typed is lost.
                _state.update { it.copy(isSending = false, sendFailed = true) }
            }
        }
    }
}

/** The trainer's conversation with one client. */
fun TrainerChatViewModel(clientId: String, repository: TrainerChatsRepository, media: ChatMediaSource = ChatMediaSource.None) =
    ChatViewModel(
        ChatRole.Trainer,
        object : ConversationSource {
            override suspend fun thread() = repository.thread(clientId)
            override suspend fun send(text: String) = repository.send(clientId, text)
        },
        media,
    )

/** A6: the client's conversation with their trainer. */
fun ClientChatViewModel(repository: ClientChatRepository, media: ChatMediaSource = ChatMediaSource.None) =
    ChatViewModel(
        ChatRole.Client,
        object : ConversationSource {
            override suspend fun thread() = repository.thread()
            override suspend fun send(text: String) = repository.send(text)
        },
        media,
    )
