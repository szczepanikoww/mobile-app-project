package pl.trainerapp.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trainingapp.domain.media.MediaAsset
import com.trainingapp.domain.media.MediaState
import com.trainingapp.feature.workoutbuilder.media.DemoVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.trainerapp.R
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.core.designsystem.DetailHeader
import pl.trainerapp.core.designsystem.ErrorState
import pl.trainerapp.core.designsystem.InlineBanner
import pl.trainerapp.core.designsystem.SkeletonBlock
import pl.trainerapp.core.designsystem.Spacing
import pl.trainerapp.core.format.formatListWhen
import pl.trainerapp.feature.chat.data.DemoChatStore
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole

enum class ReviewStatus { Loading, NotFound, Content }

data class TechniqueVideoUiState(
    val viewer: ChatRole = ChatRole.Trainer,
    val status: ReviewStatus = ReviewStatus.Loading,
    val relationshipId: String = "",
    val clientName: String = "",
    val message: ChatMessage? = null,
    val asset: MediaAsset? = null,
    val reply: String = "",
    val sending: Boolean = false,
    val sendFailed: Boolean = false,
    val replied: Boolean = false,
    val reviewed: Boolean = false,
    val markingReviewed: Boolean = false,
    val now: java.time.Instant? = null,
)

sealed interface TechniqueVideoEvent {
    data object Reviewed : TechniqueVideoEvent
}

/**
 * A5 "Przegląd wideo techniki" for the trainer; for the client the same screen is a read-only player. The video plays
 * only when its media is READY. A reply goes into the same conversation and quotes the video.
 */
class TechniqueVideoViewModel(
    private val mediaId: String,
    private val viewer: ChatRole,
    private val store: DemoChatStore,
    private val chats: TrainerChatsRepository,
    private val media: ChatMediaSource,
) : ViewModel() {
    private val _state = MutableStateFlow(TechniqueVideoUiState(viewer = viewer))
    val state: StateFlow<TechniqueVideoUiState> = _state.asStateFlow()
    private val _events = Channel<TechniqueVideoEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
        viewModelScope.launch { media.observe(mediaId).collect { asset -> _state.update { it.copy(asset = asset) } } }
    }

    fun load() {
        _state.update { it.copy(status = ReviewStatus.Loading) }
        viewModelScope.launch {
            val found = store.findVideo(mediaId)
            _state.update {
                if (found == null) {
                    it.copy(status = ReviewStatus.NotFound)
                } else {
                    it.copy(
                        status = ReviewStatus.Content,
                        relationshipId = found.first,
                        clientName = store.clientName(found.first),
                        message = found.second,
                        reviewed = store.isReviewed(mediaId),
                        now = store.now,
                    )
                }
            }
        }
    }

    fun onReplyChange(text: String) = _state.update { it.copy(reply = text, sendFailed = false) }

    fun sendReply() {
        val s = _state.value
        val text = s.reply.trim()
        if (viewer != ChatRole.Trainer || text.isEmpty() || s.sending) return
        _state.update { it.copy(sending = true, sendFailed = false) }
        viewModelScope.launch {
            try {
                chats.replyToVideo(s.relationshipId, mediaId, text)
                _state.update { it.copy(sending = false, reply = "", replied = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The reply stays in the field.
                _state.update { it.copy(sending = false, sendFailed = true) }
            }
        }
    }

    fun markReviewed() {
        val s = _state.value
        if (viewer != ChatRole.Trainer || s.reviewed || s.markingReviewed) return
        _state.update { it.copy(markingReviewed = true) }
        viewModelScope.launch {
            store.markReviewed(mediaId)
            _state.update { it.copy(markingReviewed = false, reviewed = true) }
            _events.send(TechniqueVideoEvent.Reviewed)
        }
    }

    fun retryUpload() {
        viewModelScope.launch { runCatching { media.retry(mediaId) } }
    }
}

@Composable
fun TechniqueVideoRoute(
    mediaId: String,
    viewer: ChatRole,
    store: DemoChatStore,
    chats: TrainerChatsRepository,
    mediaSource: ChatMediaSource,
    onBack: () -> Unit,
    onReviewed: () -> Unit = {},
) {
    val viewModel = viewModel(key = "video-$viewer-$mediaId") { TechniqueVideoViewModel(mediaId, viewer, store, chats, mediaSource) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnReviewed by rememberUpdatedState(onReviewed)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == TechniqueVideoEvent.Reviewed) currentOnReviewed() }
    }
    TechniqueVideoScreen(state, onBack, viewModel::onReplyChange, viewModel::sendReply, viewModel::markReviewed, viewModel::retryUpload, viewModel::load)
}

