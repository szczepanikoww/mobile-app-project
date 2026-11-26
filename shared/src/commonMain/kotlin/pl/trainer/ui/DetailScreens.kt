package pl.trainer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*

private enum class Confirmation { Cancel, Reject, Withdraw, Attended, NoShow, CorrectToPresent, CorrectToNoShow }

/**
 * A session's details: when and where, the plan for this session (assigned from a template and written out for this
 * client), the trainer's private comment and the actions the session allows. After it ended, attendance is marked here
 * too ("Odbyła się" / "Nieobecność").
 */
@Composable
fun SessionDetailScreen(state: TrainerUiState, session: Session, onClient: () -> Unit, onEdit: () -> Unit,
    onAccept: () -> Unit, onReject: () -> Unit, onCancel: () -> Unit, onComplete: () -> Unit,
    onNoShow: () -> Unit = {}, onCorrectAttendance: (Boolean) -> Unit = {}, onSaveComment: (String) -> Unit = {},
    planSection: (@Composable (PlanTarget) -> Unit)? = null) {
    val data = state.data!!
    val client = data.client(session.clientId)
    var confirmation by remember(session.id) { mutableStateOf<Confirmation?>(null) }
    val trainerProposal = session.proposal?.author == ProposalAuthor.Trainer
    val ended = state.now?.let { session.end <= it } ?: false
    val language = DateFormats.defaultLanguage()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        HeroCard(onClick = onClient) {
            Row(horizontalArrangement = Arrangement.spacedBy(Layout.medium), verticalAlignment = Alignment.CenterVertically) {
                Portrait(client.initials, client.userId, size = 56.dp, ring = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                    Text(client.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(tr(Res.string.view_profile), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.88f))
                }
            }
            Spacer(Modifier.height(Layout.padding))
            Text(DateFormats.date(session.start.toLocalDateTime(TrainerZone).date, language).replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f))
            Text(timeRange(session.start, session.end, session.timeZone), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            if (session.location.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Icon(Icons.Outlined.LocationOn, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.88f))
                Text(session.location, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f))
            }
        }
        StatusLabel(session)
        if (session.status == SessionStatus.Requested) InfoBanner(tr(Res.string.booking_notice), Icons.Outlined.Schedule)
        session.proposal?.let { proposal ->
            Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                SectionTitle(tr(Res.string.proposed_time))
                WhiteCard(color = AppColors.softAccent) {
                    TimeSummary(proposal.start, proposal.end, session.timeZone)
                    if (trainerProposal) Text(tr(Res.string.awaiting_client), style = MaterialTheme.typography.labelLarge, color = AppColors.onSoftAccent)
                }
                Text(tr(Res.string.original_stays), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Attendance of a session that has ended.
        if (session.isActive() && ended && !session.needsAttention()) Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            SectionTitle(tr(Res.string.attendance_title))
            Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
                Button(onClick = { confirmation = Confirmation.Attended }, enabled = !state.submitting, shape = Layout.pill,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.success), modifier = Modifier.weight(1f).heightIn(min = Layout.buttonHeight)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(Layout.small)); Text(tr(Res.string.attendance_present))
                }
                OutlinedButton(onClick = { confirmation = Confirmation.NoShow }, enabled = !state.submitting, shape = Layout.pill,
                    modifier = Modifier.weight(1f).heightIn(min = Layout.buttonHeight)) {
                    Icon(Icons.Outlined.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(Layout.small)); Text(tr(Res.string.attendance_no_show))
                }
            }
        }

        if (session.status == SessionStatus.Completed || session.status == SessionStatus.NoShow) {
            val toAttended = session.status == SessionStatus.NoShow
            OutlinedButton(onClick = { confirmation = if (toAttended) Confirmation.CorrectToPresent else Confirmation.CorrectToNoShow },
                enabled = !state.submitting, shape = Layout.pill, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
                Text(tr(if (toAttended) Res.string.attendance_change_button_present else Res.string.attendance_change_button_no_show))
            }
        }

        if (planSection != null && !session.isClosedWithoutTraining()) Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            SectionTitle(tr(Res.string.session_plan_title))
            planSection(PlanTarget(client.id, client.name, session.id,
                client.name + " · " + DateFormats.date(session.start.toLocalDateTime(TrainerZone).date, language, "d MMM"), session.planTitle))
        }

        SessionComment(state, data.sessionComments[session.id].orEmpty(), session.id, onSaveComment)

        Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            if (session.needsAttention() && !trainerProposal) {
                PrimaryButton(tr(Res.string.accept), loading = state.submitting, onClick = onAccept)
                OutlinedButton(onClick = { confirmation = Confirmation.Reject }, enabled = !state.submitting, shape = Layout.pill,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) { Text(tr(Res.string.reject)) }
            }
            if (trainerProposal) OutlinedButton(onClick = { confirmation = Confirmation.Withdraw }, enabled = !state.submitting, shape = Layout.pill,
                modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) { Text(tr(Res.string.withdraw)) }
            if (session.isActive() && !ended) {
                OutlinedButton(onClick = onEdit, enabled = !state.submitting, shape = Layout.pill, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
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
            title = tr(when (action) {
                Confirmation.Cancel -> Res.string.cancel_title
                Confirmation.Attended -> Res.string.attendance_present_title
                Confirmation.NoShow -> Res.string.attendance_no_show_title
                Confirmation.CorrectToPresent, Confirmation.CorrectToNoShow -> Res.string.attendance_change
                else -> Res.string.reject_title
            }),
            body = tr(when (action) {
                Confirmation.Cancel -> Res.string.cancel_body
                Confirmation.Attended -> Res.string.attendance_present_body
                Confirmation.NoShow -> Res.string.attendance_no_show_body
                Confirmation.CorrectToPresent -> Res.string.attendance_change_to_present
                Confirmation.CorrectToNoShow -> Res.string.attendance_change_to_no_show
                else -> Res.string.reject_body
            }),
            confirm = tr(when (action) {
                Confirmation.Cancel -> Res.string.confirm_cancel
                Confirmation.Withdraw -> Res.string.withdraw
                Confirmation.Reject -> Res.string.reject
                Confirmation.Attended -> Res.string.attendance_present
                Confirmation.NoShow, Confirmation.CorrectToNoShow -> Res.string.attendance_no_show
                Confirmation.CorrectToPresent -> Res.string.attendance_present
            }),
            context = client.name + "\n" + DateFormats.date(session.start.toLocalDateTime(TrainerZone).date, language, "d MMMM yyyy") + " · " + timeRange(session.start, session.end, session.timeZone),
            submitting = state.submitting,
            onDismiss = { confirmation = null }, onConfirm = {
                confirmation = null
                when (action) {
                    Confirmation.Cancel -> onCancel()
                    Confirmation.Attended -> onComplete()
                    Confirmation.NoShow -> onNoShow()
                    Confirmation.CorrectToPresent -> onCorrectAttendance(true)
                    Confirmation.CorrectToNoShow -> onCorrectAttendance(false)
                    else -> onReject()
                }
            },
        )
    }
}

