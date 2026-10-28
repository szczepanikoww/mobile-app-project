package pl.trainerapp.core.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.trainerapp.R
import pl.trainerapp.feature.trainerfinance.model.Pln
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private val AppLocale: Locale = Locale.forLanguageTag("pl-PL")
private const val NonBreakingSpace = " "

/** "3 480 zł"; grosze are shown only when non-zero or [alwaysDecimals] ("530,00 zł"). Non-breaking spaces keep it on one line. */
fun formatPln(amount: Pln, alwaysDecimals: Boolean = false): String {
    val zl = abs(amount.grosze) / 100
    val gr = abs(amount.grosze) % 100
    val grouped = zl.toString().reversed().chunked(3).joinToString(NonBreakingSpace).reversed()
    val sign = if (amount.grosze < 0) "-" else ""
    val decimals = if (gr != 0L || alwaysDecimals) ",%02d".format(gr) else ""
    return "$sign$grouped$decimals${NonBreakingSpace}zł"
}

/** "+960 zł" for incoming amounts. */
fun formatPlnSigned(amount: Pln): String = (if (amount.grosze >= 0) "+" else "") + formatPln(amount)

/** "Dzisiaj, 08:30", "Wczoraj, 18:45" or "20 paź". [now] is for display only. */
@Composable
fun formatPaymentWhen(at: Instant, zone: ZoneId, now: Instant): String {
    val day = at.atZone(zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(day, now.atZone(zone).toLocalDate())) {
        0L -> stringResource(R.string.finance_today_time, formatTime(at, zone))
        1L -> stringResource(R.string.finance_yesterday_time, formatTime(at, zone))
        else -> DateTimeFormatter.ofPattern("d MMM", AppLocale).format(day)
    }
}