@Composable
fun TechniqueVideoScreen(
    state: TechniqueVideoUiState,
    onBack: () -> Unit,
    onReplyChange: (String) -> Unit,
    onSendReply: () -> Unit,
    onMarkReviewed: () -> Unit,
    onRetryUpload: () -> Unit,
    onRetryLoad: () -> Unit,
) {
    val trainer = state.viewer == ChatRole.Trainer
    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(if (trainer) R.string.video_review_title else R.string.video_player_title), onBack = onBack)
        when (state.status) {
            ReviewStatus.Loading -> Column(Modifier.padding(Spacing.screenGutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SkeletonBlock(height = 200.dp)
                SkeletonBlock(height = 48.dp)
            }
            ReviewStatus.NotFound -> ErrorState(
                title = stringResource(R.string.video_not_found),
                retryLabel = stringResource(R.string.action_retry),
                onRetry = onRetryLoad,
                modifier = Modifier.padding(horizontal = Spacing.screenGutter),
            )
            ReviewStatus.Content -> ReviewContent(state, onReplyChange, onSendReply, onMarkReviewed, onRetryUpload)
        }
    }
}

@Composable
private fun ReviewContent(
    state: TechniqueVideoUiState,
    onReplyChange: (String) -> Unit,
    onSendReply: () -> Unit,
    onMarkReviewed: () -> Unit,
    onRetryUpload: () -> Unit,
) {
    val message = state.message ?: return
    val video = message.attachment ?: return
    val trainer = state.viewer == ChatRole.Trainer
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screenGutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // 1. Playable only when READY; otherwise the card shows upload/processing progress or the failure.
        DemoVideoCard(
            thumbnailPath = state.asset?.thumbnailPath,
            durationMs = state.asset?.durationMs ?: video.durationMs,
            asset = state.asset,
            onRetry = if (!trainer) onRetryUpload else null,
        )
        if (state.asset == null) {
            Text(stringResource(R.string.video_demo_unavailable), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        } else if (state.asset.state == MediaState.READY && state.asset.playbackUrl == null) {
            Text(stringResource(R.string.video_demo_unavailable), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        // 2. Client, exercise, set context and date.
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(video.exerciseName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(videoContext(video), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
            val now = state.now
            Text(
                listOfNotNull(state.clientName.takeIf { trainer }, now?.let { formatListWhen(message.at, TrainerZone, it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
        // 3. The client's message.
        if (message.text.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(if (trainer) R.string.video_client_message else R.string.video_your_message), style = MaterialTheme.typography.labelLarge, color = colors.textMuted)
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (trainer) {
            // 4. Reply into the same conversation, quoting the video.
            OutlinedTextField(
                value = state.reply,
                onValueChange = onReplyChange,
                label = { Text(stringResource(R.string.video_reply_label)) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                isError = state.sendFailed,
                supportingText = if (state.sendFailed) {
                    { Text(stringResource(R.string.chat_send_failed)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSendReply,
                enabled = state.reply.isNotBlank() && !state.sending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.brand),
            ) {
                if (state.sending) CircularProgressIndicator(Modifier.padding(end = Spacing.sm).size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.video_reply_send))
            }
            if (state.replied) {
                InlineBanner(
                    stringResource(R.string.video_reply_sent),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    icon = Icons.Outlined.CheckCircle,
                )
            }
            // 5. One secondary action: removes the item from "Wymaga uwagi".
            OutlinedButton(
                onClick = onMarkReviewed,
                enabled = !state.reviewed && !state.markingReviewed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.reviewed) R.string.video_reviewed else R.string.video_mark_reviewed))
            }
        }
    }
}

private val TrainerZone = java.time.ZoneId.of("Europe/Warsaw")
