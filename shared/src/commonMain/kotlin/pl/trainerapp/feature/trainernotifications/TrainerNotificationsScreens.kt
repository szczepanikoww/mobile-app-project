package pl.trainerapp.feature.trainernotifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainer.resources.*
import pl.trainerapp.core.designsystem.AppShapes
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.core.designsystem.DetailHeader
import pl.trainerapp.core.designsystem.EmptyState
import pl.trainerapp.core.designsystem.ErrorState
import pl.trainerapp.core.designsystem.InlineBanner
import pl.trainerapp.core.designsystem.PrimaryButton
import pl.trainerapp.core.designsystem.SkeletonBlock
import pl.trainerapp.core.designsystem.Spacing
import pl.trainerapp.core.designsystem.frosted
import pl.trainerapp.core.designsystem.IconCircle
import pl.trainerapp.core.designsystem.OutlinedTile
import pl.trainerapp.core.designsystem.ScreenLoading
import pl.trainer.ui.AppColors as SharedColors
import pl.trainer.ui.Layout
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.ui.draw.clip
import pl.trainerapp.core.format.formatListWhen
import pl.trainerapp.core.format.formatPaymentWhen
import pl.trainerapp.core.format.formatUpdatedAt
import pl.trainerapp.feature.trainernotifications.model.NotificationTarget
import pl.trainerapp.feature.trainernotifications.model.NotificationType
import pl.trainerapp.feature.trainernotifications.model.TrainerNotification
import kotlin.time.Instant

private val Gutter = Modifier.padding(horizontal = Spacing.screenGutter)

private fun NotificationType.icon(): ImageVector = when (this) {
    NotificationType.Payment -> Icons.Outlined.Payments
    NotificationType.Session -> Icons.Outlined.EventAvailable
    NotificationType.Message -> Icons.Outlined.ChatBubbleOutline
    NotificationType.Video -> Icons.Outlined.VideoLibrary
    NotificationType.System -> Icons.Outlined.Info
}

@Composable
private fun NotificationType.label(): String = stringResource(
    when (this) {
        NotificationType.Payment -> Res.string.notif_type_payment
        NotificationType.Session -> Res.string.notif_type_session
        NotificationType.Message -> Res.string.notif_type_message
        NotificationType.Video -> Res.string.notif_type_video
        NotificationType.System -> Res.string.notif_type_system
    },
)

@Composable
private fun TypeBubble(type: NotificationType, unread: Boolean, size: Int = 40) {
    IconCircle(
        type.icon(),
        container = if (unread) SharedColors.softAccent else MaterialTheme.colorScheme.surfaceVariant,
        tint = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        size = size,
    )
}

// ---------------------------------------------------------------- List

