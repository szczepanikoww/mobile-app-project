package pl.trainerapp.core.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.trainerapp.R
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The whole app is Polish, regardless of the device language. */
private val AppLocale: Locale = Locale.forLanguageTag("pl-PL")

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm", AppLocale)

/** Starts-in labels are only shown for sessions closer than this; later ones show the clock time. */
private const val StartsInMaxMinutes = 6 * 60L

/** Time of day only, in the given zone, e.g. "10:00". */
fun formatTime(instant: Instant, zone: ZoneId): String = ClockFormat.format(instant.atZone(zone))

/** "Dziś, 10:00 – 11:00", "Jutro, …" or "czw. 5 lis, …". [now] is for display only. */
@Composable
fun formatSessionRange(start: Instant, end: Instant, zone: ZoneId, now: Instant): String {
    val range = "${formatTime(start, zone)} – ${formatTime(end, zone)}"
    val day = start.atZone(zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), day)) {
        0L -> stringResource(R.string.day_today_time, range)
        1L -> stringResource(R.string.day_tomorrow_time, range)
        else -> stringResource(
            R.string.day_other_time,
            DateTimeFormatter.ofPattern("EEE d MMM", AppLocale).format(day),
            range,
        )
    }
}

/** "Za 45 min" or "Za 3h 15m" for sessions starting soon; null when it is past or far away. Display only. */
@Composable
fun formatStartsIn(start: Instant, now: Instant): String? {
    val minutes = Duration.between(now, start).toMinutes()
    return when {
        minutes < 1 || minutes >= StartsInMaxMinutes -> null
        minutes < 60 -> stringResource(R.string.starts_in_minutes, minutes.toInt())
        else -> stringResource(R.string.starts_in_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}

/** Clock time of the last successful sync, e.g. "10:42". */
fun formatUpdatedAt(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = formatTime(instant, zone)

/** List-row timestamp: "08:30" today, "Wczoraj", otherwise "20 paź". [now] is for display only. */
@Composable
fun formatListWhen(at: Instant, zone: ZoneId, now: Instant): String {
    val day = at.atZone(zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(day, now.atZone(zone).toLocalDate())) {
        0L -> formatTime(at, zone)
        1L -> stringResource(R.string.time_yesterday)
        else -> DateTimeFormatter.ofPattern("d MMM", AppLocale).format(day)
    }
}
