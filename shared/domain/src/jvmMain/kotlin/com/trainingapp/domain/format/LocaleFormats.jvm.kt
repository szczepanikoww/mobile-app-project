package com.trainingapp.domain.format

import java.text.DecimalFormatSymbols
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
}
