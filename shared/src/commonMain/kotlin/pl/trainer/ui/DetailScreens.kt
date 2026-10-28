package pl.trainer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*

private enum class Confirmation { Cancel, Reject, Complete, Withdraw }

@Composable
fun SessionDetailScreen(state: TrainerUiState, session: Session, onClient: () -> Unit, onEdit: () -> Unit,
    onAccept: () -> Unit, onReject: () -> Unit, onCancel: () -> Unit, onComplete: () -> Unit) {
    val client = state.data!!.client(session.clientId)
    var confirmation by remember(session.id) { mutableStateOf<Confirmation?>(null) }
    val trainerProposal = session.proposal?.author == ProposalAuthor.Trainer
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.padding), verticalAlignment = Alignment.CenterVertically) {
            Avatar(client, large = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                Text(client.name, style = MaterialTheme.typography.titleLarge)
                StatusLabel(session)
            }
        }
        Surface(shape = Layout.feature, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                if (session.proposal != null) Text(tr(Res.string.original_time), style = MaterialTheme.typography.labelLarge)
                TimeSummary(session.start, session.end, session.timeZone)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MetaLine(Icons.Outlined.LocationOn, session.location)
                Text(tr(Res.string.time_zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (session.status == SessionStatus.Requested) InfoBanner(tr(Res.string.booking_notice), Icons.Outlined.Schedule)
        session.proposal?.let { proposal ->
            Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                SectionTitle(tr(Res.string.proposed_time))
                Surface(shape = Layout.card, color = AppColors.softAccent) {
                    Column(Modifier.padding(Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                        TimeSummary(proposal.start, proposal.end, session.timeZone)
                        if (trainerProposal) Text(tr(Res.string.awaiting_client), style = MaterialTheme.typography.labelLarge, color = AppColors.onSoftAccent)
                    }
                }
                Text(tr(Res.string.original_stays), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ActionRow(Icons.Outlined.PersonOutline, tr(Res.string.view_profile), onClick = onClient)
        Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            if (session.needsAttention() && !trainerProposal) {
                PrimaryButton(tr(Res.string.accept), loading = state.submitting, onClick = onAccept)
                OutlinedButton(onClick = { confirmation = Confirmation.Reject }, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) { Text(tr(Res.string.reject)) }
            }
            if (trainerProposal) OutlinedButton(onClick = { confirmation = Confirmation.Withdraw }, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) { Text(tr(Res.string.withdraw)) }
            if (session.isActive()) {
                if (!session.needsAttention()) PrimaryButton(tr(Res.string.complete_session), loading = state.submitting, onClick = { confirmation = Confirmation.Complete })
                OutlinedButton(onClick = onEdit, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
                    Icon(Icons.Outlined.EditCalendar, null, Modifier.size(20.dp)); Spacer(Modifier.width(Layout.small)); Text(tr(Res.string.propose_time))
                }
                TextButton(onClick = { confirmation = Confirmation.Cancel }, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
                    Text(tr(Res.string.cancel_session), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(Layout.small))
    }
    confirmation?.let { action ->
        ConfirmDialog(
            title = tr(when (action) { Confirmation.Cancel -> Res.string.cancel_title; Confirmation.Complete -> Res.string.complete_title; else -> Res.string.reject_title }),
            body = tr(when (action) { Confirmation.Cancel -> Res.string.cancel_body; Confirmation.Complete -> Res.string.complete_body; else -> Res.string.reject_body }),
            confirm = tr(when (action) { Confirmation.Cancel -> Res.string.confirm_cancel; Confirmation.Complete -> Res.string.complete_session; Confirmation.Withdraw -> Res.string.withdraw; Confirmation.Reject -> Res.string.reject }),
            context = client.name + "\n" + DateFormats.date(session.start.toLocalDateTime(TrainerZone).date, DateFormats.defaultLanguage(), "d MMMM yyyy") + " · " + timeRange(session.start, session.end, session.timeZone),
            submitting = state.submitting,
            onDismiss = { confirmation = null }, onConfirm = {
                confirmation = null
                when (action) { Confirmation.Cancel -> onCancel(); Confirmation.Complete -> onComplete(); else -> onReject() }
            },
        )
    }
}

fun timeRange(start: kotlin.time.Instant, end: kotlin.time.Instant, zone: String = TrainerZone.id) =
    DateFormats.time(start, zone, DateFormats.defaultLanguage()) + " – " + DateFormats.time(end, zone, DateFormats.defaultLanguage())

@Composable
fun TimeSummary(start: kotlin.time.Instant, end: kotlin.time.Instant, zone: String = TrainerZone.id) {
    Text(DateFormats.date(start.toLocalDateTime(kotlinx.datetime.TimeZone.of(zone)).date, DateFormats.defaultLanguage()).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.titleMedium)
    MetaLine(Icons.Outlined.Schedule, timeRange(start, end, zone))
}

@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = Layout.card, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = Layout.target).padding(Layout.padding), horizontalArrangement = Arrangement.spacedBy(Layout.medium), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ClientDetailScreen(state: TrainerUiState, client: Client, onSession: (String) -> Unit, onNewSession: () -> Unit, onNotes: () -> Unit,
    onInformation: () -> Unit, onRetryInformation: () -> Unit, onIntegration: () -> Unit) {
    if (!client.active) {
        Box(Modifier.padding(Layout.gutter)) {
            EmptyContent(Icons.Outlined.Lock, tr(Res.string.access_title), tr(Res.string.access_body))
        }
        return
    }
    val data = state.data!!
    val sessions = data.sessions.filter { it.clientId == client.id }
    val next = sessions.filter { it.isActive() && it.start.toLocalDateTime(TrainerZone).date >= DemoDate }.minByOrNull { it.start }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.padding), verticalAlignment = Alignment.CenterVertically) {
            Avatar(client, large = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                Text(client.name, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (client.hasOverduePayment) MetaLine(Icons.Outlined.Payments, tr(Res.string.overdue_label))
        Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            SectionTitle(tr(Res.string.client_information)) {
                IconButton(onClick = onInformation, enabled = state.clientInformation != null && !state.informationLoading) {
                    Icon(Icons.Outlined.Edit, tr(Res.string.edit_client_information))
                }
            }
            when {
                state.informationLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.informationFailure != null -> {
                    InfoBanner(failureText(state.informationFailure), error = true)
                    TextButton(onClick = onRetryInformation) { Text(tr(Res.string.retry)) }
                }
                state.clientInformation != null && state.informationClientId == client.id -> {
                    val information = state.clientInformation
                    val language = DateFormats.defaultLanguage()
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.section),
                        verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                        InformationValue(tr(Res.string.age), information.ageOn(DemoDate)?.let { tr(Res.string.age_value, it) })
                        InformationValue(tr(Res.string.weight), information.weightKg?.let { tr(Res.string.weight_value, DateFormats.number(it, language)) })
                        InformationValue(tr(Res.string.height), information.heightCm?.let { tr(Res.string.height_value, DateFormats.number(it, language)) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InformationValue(tr(Res.string.training_goal), information.trainingGoal.takeIf { it.isNotBlank() })
                    InformationValue(tr(Res.string.injuries), information.injuries.takeIf { it.isNotBlank() })
                    InformationValue(tr(Res.string.movement_limitations), information.movementLimitations.takeIf { it.isNotBlank() })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            SectionTitle(tr(Res.string.next_session))
            if (next != null) SessionRow(next, client, onClick = { onSession(next.id) }, showDate = true)
            else Text(tr(Res.string.no_upcoming), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (client.active) PrimaryButton(tr(Res.string.new_session), onClick = onNewSession)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            SectionTitle(tr(Res.string.notes)) { IconButton(onClick = onNotes) { Icon(Icons.Outlined.EditNote, tr(Res.string.edit_notes)) } }
            Text(data.notes[client.id]?.takeIf { it.isNotBlank() } ?: tr(Res.string.notes_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            SectionTitle(tr(Res.string.training_and_contact))
            ActionRow(Icons.Outlined.FitnessCenter, tr(Res.string.plans), tr(Res.string.integration_title), onIntegration)
            ActionRow(Icons.Outlined.VideoLibrary, tr(Res.string.chat_video), tr(Res.string.integration_title), onIntegration)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            SectionTitle(tr(Res.string.history))
            val history = sessions.filter { it.status == SessionStatus.Completed }.sortedByDescending { it.start }
            if (history.isEmpty()) Text(tr(Res.string.history_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.forEach { SessionRow(it, client, onClick = { onSession(it.id) }, showDate = true) }
        }
    }
}

@Composable
private fun InformationValue(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: tr(Res.string.not_provided), style = MaterialTheme.typography.bodyLarge,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}
