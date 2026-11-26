package pl.trainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
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

/** A free gap shorter than this is not shown between two entries. */
private const val MinFreeGapMinutes = 60

/** One line of the day agenda. */
private sealed interface AgendaEntry {
    val at: Instant
    data class Booked(val session: Session) : AgendaEntry { override val at get() = session.calendarStart }
    data class Blocked(val block: AvailabilityBlock) : AgendaEntry { override val at get() = block.start }
    data class Free(override val at: Instant, val until: Instant) : AgendaEntry
    data class Now(override val at: Instant) : AgendaEntry
}

/**
 * The trainer's schedule (docs/plans/trainer-redesign.md, Kalendarz): the week strip, then the day or week agenda.
 * Each entry type has its own look: the next session as the hero, regular sessions as white cards with the client's
 * photo, requests in the attention colour with Accept / Reject, blocked time striped, finished sessions dimmed.
 * In the day view free gaps between entries can be tapped to add a session, and a line marks the server's "now".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(state: TrainerUiState, onDate: (LocalDate) -> Unit, onMode: (CalendarMode) -> Unit,
    onAvailability: () -> Unit, onSession: (String) -> Unit, onNew: () -> Unit,
    onAccept: (Session) -> Unit = {}, onReject: (Session) -> Unit = {}) {
    val data = state.data ?: return
    val language = DateFormats.defaultLanguage()
    var datePicker by remember { mutableStateOf(false) }
    val dates = weekDates(state.selectedDate)
    val visibleDays = if (state.calendarMode == CalendarMode.Day) listOf(state.selectedDate) else dates
    val visibleSessions = data.sessions.filterNot { it.isClosedWithoutTraining() }
    val nextId = state.nextSession?.id
    val now = state.now

    LazyColumn(modifier = Modifier.testTag("calendar-list"), contentPadding = PaddingValues(start = Layout.gutter, end = Layout.gutter, top = Layout.small, bottom = Layout.gutter),
        verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { datePicker = true }, modifier = Modifier.weight(1f).heightIn(min = Layout.target), contentPadding = PaddingValues(0.dp)) {
                    Text(DateFormats.date(state.selectedDate, language, "LLLL yyyy").replaceFirstChar { it.titlecase() },
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.KeyboardArrowDown, tr(Res.string.choose_date))
                }
                if (state.selectedDate != state.today) Surface(onClick = { onDate(state.today) }, shape = Layout.pill, color = AppColors.softAccent,
                    contentColor = AppColors.onSoftAccent, modifier = Modifier.heightIn(min = Layout.target).padding(vertical = Layout.small)) {
                    Box(Modifier.padding(horizontal = Layout.padding), contentAlignment = Alignment.Center) {
                        Text(tr(Res.string.today), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item {
            PillTabs(CalendarMode.entries, state.calendarMode, modifier = Modifier.fillMaxWidth(),
                label = { tr(if (it == CalendarMode.Day) Res.string.day else Res.string.week) }, onSelect = onMode)
        }
        item { WeekStrip(state, dates, visibleSessions, onDate) }
        item {
            val count = visibleSessions.count { it.calendarStart.toLocalDateTime(TrainerZone).date in visibleDays }
            Row(Modifier.fillMaxWidth().padding(top = Layout.small), verticalAlignment = Alignment.Bottom) {
                Text(
                    if (state.calendarMode == CalendarMode.Day) DateFormats.date(state.selectedDate, language, "EEEE").replaceFirstChar { it.titlecase() }
                    else tr(Res.string.week_plan),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(tr(Res.string.sessions_count, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        visibleDays.forEach { date ->
            val sessions = visibleSessions.filter { it.onDate(date) }
            val blocks = data.blocks.filter { it.start.toLocalDateTime(TrainerZone).date == date }
            if (state.calendarMode == CalendarMode.Week) item("date-$date") {
                Text(DateFormats.date(date, language).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.titleMedium,
                    color = if (date == state.today) AppColors.brandStrong else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Layout.small).semantics { heading() })
            }
            if (sessions.isEmpty() && blocks.isEmpty()) item("empty-$date") {
                if (state.calendarMode == CalendarMode.Day) EmptyContent(Icons.Outlined.CalendarToday, tr(Res.string.no_sessions), tr(Res.string.no_sessions_body), tr(Res.string.new_session), onNew)
                else Text(tr(Res.string.no_upcoming), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val entries = agenda(sessions, blocks, withGaps = state.calendarMode == CalendarMode.Day, now = now?.takeIf { date == state.today })
                items(entries, key = { entry ->
                    when (entry) {
                        is AgendaEntry.Booked -> entry.session.id
                        is AgendaEntry.Blocked -> entry.block.id
                        is AgendaEntry.Free -> "free-${entry.at}"
                        is AgendaEntry.Now -> "now-$date"
                    }
                }) { entry ->
                    when (entry) {
                        is AgendaEntry.Booked -> {
                            val session = entry.session
                            val client = data.client(session.clientId)
                            when {
                                session.isOpenRequest() -> TimedRow(session.calendarStart, session.calendarEnd, session.timeZone) { modifier ->
                                    CalendarRequestCard(session, client, state.submitting, { onAccept(session) }, { onReject(session) }, { onSession(session.id) }, modifier)
                                }
                                session.id == nextId && session.proposal == null -> TimedRow(session.start, session.end, session.timeZone, emphasised = true) { modifier ->
                                    NextCalendarCard(session, client, now, modifier) { onSession(session.id) }
                                }
                                else -> SessionRow(session, client, onClick = { onSession(session.id) }, finished = now != null && session.end <= now)
                            }
                        }
                        is AgendaEntry.Blocked -> BlockRow(entry.block, onAvailability)
                        is AgendaEntry.Free -> FreeGapRow(entry.at, entry.until, onNew)
                        is AgendaEntry.Now -> NowLine(entry.at)
                    }
                }
            }
        }
    }
    if (datePicker) AppDatePicker(state.selectedDate, onDismiss = { datePicker = false }, onDate = { onDate(it); datePicker = false })
}

/** Sessions and blocks in time order, with free gaps (day view) and the "now" line inserted between them. */
private fun agenda(sessions: List<Session>, blocks: List<AvailabilityBlock>, withGaps: Boolean, now: Instant?): List<AgendaEntry> {
    val booked = (sessions.map { AgendaEntry.Booked(it) } + blocks.map { AgendaEntry.Blocked(it) }).sortedBy { it.at }
    val result = mutableListOf<AgendaEntry>()
    var previousEnd: Instant? = null
    var nowPlaced = now == null
    booked.forEach { entry ->
        val end = when (entry) {
            is AgendaEntry.Booked -> entry.session.calendarEnd
            is AgendaEntry.Blocked -> entry.block.end
            else -> entry.at
        }
        if (!nowPlaced && now!! < entry.at) { result += AgendaEntry.Now(now); nowPlaced = true }
        val gapStart = previousEnd
        if (withGaps && gapStart != null && (entry.at - gapStart).inWholeMinutes >= MinFreeGapMinutes) result += AgendaEntry.Free(gapStart, entry.at)
        result += entry
        previousEnd = maxOf(previousEnd ?: end, end)
    }
    if (!nowPlaced) result += AgendaEntry.Now(now!!)
    return result
}

