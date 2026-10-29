package com.trainingapp.domain.format

/** Locale-aware formatting shared by every screen. Times are shown in the device's time zone. */
expect object LocaleFormats {
    fun decimalSeparator(languageTag: String): Char

    /** Medium date, e.g. "28 paź 2026" / "Oct 28, 2026". */
    fun formatDate(epochMs: Long, languageTag: String): String

    /** Short time, e.g. "14:05" / "2:05 PM". */
    fun formatTime(epochMs: Long, languageTag: String): String
}
