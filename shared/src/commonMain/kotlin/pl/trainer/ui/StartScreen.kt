package pl.trainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*
import pl.trainerapp.core.format.formatPln

/*
 * Start (docs/plans/trainer-redesign.md): the next session as the hero card, then three widgets that open their own
 * lists: requests to answer, this week's payments and the session history with attendance to mark.
 */

@Composable
fun StartScreen(
    state: TrainerUiState,
    onSession: (String) -> Unit,
    onRequests: () -> Unit,
    onPayments: () -> Unit,
    onHistory: () -> Unit,
    onNewSession: () -> Unit,
) {
    val data = state.data ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Layout.gutter, vertical = Layout.padding).testTag("start"),
        verticalArrangement = Arrangement.spacedBy(Layout.medium),
    ) {
        NextSessionCard(state, data, onSession, onNewSession)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            RequestsWidget(state.openRequests.size, onRequests, Modifier.weight(1f).fillMaxHeight())
            PaymentsWidget(state.payments, onPayments, Modifier.weight(1f).fillMaxHeight())
        }
        HistoryWidget(state, data, onHistory)
        Spacer(Modifier.height(Layout.padding))
    }
}

@Composable
private fun NextSessionCard(state: TrainerUiState, data: TrainerDashboard, onSession: (String) -> Unit, onNewSession: () -> Unit) {
    val next = state.nextSession
    val language = DateFormats.defaultLanguage()
    val now = state.now
    if (next == null) {
        HeroCard(onClick = onNewSession) {
            HeroPill(Icons.Outlined.EventAvailable, tr(Res.string.start_next_session))
            Spacer(Modifier.height(Layout.medium))
            Text(tr(Res.string.start_no_sessions), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(tr(Res.string.start_no_sessions_body), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f))
            Spacer(Modifier.height(Layout.medium))
            HeroButton(tr(Res.string.new_session), onNewSession)
        }
        return
    }
    val client = data.clients.find { it.id == next.clientId }
    val date = next.start.toLocalDateTime(TrainerZone).date
    val dayLabel = when (date) {
        state.today -> tr(Res.string.today)
        else -> DateFormats.date(date, language, "EEEE, d MMMM").replaceFirstChar { it.titlecase() }
    }
    val inProgress = now != null && next.start <= now
    val pill = when {
        inProgress -> tr(Res.string.start_in_progress)
        now != null && date == state.today -> tr(Res.string.start_next_in, minutesUntil(now, next.start))
        else -> tr(Res.string.start_next_session)
    }
    HeroCard(onClick = { onSession(next.id) }, modifier = Modifier.semantics {
        contentDescription = listOfNotNull(pill, dayLabel, timeRange(next.start, next.end, next.timeZone), client?.name, next.location).joinToString(", ")
    }) {
        HeroPill(Icons.Outlined.Schedule, pill)
        Spacer(Modifier.height(Layout.medium))
        Text(dayLabel, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
            Text(DateFormats.time(next.start, next.timeZone, language), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
            Text("– " + DateFormats.time(next.end, next.timeZone, language), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(bottom = Layout.small))
        }
        Spacer(Modifier.height(Layout.medium))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            Portrait(client?.initials ?: "?", client?.userId, size = 44.dp, ring = true)
            Column(Modifier.weight(1f)) {
                Text(client?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (next.location.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                    Icon(Icons.Outlined.LocationOn, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.85f))
                    Text(next.location, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                }
            }
            Box(Modifier.size(40.dp).background(Color.White, Layout.pill), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = AppColors.brandStrong)
            }
        }
    }
}

private fun minutesUntil(now: Instant, start: Instant): Int = ((start - now).inWholeMinutes).toInt().coerceAtLeast(1)

@Composable
private fun HeroPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.background(Color.White.copy(alpha = 0.18f), Layout.pill).padding(horizontal = Layout.medium, vertical = Layout.tiny),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.tiny),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = Color.White)
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun HeroButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, shape = Layout.pill, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AppColors.brandStrong),
        modifier = Modifier.heightIn(min = Layout.target)) { Text(label) }
}