@Composable
fun TrainerNotificationListRoute(
    repository: TrainerNotificationsRepository,
    onBack: () -> Unit,
    onOpenNotification: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { TrainerNotificationListViewModel(repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The view model outlives the screen, so pick up read state on every entry.
    LaunchedEffect(viewModel) { viewModel.onAction(NotificationListAction.Reload) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is NotificationListEvent.OpenNotification -> onOpenNotification(event.id)
            }
        }
    }

    TrainerNotificationListScreen(state, viewModel::onAction, onBack, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerNotificationListScreen(
    state: NotificationListUiState,
    onAction: (NotificationListAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(
            title = stringResource(Res.string.notifications_title),
            onBack = onBack,
            actions = {
                if (snapshot != null && snapshot.unreadCount > 0) {
                    TextButton(
                        onClick = { onAction(NotificationListAction.MarkAllRead) },
                        modifier = Modifier.heightIn(min = Layout.target),
                    ) {
                        Text(stringResource(Res.string.notifications_mark_all), maxLines = 2)
                    }
                }
            },
        )
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onAction(NotificationListAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Layout.gutter),
                verticalArrangement = Arrangement.spacedBy(Layout.small),
            ) {
                when {
                    state.status == NotificationsLoadStatus.Loading -> item { ScreenLoading(stringResource(Res.string.screen_loading)) }
                    state.status == NotificationsLoadStatus.Error -> item {
                        ErrorState(
                            title = stringResource(Res.string.error_load_notifications),
                            retryLabel = stringResource(Res.string.action_retry),
                            onRetry = { onAction(NotificationListAction.Refresh) },
                        )
                    }
                    snapshot != null -> {
                        if (snapshot.isOffline) {
                            item { InlineBanner(stringResource(Res.string.offline_banner, formatUpdatedAt(snapshot.lastUpdated))) }
                        }
                        if (snapshot.items.isEmpty()) {
                            item {
                                EmptyState(
                                    title = stringResource(Res.string.notifications_empty_title),
                                    body = stringResource(Res.string.notifications_empty_body),
                                    icon = Icons.Outlined.Notifications,
                                )
                            }
                        } else {
                            items(snapshot.items, key = { it.id }) { notification ->
                                NotificationRow(notification, snapshot.generatedAt) { onAction(NotificationListAction.Open(notification)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: TrainerNotification, now: Instant, onClick: () -> Unit) {
    val unread = !notification.read
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(Layout.tile)
            .frosted(Layout.tile)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Layout.padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Layout.medium),
    ) {
        TypeBubble(notification.type, unread)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                notification.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                notification.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
            Text(
                formatListWhen(notification.at, notification.zone, now),
                style = MaterialTheme.typography.bodySmall,
                color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            if (unread) {
                Box(modifier = Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    }
}

// ---------------------------------------------------------------- Detail

@Composable
fun TrainerNotificationDetailRoute(
    id: String,
    repository: TrainerNotificationsRepository,
    onBack: () -> Unit,
    onOpenTarget: (NotificationTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(key = "notification-$id") { TrainerNotificationDetailViewModel(id, repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrainerNotificationDetailScreen(
        state = state,
        onAction = { action ->
            when (action) {
                NotificationDetailAction.Retry -> viewModel.retry()
                is NotificationDetailAction.OpenTarget -> onOpenTarget(action.target)
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun TrainerNotificationDetailScreen(
    state: NotificationDetailUiState,
    onAction: (NotificationDetailAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(Res.string.notification_title), onBack = onBack)
        val detail = state.detail
        when {
            state.status == NotificationsLoadStatus.Loading -> ScreenLoading(stringResource(Res.string.screen_loading))
            state.status == NotificationsLoadStatus.Error -> ErrorState(
                title = stringResource(Res.string.error_load_notification),
                retryLabel = stringResource(Res.string.action_retry),
                onRetry = { onAction(NotificationDetailAction.Retry) },
                modifier = Gutter,
            )
            detail != null -> DetailContent(detail.notification, detail.generatedAt, onAction)
        }
    }
}

@Composable
private fun DetailContent(
    notification: TrainerNotification,
    now: Instant,
    onAction: (NotificationDetailAction) -> Unit,
) {
    val actionLabel = when (val target = notification.target) {
        is NotificationTarget.Chat -> stringResource(Res.string.notification_action_chat, target.clientName)
        NotificationTarget.Finance -> stringResource(Res.string.notification_action_finance)
        is NotificationTarget.Session -> stringResource(Res.string.notification_action_session)
        NotificationTarget.None -> null
    }
    Column(
        modifier = Modifier.padding(Layout.gutter),
        verticalArrangement = Arrangement.spacedBy(Layout.padding),
    ) {
        OutlinedTile(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
                TypeBubble(notification.type, unread = true, size = 48)
                Column {
                    Text(
                        notification.type.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        formatPaymentWhen(notification.at, notification.zone, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                notification.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                notification.detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (actionLabel != null) {
            PrimaryButton(
                text = actionLabel,
                onClick = { onAction(NotificationDetailAction.OpenTarget(notification.target)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
