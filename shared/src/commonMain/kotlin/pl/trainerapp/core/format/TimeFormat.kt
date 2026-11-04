package pl.trainerapp.core.format

import androidx.compose.runtime.Composable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import pl.trainer.resources.*
import pl.trainer.ui.DateFormats
import kotlin.time.Instant

/** The whole app is Polish, regardless of the device language. */
internal const val AppLanguage = "pl-PL"

/** Starts-in labels are only shown for sessions closer than this; later ones show the clock time. */
private const val StartsInMaxMinutes = 6 * 60L

/** Time of day only, in the given zone, e.g. "10:00". */
fun formatTime(instant: Instant, zone: TimeZone): String = DateFormats.time(instant, zone.id, AppLanguage)

/** "Dziś, 10:00 – 11:00", "Jutro, …" or "czw. 5 lis, …". [now] is for display only. */
@Composable
fun formatSessionRange(start: Instant, end: Instant, zone: TimeZone, now: Instant): String {
    val range = "${formatTime(start, zone)} – ${formatTime(end, zone)}"
    val day = start.toLocalDateTime(zone).date
    return when (now.toLocalDateTime(zone).date.daysUntil(day)) {
        0 -> stringResource(Res.string.day_today_time, range)
        1 -> stringResource(Res.string.day_tomorrow_time, range)
        else -> stringResource(
            Res.string.day_other_time,
            DateFormats.date(day, AppLanguage, "EEE d MMM"),
            range,
        )
    }
}

/** "Za 45 min" or "Za 3h 15m" for sessions starting soon; null when it is past or far away. Display only. */
@Composable
fun formatStartsIn(start: Instant, now: Instant): String? {
    val minutes = (start - now).inWholeMinutes
    return when {
        minutes < 1 || minutes >= StartsInMaxMinutes -> null
        minutes < 60 -> stringResource(Res.string.starts_in_minutes, minutes.toInt())
        else -> stringResource(Res.string.starts_in_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}

/** Clock time of the last successful sync, e.g. "10:42". */
fun formatUpdatedAt(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String = formatTime(instant, zone)

/** List-row timestamp: "08:30" today, "Wczoraj", otherwise "20 paź". [now] is for display only. */
@Composable
fun formatListWhen(at: Instant, zone: TimeZone, now: Instant): String {
    val day = at.toLocalDateTime(zone).date
    return when (day.daysUntil(now.toLocalDateTime(zone).date)) {
        0 -> formatTime(at, zone)
        1 -> stringResource(Res.string.time_yesterday)
        else -> DateFormats.date(day, AppLanguage, "d MMM")
    }
}
