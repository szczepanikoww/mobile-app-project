package com.trainingapp.feature.guest

import com.trainingapp.feature.guest.model.OpeningPeriod
import com.trainingapp.feature.guest.model.isOpenAt
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpeningHoursTest {
    private val warsaw = TimeZone.of("Europe/Warsaw")
    private val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        .map { OpeningPeriod(it, LocalTime(6, 0), LocalTime(22, 0)) }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: TimeZone = warsaw) =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone)

    @Test
    fun insideAndOutsideHours() {
        // 2026-11-04 is a Wednesday.
        assertTrue(isOpenAt(weekdays, warsaw, at(2026, 11, 4, 6, 0)))
        assertTrue(isOpenAt(weekdays, warsaw, at(2026, 11, 4, 21, 59)))
        assertFalse(isOpenAt(weekdays, warsaw, at(2026, 11, 4, 22, 0)))
        assertFalse(isOpenAt(weekdays, warsaw, at(2026, 11, 4, 5, 59)))
        // Saturday is not in the list.
        assertFalse(isOpenAt(weekdays, warsaw, at(2026, 11, 7, 12, 0)))
    }

    @Test
    fun overnightPeriodRunsPastMidnight() {
        val friday = listOf(OpeningPeriod(DayOfWeek.FRIDAY, LocalTime(18, 0), LocalTime(2, 0)))
        assertTrue(isOpenAt(friday, warsaw, at(2026, 11, 6, 23, 0)))
        assertTrue(isOpenAt(friday, warsaw, at(2026, 11, 7, 1, 30)))
        assertFalse(isOpenAt(friday, warsaw, at(2026, 11, 7, 2, 0)))
        assertFalse(isOpenAt(friday, warsaw, at(2026, 11, 6, 17, 0)))
    }

    @Test
    fun midnightToMidnightIsTheWholeDay() {
        val allDay = DayOfWeek.entries.map { OpeningPeriod(it, LocalTime(0, 0), LocalTime(0, 0)) }
        assertTrue(isOpenAt(allDay, warsaw, at(2026, 11, 4, 0, 0)))
        assertTrue(isOpenAt(allDay, warsaw, at(2026, 11, 4, 23, 59)))
        val sundayOnly = listOf(OpeningPeriod(DayOfWeek.SUNDAY, LocalTime(0, 0), LocalTime(0, 0)))
        // A 24 h Sunday does not leak into Monday morning.
        assertFalse(isOpenAt(sundayOnly, warsaw, at(2026, 11, 9, 0, 30)))
    }

    @Test
    fun usesThePlaceZoneAcrossDaylightSavingChanges() {
        // 2026-03-29: Europe/Warsaw switches to summer time at 02:00 → 03:00 (a Sunday).
        val sunday = listOf(OpeningPeriod(DayOfWeek.SUNDAY, LocalTime(8, 0), LocalTime(20, 0)))
        assertTrue(isOpenAt(sunday, warsaw, at(2026, 3, 29, 8, 0)))
        assertFalse(isOpenAt(sunday, warsaw, at(2026, 3, 29, 7, 59)))
        // 2026-10-25: back to winter time; 07:30 local is still before opening.
        assertFalse(isOpenAt(sunday, warsaw, at(2026, 10, 25, 7, 30)))
        assertTrue(isOpenAt(sunday, warsaw, at(2026, 10, 25, 8, 30)))
    }

    @Test
    fun aDeviceInAnotherZoneSeesTheGymsLocalHours() {
        // 21:30 Wednesday in Warsaw is 15:30 in New York; the gym is open by its own clock.
        val newYork = TimeZone.of("America/New_York")
        assertTrue(isOpenAt(weekdays, warsaw, at(2026, 11, 4, 15, 30, newYork)))
        // 17:00 in New York is 23:00 in Warsaw: closed.
        assertFalse(isOpenAt(weekdays, warsaw, at(2026, 11, 4, 17, 0, newYork)))
    }
}
