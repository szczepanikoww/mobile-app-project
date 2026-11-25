package com.trainingapp.feature.workoutbuilder.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.trainingapp.contract.plan.PlanLimits
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppFilterChip
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.resources.ds_done
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.plan.SetInput
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.rest_sheet_default
import com.trainingapp.feature.workoutbuilder.resources.rest_sheet_error_number
import com.trainingapp.feature.workoutbuilder.resources.rest_sheet_error_range
import com.trainingapp.feature.workoutbuilder.resources.rest_sheet_error_seconds
import com.trainingapp.feature.workoutbuilder.resources.rest_sheet_minutes
import com.trainingapp.feature.workoutbuilder.resources.rest_sheet_seconds
import com.trainingapp.feature.workoutbuilder.resources.rest_sheet_title
import org.jetbrains.compose.resources.stringResource
import com.trainingapp.designsystem.resources.Res as DsRes

/** Rest presets in seconds, shared by the rest sheet and the exercise settings. */
internal val RestPresetSeconds = listOf(30, 60, 90, 120, 150, 180, 240, 300)

/**
 * Edits one rest block between two sets as minutes and seconds. [currentRest] is the draft text ("1:30"; blank uses
 * the exercise's rest, [defaultSeconds]). [onApply] receives the new text: "m:ss", or blank for the default.
 */
@Composable
fun RestSheet(
    sheetKey: String,
    setLabel: String,
    currentRest: String,
    defaultSeconds: Int,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = (SetInput.parseDurationSeconds(currentRest) as? SetInput.Parsed.Value)?.value
    val initial = current ?: defaultSeconds
    var useDefault by rememberSaveable(sheetKey) { mutableStateOf(currentRest.isBlank()) }
    var minutes by rememberSaveable(sheetKey) { mutableStateOf("${initial / 60}") }
    var seconds by rememberSaveable(sheetKey) { mutableStateOf("${initial % 60}".padStart(2, '0')) }
    var showErrors by rememberSaveable(sheetKey) { mutableStateOf(false) }

    fun select(total: Int, isDefault: Boolean) {
        minutes = "${total / 60}"
        seconds = "${total % 60}".padStart(2, '0')
        useDefault = isDefault
        showErrors = false
    }

    val minutesValue = minutes.trim().takeIf { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }?.toInt()
        ?: if (minutes.isBlank()) 0 else null
    val secondsValue = seconds.trim().takeIf { it.isNotEmpty() && it.length <= 2 && it.all(Char::isDigit) }?.toInt()
        ?: if (seconds.isBlank()) 0 else null
    val total = if (minutesValue != null && secondsValue != null && secondsValue < 60) minutesValue * 60 + secondsValue else null

    val numberError = stringResource(Res.string.rest_sheet_error_number)
    val minutesError = if (minutesValue == null) numberError else null
    val secondsError = when {
        secondsValue == null -> numberError
        secondsValue >= 60 -> stringResource(Res.string.rest_sheet_error_seconds)
        else -> null
    }
    val rangeError = if (total != null && total !in PlanLimits.REST_SECONDS) {
        stringResource(Res.string.rest_sheet_error_range, SetInput.formatDuration(PlanLimits.REST_SECONDS.last))
    } else {
        null
    }

    AppBottomSheet(onDismiss = onDismiss, title = stringResource(Res.string.rest_sheet_title)) {
        Text(setLabel, style = AppTheme.type.body, color = AppTheme.colors.textSecondary)

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Top) {
            val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
            AppTextField(
                value = minutes,
                onValueChange = { minutes = it.filter(Char::isDigit).take(3); useDefault = false },
                label = stringResource(Res.string.rest_sheet_minutes),
                error = minutesError.takeIf { showErrors },
                keyboardOptions = numberKeyboard,
                modifier = Modifier.weight(1f),
            )
            AppTextField(
                value = seconds,
                onValueChange = { seconds = it.filter(Char::isDigit).take(2); useDefault = false },
                label = stringResource(Res.string.rest_sheet_seconds),
                error = secondsError.takeIf { showErrors },
                keyboardOptions = numberKeyboard.copy(imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
        }
        if (showErrors && rangeError != null) {
            Text(rangeError, style = AppTheme.type.compactBody, color = AppTheme.colors.onErrorContainer)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            AppFilterChip(
                label = stringResource(Res.string.rest_sheet_default, SetInput.formatDuration(defaultSeconds)),
                selected = useDefault,
                onClick = { select(defaultSeconds, isDefault = true) },
            )
            RestPresetSeconds.forEach { preset ->
                AppFilterChip(
                    label = SetInput.formatDuration(preset),
                    selected = !useDefault && total == preset,
                    onClick = { select(preset, isDefault = false) },
                )
            }
        }

        PrimaryButton(
            text = stringResource(DsRes.string.ds_done),
            onClick = {
                when {
                    useDefault -> onApply("")
                    total == null || rangeError != null -> showErrors = true
                    else -> onApply(SetInput.formatDuration(total))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
