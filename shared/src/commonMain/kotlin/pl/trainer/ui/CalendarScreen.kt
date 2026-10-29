package pl.trainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.datetime.*
import kotlin.time.Instant
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*

val TrainerZone = TimeZone.of("Europe/Warsaw")
fun Session.onDate(date: LocalDate) = calendarStart.toLocalDateTime(TrainerZone).date == date
fun weekDates(date: LocalDate): List<LocalDate> {
    val monday = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return (0..6).map { monday.plus(it, DateTimeUnit.DAY) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(state: TrainerUiState, onDate: (LocalDate) -> Unit, onMode: (CalendarMode) -> Unit,
    onAvailability: () -> Unit, onSession: (String) -> Unit, onNew: () -> Unit) {
    val data = state.data ?: return
    val language = DateFormats.defaultLanguage()
    var datePicker by remember { mutableStateOf(false) }
    val dates = weekDates(state.selectedDate)
    val dayScroll = rememberScrollState()
    val density = LocalDensity.current
    val visibleDays = if (state.calendarMode == CalendarMode.Day) listOf(state.selectedDate) else dates
    val visibleSessions = data.sessions.filter { it.status != SessionStatus.Rejected && it.status != SessionStatus.CancelledByTrainer }

    LazyColumn(modifier = Modifier.testTag("calendar-list"), contentPadding = PaddingValues(start = Layout.gutter, end = Layout.gutter, top = Layout.padding, bottom = Layout.padding),
        verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
        item {
            PillTabs(CalendarMode.entries, state.calendarMode,
                label = { tr(if (it == CalendarMode.Day) Res.string.day else Res.string.week) }, onSelect = onMode)
        }
        item {
            TextButton(onClick = { datePicker = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target), contentPadding = PaddingValues(0.dp)) {
                    Text(DateFormats.date(state.selectedDate, language, "LLLL yyyy").replaceFirstChar { it.titlecase() },
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.KeyboardArrowDown, tr(Res.string.choose_date))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { onDate(state.selectedDate.minus(7, DateTimeUnit.DAY)) }) { Icon(Icons.Outlined.ChevronLeft, tr(Res.string.previous_week)) }
                TextButton(onClick = { onDate(DemoDate) }, modifier = Modifier.heightIn(min = Layout.target)) { Text(tr(Res.string.today)) }
                IconButton(onClick = { onDate(state.selectedDate.plus(7, DateTimeUnit.DAY)) }) { Icon(Icons.Outlined.ChevronRight, tr(Res.string.next_week)) }
            }
            BoxWithConstraints {
                val cellWidth = maxOf(Layout.target * density.fontScale.coerceAtLeast(1f), maxWidth / 7)
                LaunchedEffect(state.selectedDate, cellWidth) {
                    val offset = cellWidth * dates.indexOf(state.selectedDate) - (maxWidth - cellWidth) / 2
                    dayScroll.scrollTo(with(density) { offset.roundToPx() }.coerceAtLeast(0))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(dayScroll)) {
                    dates.forEach { date ->
                        val chosen = date == state.selectedDate
                        val hasSession = visibleSessions.any { it.onDate(date) }
                        Surface(onClick = { onDate(date) }, shape = Layout.card,
                            color = if (chosen) AppColors.softAccent else MaterialTheme.colorScheme.background,
                            modifier = Modifier.width(cellWidth).semantics {
                                selected = chosen
                                contentDescription = DateFormats.date(date, language)
                            }) {
                            Column(Modifier.padding(vertical = Layout.medium), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                                Text(DateFormats.date(date, language, "EEE").replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.bodySmall,
                                    color = if (chosen) AppColors.onSoftAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(date.day.toString(), style = MaterialTheme.typography.titleMedium, color = if (chosen) AppColors.onSoftAccent else MaterialTheme.colorScheme.onSurface)
                                Box(Modifier.size(4.dp).background(if (hasSession) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background, Layout.pill))
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = Layout.small), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                    Text(tr(if (state.calendarMode == CalendarMode.Day) Res.string.day_plan else Res.string.week_plan), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    val count = visibleSessions.count { it.calendarStart.toLocalDateTime(TrainerZone).date in visibleDays }
                    Text(tr(Res.string.sessions_count, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val attention = visibleSessions.count { it.onDate(state.selectedDate) && it.needsAttention() }
        if (attention > 0 && state.calendarMode == CalendarMode.Day) item {
            InfoBanner(tr(Res.string.requests_count, attention), Icons.Outlined.Schedule)
        }
        visibleDays.forEach { date ->
            val sessions = visibleSessions.filter { it.onDate(date) }.sortedBy { it.calendarStart }
            val blocks = data.blocks.filter { it.start.toLocalDateTime(TrainerZone).date == date }
            if (state.calendarMode == CalendarMode.Week) item("date-$date") {
                Text(DateFormats.date(date, language).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Layout.padding).semantics { heading() })
            }
            if (sessions.isEmpty() && blocks.isEmpty()) item("empty-$date") {
                if (state.calendarMode == CalendarMode.Day) EmptyContent(Icons.Outlined.CalendarToday, tr(Res.string.no_sessions), tr(Res.string.no_sessions_body), tr(Res.string.new_session), onNew)
                else Text(tr(Res.string.no_upcoming), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val entries = sessions.map { it.calendarStart to it } + blocks.map { it.start to it }
            items(entries.sortedBy { it.first }, key = { (_, entry) -> if (entry is Session) entry.id else (entry as AvailabilityBlock).id }) { (_, entry) ->
                if (entry is Session) SessionRow(entry, data.client(entry.clientId), onClick = { onSession(entry.id) })
                else BlockRow(entry as AvailabilityBlock, onAvailability)
            }
        }
    }
    if (datePicker) AppDatePicker(state.selectedDate, onDismiss = { datePicker = false }, onDate = { onDate(it); datePicker = false })
}

@Composable
fun SessionRow(session: Session, client: Client, onClick: () -> Unit, showDate: Boolean = false) {
    val language = DateFormats.defaultLanguage()
    if (LocalDensity.current.fontScale >= 1.5f && !showDate) {
        Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            Text(timeRange(session.calendarStart, session.calendarEnd, session.timeZone), style = MaterialTheme.typography.labelLarge)
            SessionCard(session, client, onClick, showDate, Modifier.fillMaxWidth())
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
        if (!showDate) Column(Modifier.width(48.dp).padding(top = Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
            Text(DateFormats.time(session.calendarStart, session.timeZone, language), style = MaterialTheme.typography.labelLarge)
            Text(DateFormats.time(session.calendarEnd, session.timeZone, language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SessionCard(session, client, onClick, showDate, Modifier.weight(1f))
    }
}

@Composable
private fun SessionCard(session: Session, client: Client, onClick: () -> Unit, showDate: Boolean, modifier: Modifier) {
    val language = DateFormats.defaultLanguage()
    Card(onClick = onClick, modifier = modifier, shape = Layout.tile, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            StatusLabel(session)
            Text(client.name, style = MaterialTheme.typography.titleMedium)
            if (showDate) MetaLine(Icons.Outlined.CalendarToday, DateFormats.date(session.calendarStart.toLocalDateTime(TrainerZone).date, language, "d MMM · ") + DateFormats.time(session.calendarStart, session.timeZone, language))
            MetaLine(Icons.Outlined.LocationOn, session.location)
            if (session.proposal != null) MetaLine(Icons.Outlined.History,
                tr(Res.string.current_time_short, DateFormats.date(session.start.toLocalDateTime(TrainerZone).date, language, "d MMM") + " · " + DateFormats.time(session.start, session.timeZone, language)))
        }
    }
}

@Composable
fun BlockRow(block: AvailabilityBlock, onClick: () -> Unit) {
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            Text(timeRange(block.start, block.end), style = MaterialTheme.typography.labelLarge)
            BlockCard(onClick, Modifier.fillMaxWidth())
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
        Column(Modifier.width(48.dp).padding(top = Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
            Text(DateFormats.time(block.start, TrainerZone.id, DateFormats.defaultLanguage()), style = MaterialTheme.typography.labelLarge)
            Text(DateFormats.time(block.end, TrainerZone.id, DateFormats.defaultLanguage()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BlockCard(onClick, Modifier.weight(1f))
    }
}

@Composable
private fun BlockCard(onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = Layout.tile,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
            MetaLine(Icons.Outlined.Block, tr(Res.string.blocked))
            Text(tr(Res.string.block_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDatePicker(date: LocalDate, onDismiss: () -> Unit, onDate: (LocalDate) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDays().toLong() * 86_400_000)
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            picker.selectedDateMillis?.let { onDate(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date) }
        }, enabled = picker.selectedDateMillis != null) { Text(tr(Res.string.choose)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Res.string.cancel)) } }) {
        DatePicker(picker, title = { Text(tr(Res.string.choose_date), Modifier.padding(Layout.gutter)) })
    }
}