@Composable
private fun RequestsWidget(count: Int, onClick: () -> Unit, modifier: Modifier) {
    val label = tr(Res.string.start_requests)
    val supporting = if (count == 0) tr(Res.string.start_requests_none) else tr(Res.string.start_requests_count, count)
    WhiteCard(modifier.semantics(mergeDescendants = true) {}, onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.NotificationsActive, if (count > 0) TileTone.Attention else TileTone.Neutral)
            Spacer(Modifier.weight(1f))
            if (count > 0) CountBadge(count)
        }
        Spacer(Modifier.height(Layout.medium))
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PaymentsWidget(payments: WeekPaymentsState, onClick: () -> Unit, modifier: Modifier) {
    WhiteCard(modifier.semantics(mergeDescendants = true) {}, onClick = onClick) {
        IconTile(Icons.Outlined.AccountBalanceWallet, TileTone.Money)
        Spacer(Modifier.height(Layout.medium))
        when {
            payments.summary != null -> Text(formatPln(payments.summary.revenue), style = MaterialTheme.typography.titleLarge)
            payments.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Layout.small))
            else -> Text("—", style = MaterialTheme.typography.titleLarge)
        }
        Text(tr(if (payments.failed) Res.string.start_payments_failed else Res.string.start_payments), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryWidget(state: TrainerUiState, data: TrainerDashboard, onClick: () -> Unit) {
    val unmarked = state.unmarkedSessions
    WhiteCard(Modifier.semantics(mergeDescendants = true) {}, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            IconTile(Icons.Outlined.History, TileTone.Brand)
            Column(Modifier.weight(1f)) {
                Text(tr(Res.string.start_history), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (unmarked.isEmpty()) tr(Res.string.start_history_all_marked) else tr(Res.string.start_history_to_mark, unmarked.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unmarked.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else AppColors.warningText,
                )
            }
            // The clients of the sessions still to mark, overlapping.
            Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                unmarked.map { it.clientId }.distinct().take(3).forEach { id ->
                    val client = data.clients.find { it.id == id }
                    Portrait(client?.initials ?: "?", client?.userId, size = 30.dp, ring = true)
                }
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- the widgets' lists

/** "Wymaga reakcji": every open booking and reschedule request with the change it asks for. */
@Composable
fun RequestsScreen(state: TrainerUiState, onAccept: (Session) -> Unit, onReject: (Session) -> Unit, onOpen: (String) -> Unit) {
    val data = state.data ?: return
    val requests = state.openRequests
    LazyColumn(contentPadding = PaddingValues(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.medium), modifier = Modifier.testTag("requests")) {
        if (requests.isEmpty()) item {
            EmptyContent(Icons.Outlined.TaskAlt, tr(Res.string.requests_empty), tr(Res.string.requests_empty_body))
        }
        items(requests, key = { it.id }) { session ->
            RequestCard(session, data.client(session.clientId), state.submitting, onAccept = { onAccept(session) }, onReject = { onReject(session) },
                onOpen = { onOpen(session.id) })
        }
    }
}

/** One request: who, what changes ("wt 14:00 → czw 16:00" for a reschedule) and Accept / Reject. */
@Composable
fun RequestCard(session: Session, client: Client, submitting: Boolean, onAccept: () -> Unit, onReject: () -> Unit, onOpen: () -> Unit) {
    val language = DateFormats.defaultLanguage()
    fun moment(at: Instant) = DateFormats.date(at.toLocalDateTime(TrainerZone).date, language, "EEE d MMM") + " · " + DateFormats.time(at, session.timeZone, language)
    val reschedule = session.proposal != null
    WhiteCard(color = AppColors.warningSurface.copy(alpha = 0.55f), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            Portrait(client.initials, client.userId, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(client.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                    Icon(if (reschedule) Icons.Outlined.Update else Icons.Outlined.HourglassEmpty, null, Modifier.size(16.dp), tint = AppColors.warningText)
                    Text(tr(if (reschedule) Res.string.request_kind_reschedule else Res.string.request_kind_booking),
                        style = MaterialTheme.typography.labelMedium, color = AppColors.warningText)
                }
            }
        }
        Spacer(Modifier.height(Layout.medium))
        if (reschedule) {
            Text(moment(session.start) + "  →  " + moment(session.proposal!!.start), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
        } else {
            Text(moment(session.start) + "–" + DateFormats.time(session.end, session.timeZone, language), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
        }
        if (session.location.isNotBlank()) MetaLine(Icons.Outlined.LocationOn, session.location)
        Spacer(Modifier.height(Layout.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
            Button(onClick = onAccept, enabled = !submitting, shape = Layout.pill, modifier = Modifier.weight(1f).heightIn(min = Layout.target)) {
                Text(tr(Res.string.accept))
            }
            OutlinedButton(onClick = onReject, enabled = !submitting, shape = Layout.pill, modifier = Modifier.weight(1f).heightIn(min = Layout.target),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = AppColors.surface)) {
                Text(tr(Res.string.reject))
            }
        }
    }
}

/** "Historia sesji": past sessions by date; unmarked ones get ✓ Odbyła się / ✗ Nieobecność. */
@Composable
fun HistoryScreen(state: TrainerUiState, onAttended: (Session) -> Unit, onNoShow: (Session) -> Unit, onOpen: (String) -> Unit,
    onCorrect: (Session, Boolean) -> Unit = { _, _ -> }) {
    val data = state.data ?: return
    val language = DateFormats.defaultLanguage()
    val byDate = state.pastSessions.groupBy { it.start.toLocalDateTime(TrainerZone).date }
    LazyColumn(contentPadding = PaddingValues(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.small), modifier = Modifier.testTag("history")) {
        if (byDate.isEmpty()) item {
            EmptyContent(Icons.Outlined.History, tr(Res.string.history_screen_empty), tr(Res.string.history_screen_empty_body))
        }
        byDate.forEach { (date, sessions) ->
            item("d-$date") {
                Text(DateFormats.date(date, language, "EEEE, d MMMM").replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Layout.medium, bottom = Layout.tiny).semantics { heading() })
            }
            items(sessions, key = { it.id }) { session ->
                HistoryRow(session, data.client(session.clientId), state.submitting, { onAttended(session) }, { onNoShow(session) }, { onOpen(session.id) },
                    onCorrect = { attended -> onCorrect(session, attended) })
            }
        }
    }
}

@Composable
private fun HistoryRow(session: Session, client: Client, submitting: Boolean, onAttended: () -> Unit, onNoShow: () -> Unit, onOpen: () -> Unit,
    onCorrect: (attended: Boolean) -> Unit) {
    var correcting by remember(session.id) { mutableStateOf(false) }
    WhiteCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            Portrait(client.initials, client.userId, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(client.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(timeRange(session.start, session.end, session.timeZone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (session.status) {
                SessionStatus.Completed -> AttendanceChip(true, onClick = { correcting = true }.takeUnless { submitting })
                SessionStatus.NoShow -> AttendanceChip(false, onClick = { correcting = true }.takeUnless { submitting })
                else -> Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
                    FilledTonalIconButton(onClick = onAttended, enabled = !submitting,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = AppColors.successSurface, contentColor = AppColors.success)) {
                        Icon(Icons.Outlined.Check, tr(Res.string.attendance_present_for, client.name))
                    }
                    FilledTonalIconButton(onClick = onNoShow, enabled = !submitting,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = AppColors.errorSurface, contentColor = AppColors.errorText)) {
                        Icon(Icons.Outlined.Close, tr(Res.string.attendance_no_show_for, client.name))
                    }
                }
            }
        }
    }
    if (correcting) AttendanceCorrection(session, client, submitting, onDismiss = { correcting = false }, onCorrect = onCorrect)
}

/** Asks before turning a marked session into the other outcome. */
@Composable
private fun AttendanceCorrection(session: Session, client: Client, submitting: Boolean, onDismiss: () -> Unit, onCorrect: (Boolean) -> Unit) {
    val toAttended = session.status == SessionStatus.NoShow
    ConfirmDialog(
        title = tr(Res.string.attendance_change),
        body = tr(if (toAttended) Res.string.attendance_change_to_present else Res.string.attendance_change_to_no_show),
        confirm = tr(if (toAttended) Res.string.attendance_present else Res.string.attendance_no_show),
        context = client.name + "\n" + timeRange(session.start, session.end, session.timeZone),
        submitting = submitting, onDismiss = onDismiss, onConfirm = { onDismiss(); onCorrect(toAttended) },
    )
}

@Composable
private fun AttendanceChip(attended: Boolean, onClick: (() -> Unit)? = null) {
    val (bg, fg) = if (attended) AppColors.successSurface to AppColors.success else AppColors.errorSurface to AppColors.errorText
    Row(Modifier.clip(Layout.pill).background(bg, Layout.pill)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = tr(Res.string.attendance_change), onClick = onClick) else Modifier)
        .heightIn(min = 32.dp).padding(horizontal = Layout.small, vertical = Layout.tiny),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.tiny)) {
        Icon(if (attended) Icons.Outlined.Check else Icons.Outlined.Close, null, Modifier.size(16.dp), tint = fg)
        Text(tr(if (attended) Res.string.attendance_present else Res.string.attendance_no_show), style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** "Płatności w tym tygodniu": the sum and one row per payment (date, client, amount). Card payments only. */
@Composable
fun PaymentsScreen(state: TrainerUiState, onRetry: () -> Unit) {
    val payments = state.payments
    val language = DateFormats.defaultLanguage()
    LazyColumn(contentPadding = PaddingValues(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.small), modifier = Modifier.testTag("payments")) {
        item {
            HeroCard {
                Text(tr(Res.string.start_payments), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f))
                Text(payments.summary?.let { formatPln(it.revenue) } ?: "—", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
            }
            Spacer(Modifier.height(Layout.small))
        }
        when {
            payments.loading && payments.summary == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            payments.failed && payments.summary == null -> item {
                EmptyContent(Icons.Outlined.CloudOff, tr(Res.string.load_error_title), tr(Res.string.start_payments_failed), tr(Res.string.retry), onRetry)
            }
            payments.summary?.payments.isNullOrEmpty() -> item {
                EmptyContent(Icons.Outlined.AccountBalanceWallet, tr(Res.string.payments_empty), tr(Res.string.payments_empty_body))
            }
            else -> items(payments.summary!!.payments.sortedByDescending { it.at }, key = { it.id }) { payment ->
                WhiteCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
                        IconTile(Icons.Outlined.CreditCard, TileTone.Money, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(payment.clientName, style = MaterialTheme.typography.titleMedium)
                            Text(DateFormats.date(payment.at.toLocalDateTime(payment.zone).date, language, "EEE d MMM") + " · " +
                                DateFormats.time(payment.at, payment.zone.id, language), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatPln(payment.amount), style = MaterialTheme.typography.titleMedium, color = AppColors.success)
                    }
                }
            }
        }
    }
}
