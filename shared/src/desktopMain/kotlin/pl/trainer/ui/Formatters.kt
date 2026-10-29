package pl.trainer.ui

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

actual object DateFormats {
    actual fun date(date: LocalDate, language: String, pattern: String): String =
        java.time.LocalDate.parse(date.toString()).format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(language)))
    actual fun time(instant: Instant, zone: String, language: String): String =
        java.time.Instant.parse(instant.toString()).atZone(ZoneId.of(zone))
            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag(language)))
    actual fun number(value: Double, language: String): String =
        java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag(language)).apply {
            maximumFractionDigits = 1
            isGroupingUsed = false
        }.format(value)
    actual fun defaultLanguage(): String = Locale.getDefault().language.let { if (it == "pl") "pl" else "en" }
}
