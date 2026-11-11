package pl.trainerapp.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import pl.trainer.resources.*
import pl.trainer.ui.Layout
import pl.trainerapp.core.designsystem.DetailHeader
import pl.trainerapp.core.designsystem.ErrorState
import pl.trainerapp.core.designsystem.InlineBanner
import pl.trainerapp.core.designsystem.ScreenLoading
import pl.trainerapp.core.format.formatListWhen
import pl.trainerapp.feature.chat.data.remote.VideoReviewStore
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole

enum class ReviewStatus { Loading, NotFound, Content }

/**
 * Draws the technique video: thumbnail, upload or processing state and, only when the media is READY, the player.
 * The app supplies it, because the media player lives in a feature module that `:shared` does not depend on.
 */
typealias TechniqueVideoCard = @Composable (asset: MediaAsset?, durationMs: Long?, onRetry: (() -> Unit)?) -> Unit

/** A neutral 16:9 frame without a player, for previews and tests. */
val NoTechniqueVideoCard: TechniqueVideoCard = { _, _, _ ->
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Layout.tile).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
    val now: kotlin.time.Instant? = null,
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
    private val store: VideoReviewStore,
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
    store: VideoReviewStore,
    chats: TrainerChatsRepository,
    mediaSource: ChatMediaSource,
    onBack: () -> Unit,
    onReviewed: () -> Unit = {},
    videoCard: TechniqueVideoCard = NoTechniqueVideoCard,
) {
    val viewModel = viewModel(key = "video-$viewer-$mediaId") { TechniqueVideoViewModel(mediaId, viewer, store, chats, mediaSource) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnReviewed by rememberUpdatedState(onReviewed)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == TechniqueVideoEvent.Reviewed) currentOnReviewed() }
    }
    TechniqueVideoScreen(state, onBack, viewModel::onReplyChange, viewModel::sendReply, viewModel::markReviewed, viewModel::retryUpload, viewModel::load, videoCard)
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
    videoCard: TechniqueVideoCard = NoTechniqueVideoCard,
) {
    val trainer = state.viewer == ChatRole.Trainer
    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(if (trainer) Res.string.video_review_title else Res.string.video_player_title), onBack = onBack)
        when (state.status) {
            ReviewStatus.Loading -> ScreenLoading(stringResource(Res.string.screen_loading))
            ReviewStatus.NotFound -> ErrorState(
                title = stringResource(Res.string.video_not_found),
                retryLabel = stringResource(Res.string.action_retry),
                onRetry = onRetryLoad,
                modifier = Modifier.padding(horizontal = Layout.gutter),
            )
            ReviewStatus.Content -> ReviewContent(state, onReplyChange, onSendReply, onMarkReviewed, onRetryUpload, videoCard)
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
    videoCard: TechniqueVideoCard,
) {
    val message = state.message ?: return
    val video = message.attachment ?: return
    val trainer = state.viewer == ChatRole.Trainer
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter),
        verticalArrangement = Arrangement.spacedBy(Layout.padding),
    ) {
        // 1. Playable only when READY; otherwise the card shows upload/processing progress or the failure.
        videoCard(state.asset, state.asset?.durationMs ?: video.durationMs, if (!trainer) onRetryUpload else null)
        if (state.asset == null) {
            Text(stringResource(Res.string.video_demo_unavailable), style = MaterialTheme.typography.bodySmall, color = muted)
        } else if (state.asset.state == MediaState.READY && state.asset.playbackUrl == null) {
            Text(stringResource(Res.string.video_demo_unavailable), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        // 2. Client, exercise, set context and date.
        Column(verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
            Text(video.exerciseName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(videoContext(video), style = MaterialTheme.typography.bodyMedium, color = muted)
            val now = state.now
            Text(
                listOfNotNull(state.clientName.takeIf { trainer }, now?.let { formatListWhen(message.at, TrainerZone, it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }
        // 3. The client's message.
        if (message.text.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(stringResource(if (trainer) Res.string.video_client_message else Res.string.video_your_message), style = MaterialTheme.typography.labelLarge, color = muted)
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (trainer) {
            // 4. Reply into the same conversation, quoting the video.
            OutlinedTextField(
                value = state.reply,
                onValueChange = onReplyChange,
                label = { Text(stringResource(Res.string.video_reply_label)) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                isError = state.sendFailed,
                supportingText = if (state.sendFailed) {
                    { Text(stringResource(Res.string.chat_send_failed)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSendReply,
                enabled = state.reply.isNotBlank() && !state.sending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (state.sending) CircularProgressIndicator(Modifier.padding(end = Layout.small).size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.video_reply_send))
            }
            if (state.replied) {
                InlineBanner(
                    stringResource(Res.string.video_reply_sent),
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
                Text(stringResource(if (state.reviewed) Res.string.video_reviewed else Res.string.video_mark_reviewed))
            }
        }
    }
}

private val TrainerZone = TimeZone.of("Europe/Warsaw")
