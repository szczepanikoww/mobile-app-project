package pl.trainer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.datetime.*
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*

@Composable
private fun DateField(date: LocalDate, onDate: (LocalDate) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
        Text(tr(Res.string.date), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target), shape = Layout.card) {
            Icon(Icons.Outlined.CalendarToday, null)
            Spacer(Modifier.width(Layout.medium))
            Text(DateFormats.date(date, DateFormats.defaultLanguage(), "d MMMM yyyy"), modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.KeyboardArrowDown, null)
        }
    }
    if (open) AppDatePicker(date, onDismiss = { open = false }, onDate = { onDate(it); open = false })
}

@Composable
private fun TimeFields(start: String, end: String, enabled: Boolean, invalid: Boolean, onStart: (String) -> Unit, onEnd: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
        OutlinedTextField(start, onStart, label = { Text(tr(Res.string.start_time)) }, enabled = enabled, isError = invalid,
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            shape = Layout.card, modifier = Modifier.weight(1f))
        OutlinedTextField(end, onEnd, label = { Text(tr(Res.string.end_time)) }, enabled = enabled, isError = invalid,
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            shape = Layout.card, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SessionEditorScreen(state: TrainerUiState, route: Route.SessionEditor,
    onSave: (LocalDate, String, String, String?, String, Session?) -> Unit, onInput: () -> Unit) {
    val data = state.data ?: return
    val session = data.sessions.find { it.id == route.sessionId }
    val initial = session?.proposal?.start ?: session?.start
    var dateString by rememberSaveable { mutableStateOf((initial?.toLocalDateTime(TrainerZone)?.date ?: state.selectedDate).toString()) }
    var start by rememberSaveable { mutableStateOf(initial?.let { DateFormats.time(it, TrainerZone.id, DateFormats.defaultLanguage()) } ?: "09:00") }
    var end by rememberSaveable { mutableStateOf((session?.proposal?.end ?: session?.end)?.let { DateFormats.time(it, TrainerZone.id, DateFormats.defaultLanguage()) } ?: "10:00") }
    var clientId by rememberSaveable { mutableStateOf(session?.clientId ?: route.clientId) }
    var location by rememberSaveable { mutableStateOf(session?.location ?: "") }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        Text(tr(if (session == null) Res.string.session_editor_help else Res.string.proposal_editor_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (session == null) Column(verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
            Text(tr(Res.string.client), style = MaterialTheme.typography.bodyMedium)
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target), shape = Layout.card) {
                    Text(data.clients.find { it.id == clientId }?.name ?: tr(Res.string.choose_client), modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.KeyboardArrowDown, null)
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    data.clients.filter { it.active }.forEach { client ->
                        DropdownMenuItem(text = { Text(client.name) }, onClick = { clientId = client.id; expanded = false; onInput() })
                    }
                }
            }
        } else Text(data.client(session.clientId).name, style = MaterialTheme.typography.titleLarge)
        DateField(LocalDate.parse(dateString), { dateString = it.toString(); onInput() }, !state.submitting)
        TimeFields(start, end, !state.submitting, state.actionFailure == Failure.InvalidInput,
            { start = it; onInput() }, { end = it; onInput() })
        Text(tr(Res.string.time_zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (session == null) OutlinedTextField(location, { location = it; onInput() }, label = { Text(tr(Res.string.location)) }, enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(), shape = Layout.card, singleLine = true, isError = state.actionFailure == Failure.InvalidInput && location.isBlank())
        PrimaryButton(tr(if (session == null) Res.string.save else Res.string.save_proposal), loading = state.submitting,
            enabled = session != null || (clientId != null && location.isNotBlank()),
            onClick = { onSave(LocalDate.parse(dateString), start, end, clientId, location, session) })
        Spacer(Modifier.height(Layout.section))
    }
}

@Composable
fun AvailabilityScreen(state: TrainerUiState, onSave: (LocalDate, String, String) -> Unit, onRemove: (String) -> Unit, onInput: () -> Unit) {
    var dateString by rememberSaveable { mutableStateOf(state.selectedDate.toString()) }
    var start by rememberSaveable { mutableStateOf("12:00") }
    var end by rememberSaveable { mutableStateOf("13:00") }
    var removing by remember { mutableStateOf<AvailabilityBlock?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        Text(tr(Res.string.availability_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DateField(LocalDate.parse(dateString), { dateString = it.toString(); onInput() }, !state.submitting)
        TimeFields(start, end, !state.submitting, state.actionFailure == Failure.InvalidInput,
            { start = it; onInput() }, { end = it; onInput() })
        PrimaryButton(tr(Res.string.add_block), loading = state.submitting, onClick = { onSave(LocalDate.parse(dateString), start, end) })
        SectionTitle(tr(Res.string.your_blocks))
        val blocks = state.data?.blocks.orEmpty().sortedBy { it.start }
        if (blocks.isEmpty()) Text(tr(Res.string.no_blocks), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        blocks.forEach { block ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.small), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.small)) { TimeSummary(block.start, block.end) }
                IconButton(onClick = { removing = block }, enabled = !state.submitting) { Icon(Icons.Outlined.DeleteOutline, tr(Res.string.remove_block)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text(tr(Res.string.availability_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    removing?.let { block -> ConfirmDialog(tr(Res.string.remove_block_title), tr(Res.string.remove_block_body), tr(Res.string.remove_block), state.submitting,
        onDismiss = { removing = null }, onConfirm = { removing = null; onRemove(block.id) },
        context = DateFormats.date(block.start.toLocalDateTime(TrainerZone).date, DateFormats.defaultLanguage()) + "\n" + timeRange(block.start, block.end)) }
}

@Composable
fun NoteEditorScreen(state: TrainerUiState, clientId: String, onSave: (String) -> Unit) {
    var note by rememberSaveable(clientId) { mutableStateOf(state.data?.notes?.get(clientId).orEmpty()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        Text(state.data!!.client(clientId).name, style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(note, { note = it }, label = { Text(tr(Res.string.note_label)) }, enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target * 4), minLines = 6, shape = Layout.card)
        Text(tr(Res.string.note_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(tr(Res.string.save), loading = state.submitting, onClick = { onSave(note) })
    }
}
