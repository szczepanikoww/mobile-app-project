package com.trainingapp.domain.plan

/**
 * Parses and formats the values typed into set fields. Both "82,5" and "82.5" are accepted, so a draft
 * typed in one language still parses in another. Parsing returns `null` for blank input and
 * [SetInput.Invalid] for text that cannot be read.
 */
object SetInput {

    sealed interface Parsed<out T> {
        data class Value<T>(val value: T) : Parsed<T>
        data object Blank : Parsed<Nothing>
        data object Invalid : Parsed<Nothing>
    }

    data class Reps(val min: Int, val max: Int)

    /** "8" → 8–8, "8-10", "8–10" and "8 - 10" → 8–10. */
    fun parseReps(text: String): Parsed<Reps> {
        val t = text.trim()
        if (t.isEmpty()) return Parsed.Blank
        val parts = t.split('-', '–', '—').map { it.trim() }
        return when (parts.size) {
            1 -> parts[0].toIntStrictOrNull()?.let { Parsed.Value(Reps(it, it)) } ?: Parsed.Invalid
            2 -> {
                val min = parts[0].toIntStrictOrNull()
                val max = parts[1].toIntStrictOrNull()
                if (min != null && max != null) Parsed.Value(Reps(min, max)) else Parsed.Invalid
            }
            else -> Parsed.Invalid
        }
    }

    /** Kilograms with up to three decimals, to integer grams: "82,5" → 82500. */
    fun parseWeightGrams(text: String): Parsed<Int> = parseDecimalTimes1000(text)

    /** Kilometres with up to three decimals, to integer metres: "2,5" → 2500. */
    fun parseDistanceMeters(text: String): Parsed<Int> = parseDecimalTimes1000(text)

    /** "8", "8,5", "8.5". */
    fun parseRpe(text: String): Parsed<Double> {
        val t = text.trim()
        if (t.isEmpty()) return Parsed.Blank
        val thousandths = parseDecimalTimes1000(t)
        return if (thousandths is Parsed.Value) Parsed.Value(thousandths.value / 1000.0) else Parsed.Invalid
    }

    /**
     * "90" (seconds), "1:30" (min:s) or "1:02:03" (h:min:s), to seconds. Numeric keypads have no colon, so
     * "1.30" and "1,30" are read as 1:30 when exactly two digits follow the separator.
     */
    fun parseDurationSeconds(text: String): Parsed<Int> {
        var t = text.trim()
        if (t.isEmpty()) return Parsed.Blank
        val keypadSeparator = t.indexOfFirst { it == '.' || it == ',' }
        if (keypadSeparator >= 0) {
            if (':' in t || t.length - keypadSeparator - 1 != 2) return Parsed.Invalid
            t = t.substring(0, keypadSeparator) + ":" + t.substring(keypadSeparator + 1)
        }
        val parts = t.split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 5 }) return Parsed.Invalid
        val numbers = parts.map { it.toInt() }
        if (numbers.size > 1 && numbers.drop(1).any { it >= 60 }) return Parsed.Invalid
        return Parsed.Value(numbers.fold(0) { acc, n -> acc * 60 + n })
    }

    fun formatReps(min: Int?, max: Int?): String = when {
        min == null -> ""
        max == null || max == min -> "$min"
        else -> "$min–$max"
    }

    fun formatThousandths(value: Int?, decimalSeparator: Char): String {
        if (value == null) return ""
        val whole = value / 1000
        val fraction = (value % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (fraction.isEmpty()) "$whole" else "$whole$decimalSeparator$fraction"
    }

    fun formatRpe(rpe: Double?, decimalSeparator: Char): String =
        if (rpe == null) "" else formatThousandths((rpe * 1000).toLong().toInt(), decimalSeparator)

    /** 90 → "1:30", 3725 → "1:02:05". */
    fun formatDuration(seconds: Int?): String {
        if (seconds == null) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "$h:${m.pad()}:${s.pad()}" else "$m:${s.pad()}"
    }

    private fun Int.pad() = toString().padStart(2, '0')

    private fun parseDecimalTimes1000(text: String): Parsed<Int> {
        val t = text.trim()
        if (t.isEmpty()) return Parsed.Blank
        val parts = t.replace(',', '.').split('.')
        if (parts.size > 2) return Parsed.Invalid
        val whole = parts[0]
        val fraction = parts.getOrElse(1) { "" }
        if (whole.isEmpty() && fraction.isEmpty()) return Parsed.Invalid
        if (!whole.all(Char::isDigit) || !fraction.all(Char::isDigit)) return Parsed.Invalid
        if (whole.length > 6 || fraction.length > 3) return Parsed.Invalid
        val wholeValue = if (whole.isEmpty()) 0 else whole.toInt()
        val fractionValue = fraction.padEnd(3, '0').toInt()
        return Parsed.Value(wholeValue * 1000 + fractionValue)
    }

    private fun String.toIntStrictOrNull(): Int? =
        if (isNotEmpty() && length <= 6 && all(Char::isDigit)) toInt() else null
}