@Composable
private fun WeekStrip(state: TrainerUiState, dates: List<LocalDate>, sessions: List<Session>, onDate: (LocalDate) -> Unit) {
    val language = DateFormats.defaultLanguage()
    val density = LocalDensity.current
    val dayScroll = rememberScrollState()
    BoxWithConstraints {
        val cellWidth = maxOf(Layout.target * density.fontScale.coerceAtLeast(1f), maxWidth / 7)
        LaunchedEffect(state.selectedDate, cellWidth) {
            val offset = cellWidth * dates.indexOf(state.selectedDate) - (maxWidth - cellWidth) / 2
            dayScroll.scrollTo(with(density) { offset.roundToPx() }.coerceAtLeast(0))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(dayScroll)) {
            dates.forEach { date ->
                val chosen = date == state.selectedDate
                val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
                val daySessions = sessions.filter { it.onDate(date) }
                val dots = daySessions.take(3).map { it.isOpenRequest() }
                val secondary = if (weekend) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(onClick = { onDate(date) }, shape = RoundedCornerShapeTile,
                    color = if (chosen) AppColors.brandStrong else Color.Transparent,
                    modifier = Modifier.width(cellWidth).padding(horizontal = 2.dp).semantics {
                        selected = chosen
                        contentDescription = DateFormats.date(date, language) + ", " + daySessions.size
                    }) {
                    Column(Modifier.padding(vertical = Layout.small), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                        Text(DateFormats.date(date, language, "EEE").replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.bodySmall,
                            color = if (chosen) Color.White.copy(alpha = 0.88f) else secondary)
                        Text(date.day.toString(), style = MaterialTheme.typography.titleMedium,
                            color = when {
                                chosen -> Color.White
                                date == state.today -> AppColors.brandStrong
                                weekend -> secondary
                                else -> MaterialTheme.colorScheme.onSurface
                            })
                        Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            dots.forEach { request ->
                                Box(Modifier.size(5.dp).background(
                                    when {
                                        request -> if (chosen) AppColors.attentionOnBrand else AppColors.warningText
                                        chosen -> Color.White
                                        else -> AppColors.accent
                                    }, Layout.pill))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val RoundedCornerShapeTile = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)

/** The time column next to an agenda card; at large font scales the time goes above the card instead. */
@Composable
private fun TimedRow(start: Instant, end: Instant, zone: String, emphasised: Boolean = false, card: @Composable (Modifier) -> Unit) {
    val language = DateFormats.defaultLanguage()
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            Text(timeRange(start, end, zone), style = MaterialTheme.typography.labelLarge)
            card(Modifier.fillMaxWidth())
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
        Column(Modifier.width(48.dp).padding(top = Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
            Text(DateFormats.time(start, zone, language), style = MaterialTheme.typography.labelLarge,
                color = if (emphasised) AppColors.brandStrong else MaterialTheme.colorScheme.onSurface)
            Text(DateFormats.time(end, zone, language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        card(Modifier.weight(1f))
    }
}

/** A session in the agenda (and in the client card with [showDate]). [finished] dims it and shows its outcome. */
@Composable
fun SessionRow(session: Session, client: Client, onClick: () -> Unit, showDate: Boolean = false, finished: Boolean = false) {
    if (showDate) {
        SessionCard(session, client, onClick, true, finished, Modifier.fillMaxWidth())
        return
    }
    TimedRow(session.calendarStart, session.calendarEnd, session.timeZone) { modifier ->
        SessionCard(session, client, onClick, false, finished, modifier)
    }
}

@Composable
private fun SessionCard(session: Session, client: Client, onClick: () -> Unit, showDate: Boolean, finished: Boolean, modifier: Modifier) {
    val language = DateFormats.defaultLanguage()
    val dimmed = finished || session.status == SessionStatus.Completed || session.status == SessionStatus.NoShow
    Surface(onClick = onClick, modifier = modifier, shape = Layout.tile, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(Layout.padding), horizontalArrangement = Arrangement.spacedBy(Layout.medium), verticalAlignment = Alignment.CenterVertically) {
            Portrait(client.initials, client.userId, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(client.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                val time = if (showDate) DateFormats.date(session.calendarStart.toLocalDateTime(TrainerZone).date, language, "EEE d MMM").replaceFirstChar { it.titlecase() } + " · " +
                    timeRange(session.calendarStart, session.calendarEnd, session.timeZone) else timeRange(session.calendarStart, session.calendarEnd, session.timeZone)
                Text(listOf(time, session.location).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (session.planTitle != null) MetaLine(Icons.Outlined.FitnessCenter, session.planTitle)
                if (session.proposal?.author == ProposalAuthor.Trainer || showDate) StatusLabel(session)
            }
            when {
                session.status == SessionStatus.Completed -> Icon(Icons.Outlined.CheckCircle, tr(Res.string.attendance_present), tint = AppColors.success)
                session.status == SessionStatus.NoShow -> Icon(Icons.Outlined.Cancel, tr(Res.string.attendance_no_show), tint = AppColors.errorText)
                else -> Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The next session in the agenda: the same hero as Start. */
@Composable
private fun NextCalendarCard(session: Session, client: Client, now: Instant?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    HeroCard(modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            Portrait(client.initials, client.userId, size = 40.dp, ring = true)
            Column(Modifier.weight(1f)) {
                Text(client.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(timeRange(session.start, session.end, session.timeZone), session.location).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.88f))
            }
        }
        Spacer(Modifier.height(Layout.small))
        val label = when {
            now != null && session.start <= now -> tr(Res.string.start_in_progress)
            else -> tr(Res.string.start_next_session)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White,
            modifier = Modifier.background(Color.White.copy(alpha = 0.18f), Layout.pill).padding(horizontal = Layout.medium, vertical = Layout.tiny))
    }
}

/** A request in the agenda: attention colour, dashed outline and the answer buttons on the spot. */
@Composable
private fun CalendarRequestCard(session: Session, client: Client, submitting: Boolean, onAccept: () -> Unit, onReject: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val language = DateFormats.defaultLanguage()
    Column(
        modifier.fillMaxWidth().clip(Layout.tile).background(AppColors.warningSurface).dashedBorder(AppColors.warningText)
            .clickable(role = Role.Button, onClick = onOpen).padding(Layout.padding),
        verticalArrangement = Arrangement.spacedBy(Layout.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            Portrait(client.initials, client.userId, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(client.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                    Icon(if (session.proposal != null) Icons.Outlined.Update else Icons.Outlined.HourglassEmpty, null, Modifier.size(14.dp), tint = AppColors.warningText)
                    Text(
                        if (session.proposal != null) tr(Res.string.request_kind_reschedule_from,
                            DateFormats.date(session.start.toLocalDateTime(TrainerZone).date, language, "EEE d MMM") + " " + DateFormats.time(session.start, session.timeZone, language))
                        else tr(Res.string.request_kind_booking),
                        style = MaterialTheme.typography.labelMedium, color = AppColors.warningText,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
            Button(onClick = onAccept, enabled = !submitting, shape = Layout.pill, modifier = Modifier.weight(1f).heightIn(min = Layout.target)) { Text(tr(Res.string.accept)) }
            OutlinedButton(onClick = onReject, enabled = !submitting, shape = Layout.pill, modifier = Modifier.weight(1f).heightIn(min = Layout.target),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = AppColors.surface)) { Text(tr(Res.string.reject)) }
        }
    }
}

/** A dashed rounded outline, for requests and free gaps (not yet booked). */
private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawRoundRect(color = color, style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()),
        topLeft = Offset(stroke / 2, stroke / 2), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
}

@Composable
private fun FreeGapRow(from: Instant, until: Instant, onNew: () -> Unit) {
    val text = tr(Res.string.calendar_free, timeRange(from, until))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
        Spacer(Modifier.width(48.dp))
        Row(
            Modifier.weight(1f).clip(Layout.tile).dashedBorder(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                .clickable(role = Role.Button, onClickLabel = tr(Res.string.new_session), onClick = onNew)
                .heightIn(min = Layout.target).padding(horizontal = Layout.padding, vertical = Layout.small),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.small),
        ) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NowLine(at: Instant) {
    val label = DateFormats.time(at, TrainerZone.id, DateFormats.defaultLanguage())
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Layout.tiny)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AppColors.errorAccent, modifier = Modifier.width(48.dp))
        Box(Modifier.size(8.dp).background(AppColors.errorAccent, Layout.pill))
        Box(Modifier.weight(1f).height(2.dp).background(AppColors.errorAccent))
    }
}

@Composable
fun BlockRow(block: AvailabilityBlock, onClick: () -> Unit) {
    TimedRow(block.start, block.end, TrainerZone.id) { modifier -> BlockCard(block, onClick, modifier) }
}

@Composable
private fun BlockCard(block: AvailabilityBlock, onClick: () -> Unit, modifier: Modifier) {
    val stripe = AppColors.outline
    Row(
        modifier.fillMaxWidth().clip(Layout.tile).background(AppColors.completedSurface)
            .drawBehind {
                val step = 16.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 6.dp.toPx())
                    x += step
                }
            }
            .clickable(role = Role.Button, onClick = onClick).padding(Layout.padding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium),
    ) {
        Icon(Icons.Outlined.Lock, null, tint = AppColors.completedText)
        Column(Modifier.weight(1f)) {
            Text(tr(Res.string.blocked), style = MaterialTheme.typography.titleMedium, color = AppColors.completedText)
            Text(timeRange(block.start, block.end) + " · " + tr(Res.string.block_description), style = MaterialTheme.typography.bodySmall, color = AppColors.completedText)
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
