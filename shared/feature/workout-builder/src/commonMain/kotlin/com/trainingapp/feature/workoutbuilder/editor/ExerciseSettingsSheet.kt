package com.trainingapp.feature.workoutbuilder.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.trainingapp.contract.plan.PlanLimits
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppFilterChip
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.resources.ds_done
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.plan.DraftExercise
import com.trainingapp.domain.plan.SetInput
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.settings_note
import com.trainingapp.feature.workoutbuilder.resources.settings_note_supporting
import com.trainingapp.feature.workoutbuilder.resources.settings_rest
import com.trainingapp.feature.workoutbuilder.resources.settings_rest_none
import com.trainingapp.feature.workoutbuilder.resources.settings_rpe
import com.trainingapp.feature.workoutbuilder.resources.settings_rpe_supporting
import com.trainingapp.feature.workoutbuilder.resources.settings_tempo
import com.trainingapp.feature.workoutbuilder.resources.settings_tempo_supporting
import com.trainingapp.feature.workoutbuilder.resources.settings_title
import org.jetbrains.compose.resources.stringResource
import com.trainingapp.designsystem.resources.Res as DsRes

/** Rest presets in seconds. `null` is "no rest". */
private val RestPresets = listOf(null, 30, 60, 90, 120, 150, 180, 240, 300)

data class ExerciseSettings(val restSeconds: Int?, val rpeEnabled: Boolean, val tempo: String, val note: String)

/** S6: settings for one exercise in the plan. Changes apply with "Gotowe". */
@Composable
fun ExerciseSettingsSheet(
    planned: DraftExercise,
    exerciseName: String,
    onApply: (ExerciseSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var rest by rememberSaveable(planned.id) { mutableStateOf(planned.restSeconds) }
    var rpe by rememberSaveable(planned.id) { mutableStateOf(planned.rpeEnabled) }
    var tempo by rememberSaveable(planned.id) { mutableStateOf(planned.tempo) }
    var note by rememberSaveable(planned.id) { mutableStateOf(planned.note) }
    // Presets plus the stored value, if it is not a preset.
    val options = (RestPresets + listOfNotNull(planned.restSeconds)).distinct()

    AppBottomSheet(onDismiss = onDismiss, title = stringResource(Res.string.settings_title)) {
        Text(exerciseName, style = AppTheme.type.body, color = AppTheme.colors.textSecondary)

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(Res.string.settings_rest), style = AppTheme.type.bodyStrong)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                options.forEach { seconds ->
                    AppFilterChip(
                        label = seconds?.let { SetInput.formatDuration(it) } ?: stringResource(Res.string.settings_rest_none),
                        selected = rest == seconds,
                        onClick = { rest = seconds },
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.minTouchTarget)
                .toggleable(value = rpe, role = Role.Switch, onValueChange = { rpe = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.settings_rpe), style = AppTheme.type.bodyStrong)
                Text(stringResource(Res.string.settings_rpe_supporting), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
            }
            Switch(
                checked = rpe,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = AppTheme.colors.brandStrong),
            )
        }

        AppTextField(
            value = tempo,
            onValueChange = { tempo = it.take(PlanLimits.TEMPO_MAX) },
            label = stringResource(Res.string.settings_tempo),
            supportingText = stringResource(Res.string.settings_tempo_supporting),
        )
        AppTextField(
            value = note,
            onValueChange = { note = it.take(PlanLimits.NOTE_MAX) },
            label = stringResource(Res.string.settings_note),
            supportingText = stringResource(Res.string.settings_note_supporting),
            singleLine = false,
            minLines = 2,
        )
        PrimaryButton(
            text = stringResource(DsRes.string.ds_done),
            onClick = { onApply(ExerciseSettings(rest, rpe, tempo, note)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
