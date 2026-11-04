package com.trainingapp.feature.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CardMembership
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.ActionRow
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.CardRow
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.MetaLine
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SecondaryButton
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.client.presentation.ScheduleUiState
import com.trainingapp.feature.client.resources.*
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.ClientSession
import pl.trainer.domain.ClientSessionState
import pl.trainer.ui.DateFormats

data class ClientCalendarActions(
    val onDate: (LocalDate) -> Unit,
    val onShiftWeek: (Int) -> Unit,
    val onToday: () -> Unit,
    val onOpenSession: (String) -> Unit,
    val onRequestSession: () -> Unit,
    val onAnswerProposal: (ClientSession, Boolean) -> Unit,
    val onWithdraw: (ClientSession) -> Unit,
    val onOpenWallet: () -> Unit,
    val onFindTrainer: () -> Unit,
    val onRetry: () -> Unit,
)

/**
 * B2 "Kalendarz": a week strip and the week's agenda (no month view, D5). "Poproś o sesję" is the tab's one primary
 * action. A trainer's proposal is answered inline; a pending request can be withdrawn at no cost.
 */
@Composable
fun ClientCalendarContent(state: ScheduleUiState, actions: ClientCalendarActions) {
    val data = state.schedule
    when {
        state.loading -> LoadingState()
        data == null -> ErrorState(failureMessage(state.loadFailure ?: pl.trainer.domain.Failure.Load), onRetry = actions.onRetry)
        else -> {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val week = state.week
            val byDay = week.associateWith { day -> data.sessions.filter { it.agendaStart.dateIn(it.zone) == day }.sortedBy { it.agendaStart } }
            val daysWithSessions = week.filter { byDay[it].orEmpty().isNotEmpty() }
            // Header items before the agenda: action, offline note, week strip.
            val leadingItems = 2 + if (state.offline) 1 else 0
            LazyColumn(
                Modifier.testTag("client-calendar"),
                state = listState,
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                item(key = "action") {
                    if (data.trainers.isEmpty()) {
                        SecondaryButton(stringResource(Res.string.find_trainer), onClick = actions.onFindTrainer, icon = Icons.Outlined.Search)
                    } else {
                        PrimaryButton(
                            stringResource(Res.string.request_session),
                            onClick = actions.onRequestSession,
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Outlined.CalendarToday,
                        )
                    }
                }
                if (state.offline) item(key = "offline") { InlineMessage(stringResource(Res.string.offline_cached), tone = MessageTone.Offline) }
                item(key = "week") {
                    WeekStrip(state, byDay.filterValues { it.isNotEmpty() }.keys, onDate = { date ->
                        actions.onDate(date)
                        val index = daysWithSessions.indexOf(date)
                        if (index >= 0) {
                            // Day headers and their sessions follow the leading items in order.
                            val position = leadingItems + daysWithSessions.take(index).sumOf { 1 + byDay[it].orEmpty().size }
                            scope.launch { listState.animateScrollToItem(position) }
                        }
                    }, actions)
                }
                if (daysWithSessions.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(Res.string.calendar_empty_title),
                            message = stringResource(Res.string.calendar_empty_body),
                            icon = Icons.Outlined.CalendarToday,
                        )
                    }
                }
                daysWithSessions.forEach { day ->
                    item(key = "day-$day") {
                        Text(
                            dayTitle(day),
                            style = AppTheme.type.itemTitle,
                            color = AppTheme.colors.text,
                            modifier = Modifier.padding(top = Spacing.s).semantics { heading() },
                        )
                    }
                    items(byDay[day].orEmpty(), key = { it.id }) { session ->
                        ClientSessionCard(session, state.submitting, actions)
                    }
                }
                item(key = "packages") {
                    ActionRow(
                        Icons.Outlined.CardMembership,
                        stringResource(Res.string.packages_link),
                        onClick = actions.onOpenWallet,
                        modifier = Modifier.padding(top = Spacing.l),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekStrip(state: ScheduleUiState, marked: Set<LocalDate>, onDate: (LocalDate) -> Unit, actions: ClientCalendarActions) {
    val selected = state.visibleDate ?: return
    val language = language()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                DateFormats.date(selected, language, "LLLL yyyy").replaceFirstChar { it.titlecase() },
                style = AppTheme.type.itemTitle,
                color = AppTheme.colors.text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { actions.onShiftWeek(-1) }) { Icon(Icons.Outlined.ChevronLeft, stringResource(Res.string.previous_week)) }
            AppTextButton(stringResource(Res.string.today), onClick = actions.onToday)
            IconButton(onClick = { actions.onShiftWeek(1) }) { Icon(Icons.Outlined.ChevronRight, stringResource(Res.string.next_week)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            state.week.forEach { date ->
                val chosen = date == selected
                val hasSession = date in marked
                val description = DateFormats.date(date, language, "EEEE, d MMMM") +
                    if (hasSession) ", " + stringResource(Res.string.day_has_sessions) else ""
                Surface(
                    onClick = { onDate(date) },
                    shape = AppShapes.card,
                    color = if (chosen) AppTheme.colors.softAccent else AppTheme.colors.background,
                    modifier = Modifier.weight(1f).heightIn(min = Sizes.minTouchTarget).semantics {
                        this.selected = chosen
                        contentDescription = description
                    },
                ) {
                    Column(
                        Modifier.padding(vertical = Spacing.s),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            DateFormats.date(date, language, "EEEEE").uppercase(),
                            style = AppTheme.type.label,
                            color = if (chosen) AppTheme.colors.onSoftAccent else AppTheme.colors.textSecondary,
                        )
                        Text(
                            date.day.toString(),
                            style = AppTheme.type.itemTitle,
                            color = if (chosen) AppTheme.colors.onSoftAccent else AppTheme.colors.text,
                        )
                        Box(
                            Modifier.size(4.dp).background(
                                if (hasSession) AppTheme.colors.brandStrong else AppTheme.colors.background.copy(alpha = 0f),
                                AppShapes.pill,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** One session in the agenda. Proposals and pending requests carry their answer inline. */
@Composable
internal fun ClientSessionCard(session: ClientSession, submitting: Boolean, actions: ClientCalendarActions) {
    val s = session.session
    CardRow(
        title = hours(session.agendaStart, s.proposal?.end ?: s.end, session.zone),
        supporting = session.trainer.name + " · " + s.location,
        onClick = { actions.onOpenSession(session.id) },
        onClickLabel = stringResource(Res.string.details),
        badges = { SessionStateChip(session.state) },
        meta = {
            s.planTitle?.let { MetaLine(Icons.Outlined.FitnessCenter, stringResource(Res.string.plan_line, it)) }
            if (s.proposal != null) {
                MetaLine(Icons.Outlined.History, stringResource(Res.string.current_time_still_confirmed, shortDateTime(s.start, session.zone)))
            }
            when (session.state) {
                ClientSessionState.ProposalFromTrainer -> Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { actions.onAnswerProposal(session, false) }, enabled = !submitting) {
                        Icon(Icons.Outlined.Close, stringResource(Res.string.decline_proposal), tint = AppTheme.colors.textSecondary)
                    }
                    PrimaryButton(
                        stringResource(Res.string.accept),
                        onClick = { actions.onAnswerProposal(session, true) },
                        enabled = !submitting,
                        modifier = Modifier.weight(1f),
                    )
                }
                ClientSessionState.AwaitingTrainer, ClientSessionState.RescheduleRequested ->
                    AppTextButton(stringResource(Res.string.withdraw_request), onClick = { actions.onWithdraw(session) }, enabled = !submitting)
                else -> Unit
            }
        },
    )
}
