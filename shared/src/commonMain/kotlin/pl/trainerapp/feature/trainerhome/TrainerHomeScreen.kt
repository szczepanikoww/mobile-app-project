package pl.trainerapp.feature.trainerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import pl.trainer.resources.*
import pl.trainer.ui.AppColors
import pl.trainer.ui.EmptyContent
import pl.trainer.ui.InfoBanner
import pl.trainer.ui.Layout
import pl.trainer.ui.MetaLine
import pl.trainerapp.core.designsystem.IconCircle
import pl.trainerapp.core.designsystem.OutlinedTile
import pl.trainerapp.core.designsystem.ScreenError
import pl.trainerapp.core.designsystem.ScreenLoading
import pl.trainerapp.core.designsystem.ScreenSectionTitle
import pl.trainerapp.core.designsystem.ScreenTopBar
import pl.trainerapp.core.format.formatSessionRange
import pl.trainerapp.core.format.formatStartsIn
import pl.trainerapp.core.format.formatUpdatedAt
import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.SessionStatus
import pl.trainerapp.feature.trainerhome.model.SessionSummary
import pl.trainerapp.feature.trainerhome.model.TrainerHomeSummary
import pl.trainerapp.feature.trainerhome.model.WeekStats
import kotlin.time.Instant

private val Gutter = Modifier.padding(horizontal = Layout.gutter)

/** Cards in the today carousel are narrower than the screen so the next card peeks in. */
private val SessionCardWidth = 296.dp