/** The trainer's private comment on the session, saved with its own button. */
@Composable
private fun SessionComment(state: TrainerUiState, saved: String, sessionId: String, onSave: (String) -> Unit) {
    var text by remember(sessionId, saved) { mutableStateOf(saved) }
    Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
        SectionTitle(tr(Res.string.session_comment_title))
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), shape = Layout.card,
            label = { Text(tr(Res.string.session_comment_label)) }, supportingText = { Text(tr(Res.string.session_comment_private)) },
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = AppColors.surface, focusedContainerColor = AppColors.surface,
                unfocusedBorderColor = AppColors.outline))
        if (text.trim() != saved.trim()) Button(onClick = { onSave(text) }, enabled = !state.submitting, shape = Layout.pill,
            modifier = Modifier.heightIn(min = Layout.target)) { Text(tr(Res.string.session_comment_save)) }
    }
}

fun timeRange(start: kotlin.time.Instant, end: kotlin.time.Instant, zone: String = TrainerZone.id) =
    DateFormats.time(start, zone, DateFormats.defaultLanguage()) + " – " + DateFormats.time(end, zone, DateFormats.defaultLanguage())

@Composable
fun TimeSummary(start: kotlin.time.Instant, end: kotlin.time.Instant, zone: String = TrainerZone.id) {
    Text(DateFormats.date(start.toLocalDateTime(kotlinx.datetime.TimeZone.of(zone)).date, DateFormats.defaultLanguage()).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.titleMedium)
    MetaLine(Icons.Outlined.Schedule, timeRange(start, end, zone))
}

/** A row of a menu or a sheet: icon, title, optional hint, chevron. */
@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    WhiteCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth().heightIn(min = Layout.target), horizontalArrangement = Arrangement.spacedBy(Layout.medium), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, TileTone.Brand)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The client card: who it is, quick actions (chat, new session), the default plan, private information and notes,
 * then every session of the client, upcoming and past. Archiving is at the bottom and can be undone.
 */
