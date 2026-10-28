package com.trainingapp.domain

import com.trainingapp.domain.plan.SetInput
import com.trainingapp.domain.plan.SetInput.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals

class SetInputTest {

    @Test
    fun repsAcceptSingleValuesAndRanges() {
        assertEquals(Parsed.Value(SetInput.Reps(8, 8)), SetInput.parseReps("8"))
        assertEquals(Parsed.Value(SetInput.Reps(8, 10)), SetInput.parseReps("8–10"))
        assertEquals(Parsed.Value(SetInput.Reps(8, 10)), SetInput.parseReps(" 8 - 10 "))
        assertEquals(Parsed.Blank, SetInput.parseReps(" "))
        assertEquals(Parsed.Invalid, SetInput.parseReps("8–"))
        assertEquals(Parsed.Invalid, SetInput.parseReps("osiem"))
    }

    @Test
    fun weightAcceptsCommaAndDotAndStoresGrams() {
        assertEquals(Parsed.Value(82_500), SetInput.parseWeightGrams("82,5"))
        assertEquals(Parsed.Value(82_500), SetInput.parseWeightGrams("82.5"))
        assertEquals(Parsed.Value(1_250), SetInput.parseWeightGrams("1,25"))
        assertEquals(Parsed.Value(0), SetInput.parseWeightGrams("0"))
        assertEquals(Parsed.Invalid, SetInput.parseWeightGrams("-5"))
        assertEquals(Parsed.Invalid, SetInput.parseWeightGrams("1,2,3"))
        assertEquals(Parsed.Invalid, SetInput.parseWeightGrams("1,2345"))
    }

    @Test
    fun durationAcceptsSecondsOrClockFormat() {
        assertEquals(Parsed.Value(90), SetInput.parseDurationSeconds("90"))
        assertEquals(Parsed.Value(90), SetInput.parseDurationSeconds("1:30"))
        assertEquals(Parsed.Value(3_725), SetInput.parseDurationSeconds("1:02:05"))
        assertEquals(Parsed.Invalid, SetInput.parseDurationSeconds("1:75"))
        assertEquals(Parsed.Invalid, SetInput.parseDurationSeconds("1:"))
    }

    @Test
    fun formattingRoundTrips() {
        assertEquals("82,5", SetInput.formatThousandths(82_500, ','))
        assertEquals("80", SetInput.formatThousandths(80_000, ','))
        assertEquals("1.25", SetInput.formatThousandths(1_250, '.'))
        assertEquals("8–10", SetInput.formatReps(8, 10))
        assertEquals("8", SetInput.formatReps(8, 8))
        assertEquals("2:00", SetInput.formatDuration(120))
        assertEquals("1:02:05", SetInput.formatDuration(3_725))
        assertEquals("8,5", SetInput.formatRpe(8.5, ','))
        assertEquals(Parsed.Value(8.5), SetInput.parseRpe("8,5"))
    }
}
