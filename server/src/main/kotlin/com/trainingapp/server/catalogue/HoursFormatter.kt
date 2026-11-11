package com.trainingapp.server.catalogue

/**
 * `gym.opening_hours` → the one-line summary of the gym card, in the request's locale:
 * `Pon–Pt 06:00–22:00 · Sob–Nd 08:00–20:00`, `Pon–Nd 07:00–22:00` or `Całą dobę`.
 */
object HoursFormatter {
    private val dayNames = mapOf(
        "pl" to listOf("Pon", "Wt", "Śr", "Czw", "Pt", "Sob", "Nd"),
        "en" to listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
    )

    fun summary(hours: List<OpeningHours>, locale: String): String {
        val lang = if (locale == "en") "en" else "pl"
        if (hours.isEmpty()) return ""
        val byDay = hours.associateBy { it.weekday }
        val allDay = (1..7).all { d -> byDay[d]?.let { it.opens == "00:00" && (it.closes == "00:00" || it.closes == "24:00") } == true }
        if (allDay) return if (lang == "en") "Open 24 hours" else "Całą dobę"
        val names = dayNames.getValue(lang)
        val groups = mutableListOf<Triple<Int, Int, String>>()
        for (day in 1..7) {
            val window = byDay[day]?.let { "${it.opens}–${it.closes}" } ?: continue
            val last = groups.lastOrNull()
            if (last != null && last.second == day - 1 && last.third == window) groups[groups.size - 1] = last.copy(second = day)
            else groups += Triple(day, day, window)
        }
        return groups.joinToString(" · ") { (from, to, window) ->
            val days = if (from == to) names[from - 1] else "${names[from - 1]}–${names[to - 1]}"
            "$days $window"
        }
    }
}
