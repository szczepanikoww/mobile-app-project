package com.trainingapp.feature.guest.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Whether a place with these structured [hours] is open at [instant], in the place's own [zone] (not the device's).
 * A period whose close time is not after its open time runs past midnight; 00:00–00:00 means the whole day.
 */
fun isOpenAt(hours: List<OpeningPeriod>, zone: TimeZone, instant: Instant): Boolean {
    val local = instant.toLocalDateTime(zone)
    val previousDay = local.date.minus(1, DateTimeUnit.DAY)
    return hours.any { period ->
        val overnight = period.close <= period.open
        when {
            period.day == local.date.dayOfWeek -> {
                local.time >= period.open && (overnight || local.time < period.close)
            }
            // Yesterday's overnight period still running this morning.
            overnight && period.day == previousDay.dayOfWeek && period.close != LocalTime(0, 0) -> {
                local.time < period.close
            }
            else -> false
        }
    }
}

/** The same check for the gym detail screen. */
fun GymDetail.isOpenAt(instant: Instant): Boolean = isOpenAt(weeklyHours, TimeZone.of(timeZone), instant)
