package pl.trainer.ui

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

expect object DateFormats {
    fun date(date: LocalDate, language: String, pattern: String = "EEEE, d MMMM"): String
    fun time(instant: Instant, zone: String, language: String): String
    fun number(value: Double, language: String): String
    fun defaultLanguage(): String
}
