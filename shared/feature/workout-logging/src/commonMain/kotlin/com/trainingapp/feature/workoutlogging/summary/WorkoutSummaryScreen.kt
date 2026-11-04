package com.trainingapp.feature.workoutlogging.summary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.designsystem.component.BottomActionBar
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.GroupedSurface
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.RowDivider
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.plan.SetInput
import com.trainingapp.domain.workout.ExerciseSummary
import com.trainingapp.domain.workout.WorkoutLogReducer
import com.trainingapp.feature.workoutlogging.WorkoutSummaryDestination
import com.trainingapp.feature.workoutlogging.common.WorkoutFormats
import com.trainingapp.feature.workoutlogging.common.durationText
import com.trainingapp.feature.workoutlogging.common.valueText
import com.trainingapp.feature.workoutlogging.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkoutSummaryRoute(
    destination: WorkoutSummaryDestination,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: WorkoutSummaryViewModel = koinViewModel(key = destination.toString()) { parametersOf(destination) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> if (event == WorkoutSummaryEvent.Saved) currentOnSaved() }
    }
    WorkoutSummaryScreen(state, onBack, viewModel::onNoteChange, viewModel::save, viewModel::load)
}

/** A2 "Podsumowanie treningu": what was done, planned vs done, records, a note and one "Zapisz trening" action. */
@Composable
fun WorkoutSummaryScreen(
    state: WorkoutSummaryUiState,
    onBack: () -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = { DetailHeader(stringResource(Res.string.summary_title), onBack = onBack, backEnabled = !state.saving) },
        bottomBar = {
            if (state.status == SummaryStatus.Content && !state.readOnly) {
                BottomActionBar {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        PrimaryButton(stringResource(Res.string.summary_save), onClick = onSave, loading = state.saving, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(Res.string.summary_sync_note), style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
                    }
                }
            }
        },
    ) { padding ->
        when (state.status) {
            SummaryStatus.Loading -> LoadingState(Modifier.padding(padding))
            SummaryStatus.NotFound -> ErrorState(stringResource(Res.string.summary_not_found), Modifier.padding(padding), onRetry = onRetry)
            SummaryStatus.Content -> SummaryContent(state, onNoteChange, Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun SummaryContent(state: WorkoutSummaryUiState, onNoteChange: (String) -> Unit, modifier: Modifier) {
    val log = state.log ?: return
    val summary = state.summary ?: return
    val colors = AppTheme.colors
    val lang = state.languageTag
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        item(key = "greeting") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    if (state.clientFirstName.isNotBlank()) stringResource(Res.string.summary_greeting, state.clientFirstName) else stringResource(Res.string.summary_greeting_plain),
                    style = AppTheme.type.screenTitle,
                    color = colors.text,
                    modifier = Modifier.semantics { heading() },
                )
                val date = LocaleFormats.formatDate(log.startedAtEpochMs, lang)
                Text(
                    listOfNotNull(log.name, date, log.place).joinToString(" · "),
                    style = AppTheme.type.compactBody,
                    color = colors.textSecondary,
                )
                if (state.readOnly) StatusChip(stringResource(Res.string.summary_saved_on_device), tone = StatusTone.Success, icon = Icons.Outlined.CheckCircle)
            }
        }
        item(key = "stats") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                StatTile(stringResource(Res.string.summary_stat_time), durationText(summary.durationMs), Modifier.weight(1f))
                StatTile(
                    stringResource(Res.string.summary_stat_volume),
                    stringResource(Res.string.value_volume, LocaleFormats.formatInteger(summary.volumeGrams / 1000, lang)),
                    Modifier.weight(1f),
                )
                StatTile(
                    stringResource(Res.string.summary_stat_sets),
                    if (summary.plannedSets > 0) "${summary.completedSets}/${summary.plannedSets}" else "${summary.completedSets}",
                    Modifier.weight(1f),
                )
            }
        }
        if (state.records.isNotEmpty()) {
            item(key = "records") {
                Surface(shape = AppShapes.panel, color = colors.softAccent, contentColor = colors.onSoftAccent) {
                    Row(Modifier.fillMaxWidth().padding(Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.EmojiEvents, contentDescription = null, modifier = Modifier.size(24.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(stringResource(Res.string.summary_record_title), style = AppTheme.type.bodyStrong)
                            state.records.forEach { record ->
                                Text(
                                    stringResource(Res.string.summary_record_line, state.exerciseName(record.exerciseId), record.set.valueText(lang)),
                                    style = AppTheme.type.compactBody,
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "exercises-title") {
            Text(stringResource(Res.string.summary_exercises), style = AppTheme.type.sectionTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        }
        item(key = "exercises") {
            GroupedSurface {
                summary.exercises.forEachIndexed { index, exercise ->
                    if (index > 0) RowDivider()
                    ExerciseRow(state, exercise)
                }
            }
        }
        item(key = "note") {
            if (state.readOnly) {
                if (log.note.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(Res.string.summary_note_label), style = AppTheme.type.label, color = colors.textSecondary)
                        Text(log.note, style = AppTheme.type.body, color = colors.text)
                    }
                }
            } else {
                AppTextField(
                    value = state.note,
                    onValueChange = onNoteChange,
                    label = stringResource(Res.string.summary_note_label),
                    supportingText = stringResource(Res.string.summary_note_counter, state.note.length, WorkoutLogReducer.NOTE_MAX_LENGTH),
                    singleLine = false,
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
        if (state.saveFailed) {
            item(key = "error") { InlineMessage(stringResource(Res.string.summary_save_failed), tone = MessageTone.Error) }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = AppShapes.card, color = AppTheme.colors.surface, border = BorderStroke(1.dp, AppTheme.colors.outline)) {
        Column(Modifier.padding(Spacing.m).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(label, style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
            Text(value, style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
        }
    }
}

@Composable
private fun ExerciseRow(state: WorkoutSummaryUiState, exercise: ExerciseSummary) {
    val colors = AppTheme.colors
    val lang = state.languageTag
    Column(Modifier.fillMaxWidth().padding(Spacing.l).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(state.exerciseName(exercise.exerciseId), style = AppTheme.type.bodyStrong, color = colors.text)
        val percent = exercise.planPercent
        val progress = if (percent != null) {
            stringResource(Res.string.summary_sets_vs_plan, exercise.completedSets, exercise.plannedSets, "$percent%")
        } else {
            stringResource(Res.string.summary_sets_done, exercise.completedSets)
        }
        Text(progress, style = AppTheme.type.compactBody, color = colors.textSecondary)
        val details = listOfNotNull(
            exercise.maxWeightGrams?.let { stringResource(Res.string.summary_max, WorkoutFormats.decimal(it, lang)) },
            exercise.maxRpe?.let { stringResource(Res.string.summary_rpe, SetInput.formatRpe(it, LocaleFormats.decimalSeparator(lang))) },
        )
        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = AppTheme.type.compactBody, color = colors.textSecondary)
    }
}

