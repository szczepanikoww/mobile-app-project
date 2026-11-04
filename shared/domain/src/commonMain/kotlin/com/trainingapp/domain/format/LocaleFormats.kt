package com.trainingapp.domain.format

/** Locale-aware formatting shared by every screen. Times are shown in the device's time zone. */
expect object LocaleFormats {
    fun decimalSeparator(languageTag: String): Char

    /** Medium date, e.g. "28 paź 2026" / "Oct 28, 2026". */
    fun formatDate(epochMs: Long, languageTag: String): String

    /** Short time, e.g. "14:05" / "2:05 PM". */
    fun formatTime(epochMs: Long, languageTag: String): String

    /** Money in the locale's style, e.g. "1 200 zł" / "PLN 1,200". Whole amounts are shown without decimals. */
    fun formatMoney(money: Money, languageTag: String): String

    /** A decimal number with at most [maxFractionDigits] digits, e.g. "4,9" / "4.9". */
    fun formatDecimal(value: Double, languageTag: String, maxFractionDigits: Int = 1): String
}
