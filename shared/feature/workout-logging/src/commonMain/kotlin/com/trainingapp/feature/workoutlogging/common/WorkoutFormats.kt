package com.trainingapp.feature.workoutlogging.common

import androidx.compose.runtime.Composable
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.plan.SetInput
import com.trainingapp.domain.workout.MeasurementUnit
import com.trainingapp.domain.workout.SetField
import com.trainingapp.domain.workout.SetLog
import com.trainingapp.feature.workoutlogging.resources.Res
import com.trainingapp.feature.workoutlogging.resources.unit_cm
import com.trainingapp.feature.workoutlogging.resources.unit_kg
import com.trainingapp.feature.workoutlogging.resources.value_duration_hours
import com.trainingapp.feature.workoutlogging.resources.value_duration_minutes
import com.trainingapp.feature.workoutlogging.resources.value_km
import com.trainingapp.feature.workoutlogging.resources.value_reps
import com.trainingapp.feature.workoutlogging.resources.value_weight
import com.trainingapp.feature.workoutlogging.resources.value_weight_reps
import org.jetbrains.compose.resources.stringResource

/** Locale-aware formatting of workout values, shared by A1–A3. */
object WorkoutFormats {
    fun decimal(thousandths: Int?, languageTag: String): String =
        SetInput.formatThousandths(thousandths, LocaleFormats.decimalSeparator(languageTag))

    /** The text shown in a set field for its stored value. */
    fun fieldText(set: SetLog, field: SetField, languageTag: String): String = when (field) {
        SetField.WEIGHT -> decimal(set.weightGrams, languageTag)
        SetField.REPS -> set.reps?.toString().orEmpty()
        SetField.RPE -> SetInput.formatRpe(set.rpe, LocaleFormats.decimalSeparator(languageTag))
        SetField.DURATION -> SetInput.formatDuration(set.durationSeconds)
        SetField.DISTANCE -> decimal(set.distanceMeters, languageTag)
    }

    /** "24:21" or "1:02:05". */
    fun clock(ms: Long): String = SetInput.formatDuration((ms / 1000).toInt().coerceAtLeast(0))
}

/** "82,5 kg × 8", "12 powt.", "1:00", "2,5 km · 12:00". */
@Composable
fun setValueText(weightGrams: Int?, reps: Int?, durationSeconds: Int?, distanceMeters: Int?, languageTag: String): String = when {
    weightGrams != null && reps != null -> stringResource(Res.string.value_weight_reps, WorkoutFormats.decimal(weightGrams, languageTag), reps)
    weightGrams != null -> stringResource(Res.string.value_weight, WorkoutFormats.decimal(weightGrams, languageTag))
    distanceMeters != null -> listOfNotNull(
        stringResource(Res.string.value_km, WorkoutFormats.decimal(distanceMeters, languageTag)),
        durationSeconds?.let { SetInput.formatDuration(it) },
    ).joinToString(" · ")
    reps != null -> stringResource(Res.string.value_reps, reps)
    durationSeconds != null -> SetInput.formatDuration(durationSeconds)
    else -> "–"
}

@Composable
fun SetLog.valueText(languageTag: String): String = setValueText(weightGrams, reps, durationSeconds, distanceMeters, languageTag)

/** "48 min" or "1 h 05 min". */
@Composable
fun durationText(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return if (minutes < 60) {
        stringResource(Res.string.value_duration_minutes, minutes)
    } else {
        stringResource(Res.string.value_duration_hours, minutes / 60, (minutes % 60).toString().padStart(2, '0'))
    }
}

@Composable
fun MeasurementUnit.label(): String = when (this) {
    MeasurementUnit.KG -> stringResource(Res.string.unit_kg)
    MeasurementUnit.CM -> stringResource(Res.string.unit_cm)
}