/** Navigation- and DI-aware entry point. Everything below it only renders state and emits actions. */
@Composable
fun TrainerHomeRoute(
    repository: TrainerHomeRepository,
    onNavigate: (TrainerHomeDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { TrainerHomeViewModel(repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TrainerHomeEvent.Navigate -> onNavigate(event.destination)
            }
        }
    }

    TrainerHomeScreen(state = state, onAction = viewModel::onAction, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerHomeScreen(
    state: TrainerHomeUiState,
    onAction: (TrainerHomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HomeTopBar(state.summary, onAction)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onAction(TrainerHomeAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Horizontal gutters are applied per item so the session carousel can scroll edge to edge.
                contentPadding = PaddingValues(vertical = Layout.padding),
                verticalArrangement = Arrangement.spacedBy(Layout.medium),
            ) {
                when {
                    state.status == LoadStatus.Loading -> item { ScreenLoading(stringResource(Res.string.screen_loading), Gutter) }
                    state.status == LoadStatus.Error -> item {
                        Box(Gutter) { ScreenError(
                            title = stringResource(Res.string.error_load_home),
                            body = stringResource(Res.string.screen_error_body),
                            retryLabel = stringResource(Res.string.action_retry),
                            onRetry = { onAction(TrainerHomeAction.Refresh) },
                        ) }
                    }
                    state.summary != null -> content(state, state.summary, onAction)
                }
            }
        }
    }
}

private fun LazyListScope.content(
    state: TrainerHomeUiState,
    summary: TrainerHomeSummary,
    onAction: (TrainerHomeAction) -> Unit,
) {
    val updated = state.lastUpdated
    if (state.isOffline && updated != null) {
        item { InfoBanner(stringResource(Res.string.offline_banner, formatUpdatedAt(updated)), Icons.Outlined.CloudOff, modifier = Gutter) }
    }

    if (summary.isNewTrainer) {
        item {
            Box(Gutter) {
                EmptyContent(
                    Icons.Outlined.EventAvailable,
                    stringResource(Res.string.empty_new_title),
                    stringResource(Res.string.empty_new_body),
                    stringResource(Res.string.empty_new_action),
                ) { onAction(TrainerHomeAction.EmptyStateAction) }
            }
        }
        return
    }

    val sessions = summary.carouselSessions
    item {
        ScreenSectionTitle(stringResource(Res.string.home_today_sessions), Gutter) {
            if (sessions.size > 1) {
                Text(
                    stringResource(Res.string.home_swipe),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (sessions.isEmpty()) {
        item { Box(Gutter) { EmptyDay(summary, onAction) } }
    } else {
        item(key = "sessions") {
            // Swipe sideways through today's sessions; the next session comes first.
            LazyRow(
                contentPadding = PaddingValues(horizontal = Layout.gutter),
                horizontalArrangement = Arrangement.spacedBy(Layout.medium),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(session, summary.generatedAt, state.isOffline, onAction, Modifier.width(SessionCardWidth))
                }
            }
        }
    }

    if (summary.attention.isNotEmpty()) {
        item { ScreenSectionTitle(stringResource(Res.string.home_needs_attention), Gutter.padding(top = Layout.small)) }
        items(summary.attention, key = { "attention-${it.id}" }) { item ->
            Box(Gutter) { AttentionRow(item) { onAction(TrainerHomeAction.OpenAttention(item)) } }
        }
    }

    summary.week?.let { week ->
        item { Box(Gutter) { WeekSection(week, onAction) } }
    }
}

@Composable
private fun HomeTopBar(summary: TrainerHomeSummary?, onAction: (TrainerHomeAction) -> Unit) {
    ScreenTopBar(
        actions = {
            val unread = summary?.unreadChats ?: 0
            IconButton(onClick = { onAction(TrainerHomeAction.OpenChats) }) {
                BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = if (unread > 0) {
                            stringResource(Res.string.cd_unread_chats, unread)
                        } else {
                            stringResource(Res.string.action_chat)
                        },
                    )
                }
            }
            IconButton(onClick = { onAction(TrainerHomeAction.OpenNotifications) }) {
                BadgedBox(badge = { if (summary?.hasNotifications == true) Badge() }) {
                    Icon(Icons.Outlined.Notifications, contentDescription = stringResource(Res.string.action_notifications))
                }
            }
            IconButton(onClick = { onAction(TrainerHomeAction.OpenProfile) }) {
                Icon(Icons.Outlined.AccountCircle, contentDescription = stringResource(Res.string.action_profile))
            }
        },
    ) {
        Column {
            Text(
                stringResource(Res.string.home_greeting_hey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                summary?.trainerName ?: stringResource(Res.string.shell_nav_start),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyDay(summary: TrainerHomeSummary, onAction: (TrainerHomeAction) -> Unit) {
    val upcoming = summary.upcomingAfterToday
    EmptyContent(
        Icons.Outlined.CalendarToday,
        stringResource(Res.string.empty_day_title),
        if (upcoming != null) {
            stringResource(
                Res.string.empty_day_next,
                formatSessionRange(upcoming.start, upcoming.end, upcoming.zone, summary.generatedAt),
            )
        } else {
            stringResource(Res.string.empty_day_body)
        },
        stringResource(Res.string.home_see_calendar),
    ) { onAction(TrainerHomeAction.OpenCalendar) }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    now: Instant,
    offline: Boolean,
    onAction: (TrainerHomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requested = session.status == SessionStatus.Requested
    val canCheckIn = session.checkInOpen && !offline
    OutlinedTile(modifier, onClick = { onAction(TrainerHomeAction.OpenSession(session)) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SessionStatusLabel(session.status)
            Spacer(Modifier.weight(1f))
            if (!requested) {
                formatStartsIn(session.start, now)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(session.client.name, style = MaterialTheme.typography.titleMedium)
        MetaLine(Icons.Outlined.Schedule, formatSessionRange(session.start, session.end, session.zone, now))
        MetaLine(Icons.Outlined.LocationOn, session.location)
        session.planTitle?.let { MetaLine(Icons.AutoMirrored.Outlined.EventNote, it) }
        Row(
            Modifier.fillMaxWidth().padding(top = Layout.tiny),
            horizontalArrangement = Arrangement.spacedBy(Layout.small),
        ) {
            if (requested) {
                Button(
                    onClick = { onAction(TrainerHomeAction.OpenSession(session)) },
                    shape = Layout.pill,
                    modifier = Modifier.weight(1f).heightIn(min = Layout.target),
                ) { ButtonContent(Icons.Outlined.Check, stringResource(Res.string.action_confirm)) }
            } else {
                Button(
                    onClick = { onAction(TrainerHomeAction.CheckIn(session)) },
                    enabled = canCheckIn,
                    shape = Layout.pill,
                    modifier = Modifier.weight(1f).heightIn(min = Layout.target),
                ) { ButtonContent(Icons.Outlined.HowToReg, stringResource(Res.string.action_check_in)) }
            }
            OutlinedButton(
                onClick = { onAction(TrainerHomeAction.Message(session)) },
                shape = Layout.pill,
                modifier = Modifier.weight(1f).heightIn(min = Layout.target),
            ) { ButtonContent(Icons.Outlined.ChatBubbleOutline, stringResource(Res.string.action_message)) }
        }
        if (offline && session.checkInOpen && !requested) {
            Text(
                stringResource(Res.string.check_in_needs_connection),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ButtonContent(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(Layout.small))
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Status pill with icon and label, matching the calendar's status labels. */
@Composable
private fun SessionStatusLabel(status: SessionStatus) {
    val (label, colors) = when (status) {
        SessionStatus.Confirmed -> Res.string.status_confirmed to Triple(AppColors.successSurface, AppColors.success, Icons.Outlined.EventAvailable)
        SessionStatus.Completed -> Res.string.status_completed to Triple(AppColors.completedSurface, AppColors.completedText, Icons.Outlined.DoneAll)
        SessionStatus.Requested -> Res.string.status_requested to Triple(AppColors.warningSurface, AppColors.warningText, Icons.Outlined.Schedule)
        SessionStatus.Cancelled -> Res.string.status_cancelled to Triple(AppColors.errorSurface, AppColors.errorText, Icons.Outlined.EventBusy)
    }
    val (background, content, icon) = colors
    Surface(color = background, contentColor = content, shape = Layout.pill) {
        Row(
            Modifier.padding(horizontal = Layout.small, vertical = Layout.tiny),
            horizontalArrangement = Arrangement.spacedBy(Layout.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, onClick: () -> Unit) {
    val brand = MaterialTheme.colorScheme.primary
    when (item) {
        is AttentionItem.TechniqueVideo -> AttentionTile(
            icon = Icons.Outlined.PlayCircleOutline,
            container = AppColors.softAccent,
            tint = brand,
            label = stringResource(Res.string.attention_video_label),
            title = item.exercise,
            subtitle = item.client.name,
            onClick = onClick,
        )
        is AttentionItem.UnreadMessages -> AttentionTile(
            icon = Icons.Outlined.ChatBubbleOutline,
            container = AppColors.softAccent,
            tint = brand,
            label = pluralStringResource(Res.plurals.attention_unread_short, item.count, item.count),
            title = item.client.name,
            subtitle = item.topic,
            onClick = onClick,
        )
        is AttentionItem.OverduePayment -> AttentionTile(
            icon = Icons.Outlined.ErrorOutline,
            container = AppColors.errorSurface,
            tint = AppColors.errorText,
            label = stringResource(Res.string.attention_payment_label),
            title = stringResource(Res.string.attention_overdue),
            subtitle = item.client.name,
            onClick = onClick,
        )
    }
}

@Composable
private fun AttentionTile(
    icon: ImageVector,
    container: Color,
    tint: Color,
    label: String,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    OutlinedTile(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.medium), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon, container, tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WeekSection(week: WeekStats, onAction: (TrainerHomeAction) -> Unit) {
    Column(Modifier.padding(top = Layout.small), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
        ScreenSectionTitle(stringResource(Res.string.home_this_week)) {
            TextButton(onClick = { onAction(TrainerHomeAction.OpenCalendar) }, modifier = Modifier.heightIn(min = Layout.target)) {
                Text(stringResource(Res.string.home_details))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
            StatTile(
                Icons.Outlined.FitnessCenter,
                "${week.sessionsDone}/${week.sessionsPlanned}",
                stringResource(Res.string.week_sessions),
                Modifier.weight(1f),
            )
            StatTile(Icons.Outlined.PersonAdd, week.newClients.toString(), stringResource(Res.string.week_new), Modifier.weight(1f))
            StatTile(
                Icons.Outlined.EventBusy,
                week.cancellations.toString(),
                stringResource(Res.string.week_cancelled),
                Modifier.weight(1f),
                muted = true,
            )
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    OutlinedTile(modifier) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}
