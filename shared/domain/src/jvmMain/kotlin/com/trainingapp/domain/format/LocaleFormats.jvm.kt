package com.trainingapp.domain.format

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

actual object LocaleFormats {
    actual fun decimalSeparator(languageTag: String): Char =
        DecimalFormatSymbols.getInstance(Locale.forLanguageTag(languageTag)).decimalSeparator

    actual fun formatDate(epochMs: Long, languageTag: String): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.forLanguageTag(languageTag))
            .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    actual fun formatTime(epochMs: Long, languageTag: String): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.forLanguageTag(languageTag))
            .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    actual fun formatMoney(money: Money, languageTag: String): String {
        val currency = Currency.getInstance(money.currency)
        val format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag(languageTag)).apply {
            this.currency = currency
            val digits = currency.defaultFractionDigits.coerceAtLeast(0)
            val whole = digits == 0 || money.minor % pow10(digits) == 0L
            minimumFractionDigits = if (whole) 0 else digits
            maximumFractionDigits = if (whole) 0 else digits
        }
        val amount = BigDecimal.valueOf(money.minor, currency.defaultFractionDigits.coerceAtLeast(0))
        // Non-breaking spaces from the JDK are kept, so "1 200 zł" never wraps inside the amount.
        return format.format(amount)
    }

    actual fun formatDecimal(value: Double, languageTag: String, maxFractionDigits: Int): String =
        NumberFormat.getNumberInstance(Locale.forLanguageTag(languageTag)).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = maxFractionDigits
            isGroupingUsed = true
        }.format(value)

    private fun pow10(digits: Int): Long = (1..digits).fold(1L) { acc, _ -> acc * 10 }
    actual fun formatInteger(value: Long, languageTag: String): String =
        NumberFormat.getIntegerInstance(Locale.forLanguageTag(languageTag)).format(value)

    actual fun formatShortDate(epochMs: Long, languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        val pattern = if (locale.language == "pl") "d MMM" else "MMM d"
        return DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
}
