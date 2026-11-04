package pl.trainerapp.core.format

import androidx.compose.runtime.Composable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import pl.trainer.resources.*
import pl.trainer.ui.DateFormats
import pl.trainerapp.feature.trainerfinance.model.Pln
import kotlin.math.abs
import kotlin.time.Instant

private const val NonBreakingSpace = " "

/** "3 480 zł"; grosze are shown only when non-zero or [alwaysDecimals] ("530,00 zł"). Non-breaking spaces keep it on one line. */
fun formatPln(amount: Pln, alwaysDecimals: Boolean = false): String {
    val zl = abs(amount.grosze) / 100
    val gr = abs(amount.grosze) % 100
    val grouped = zl.toString().reversed().chunked(3).joinToString(NonBreakingSpace).reversed()
    val sign = if (amount.grosze < 0) "-" else ""
    val decimals = if (gr != 0L || alwaysDecimals) "," + gr.toString().padStart(2, '0') else ""
    return "$sign$grouped$decimals${NonBreakingSpace}zł"
}

/** "+960 zł" for incoming amounts. */
fun formatPlnSigned(amount: Pln): String = (if (amount.grosze >= 0) "+" else "") + formatPln(amount)

/** "Dzisiaj, 08:30", "Wczoraj, 18:45" or "20 paź". [now] is for display only. */
@Composable
fun formatPaymentWhen(at: Instant, zone: TimeZone, now: Instant): String {
    val day = at.toLocalDateTime(zone).date
    return when (day.daysUntil(now.toLocalDateTime(zone).date)) {
        0 -> stringResource(Res.string.finance_today_time, formatTime(at, zone))
        1 -> stringResource(Res.string.finance_yesterday_time, formatTime(at, zone))
        else -> DateFormats.date(day, AppLanguage, "d MMM")
    }
}