@Composable
fun ClientDetailScreen(state: TrainerUiState, client: Client, onSession: (String) -> Unit, onNewSession: () -> Unit, onNotes: () -> Unit,
    onInformation: () -> Unit, onRetryInformation: () -> Unit, onIntegration: () -> Unit,
    onChat: (() -> Unit)? = null, onArchive: (Boolean) -> Unit = {}, planSection: (@Composable (PlanTarget) -> Unit)? = null) {
    var confirmArchive by remember(client.id) { mutableStateOf(false) }
    if (!client.active) {
        Column(Modifier.fillMaxSize().padding(Layout.gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            Portrait(client.initials, client.userId, size = 72.dp)
            Text(client.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (client.archived) {
                Text(tr(Res.string.archived_client_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                PrimaryButton(tr(Res.string.restore_client), loading = state.submitting) { onArchive(false) }
            } else {
                EmptyContent(Icons.Outlined.Lock, tr(Res.string.access_title), tr(Res.string.access_body))
            }
        }
        return
    }
    val data = state.data!!
    val now = state.now
    val sessions = data.sessions.filter { it.clientId == client.id && !it.isClosedWithoutTraining() }
    val upcoming = sessions.filter { now == null || it.end > now }.sortedBy { it.calendarStart }
    val past = sessions.filter { now != null && it.end <= now }.sortedByDescending { it.start }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            Portrait(client.initials, client.userId, size = 88.dp)
            Text(client.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
                if (onChat != null) QuickAction(Icons.AutoMirrored.Outlined.Chat, tr(Res.string.client_chat), onChat)
                QuickAction(Icons.Outlined.EventAvailable, tr(Res.string.new_session), onNewSession)
            }
        }
        if (planSection != null) Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            SectionTitle(tr(Res.string.default_plan_title))
            planSection(PlanTarget(client.id, client.name))
        }
        Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            SectionTitle(tr(Res.string.client_information)) {
                // The client enters this information when the trainer may only read it (TB1).
                if (state.canEditClientInformation) {
                    IconButton(onClick = onInformation, enabled = state.clientInformation != null && !state.informationLoading) {
                        Icon(Icons.Outlined.Edit, tr(Res.string.edit_client_information))
                    }
                }
            }
            WhiteCard {
                when {
                    state.informationLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.informationFailure != null -> {
                        InfoBanner(failureText(state.informationFailure), error = true)
                        TextButton(onClick = onRetryInformation) { Text(tr(Res.string.retry)) }
                    }
                    state.clientInformation != null && state.informationClientId == client.id -> {
                        val information = state.clientInformation
                        val language = DateFormats.defaultLanguage()
                        Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.section),
                                verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                                InformationValue(tr(Res.string.age), information.ageOn(state.today)?.let { tr(Res.string.age_value, it) })
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
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            SectionTitle(tr(Res.string.notes)) { IconButton(onClick = onNotes) { Icon(Icons.Outlined.EditNote, tr(Res.string.edit_notes)) } }
            WhiteCard(onClick = onNotes) {
                Text(data.notes[client.id]?.takeIf { it.isNotBlank() } ?: tr(Res.string.notes_empty), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            SectionTitle(tr(Res.string.client_sessions))
            if (upcoming.isEmpty() && past.isEmpty()) Text(tr(Res.string.history_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (upcoming.isNotEmpty()) {
                Text(tr(Res.string.client_sessions_upcoming), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() })
                upcoming.forEach { SessionRow(it, client, onClick = { onSession(it.id) }, showDate = true) }
            }
            if (past.isNotEmpty()) {
                Text(tr(Res.string.client_sessions_past), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Layout.small).semantics { heading() })
                past.forEach { SessionRow(it, client, onClick = { onSession(it.id) }, showDate = true, finished = true) }
            }
        }
        TextButton(onClick = { confirmArchive = true }, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
            Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp)); Spacer(Modifier.width(Layout.small)); Text(tr(Res.string.archive_client))
        }
    }
    if (confirmArchive) ConfirmDialog(
        title = tr(Res.string.archive_client_title), body = tr(Res.string.archive_client_body), confirm = tr(Res.string.archive_client),
        context = client.name, submitting = state.submitting, onDismiss = { confirmArchive = false },
        onConfirm = { confirmArchive = false; onArchive(true) },
    )
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = Layout.pill, modifier = Modifier.heightIn(min = Layout.target),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = AppColors.softAccent, contentColor = AppColors.onSoftAccent)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(Layout.small)); Text(label)
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
