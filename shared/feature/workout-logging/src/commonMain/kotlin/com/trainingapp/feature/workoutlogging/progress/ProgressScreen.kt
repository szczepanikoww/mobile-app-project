package com.trainingapp.feature.workoutlogging.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.designsystem.component.ChartPoint
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.GroupedSurface
import com.trainingapp.designsystem.component.LineChart
import com.trainingapp.designsystem.component.ListRow
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.RowDivider
import com.trainingapp.designsystem.component.SecondaryButton
import com.trainingapp.designsystem.component.SegmentedTabs
import com.trainingapp.designsystem.component.SelectorField
import com.trainingapp.designsystem.component.SheetOption
import com.trainingapp.designsystem.component.SingleChoiceSheet
import com.trainingapp.designsystem.component.TopLevelHeader
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.workout.BodyMeasurement
import com.trainingapp.domain.workout.MeasurementKind
import com.trainingapp.domain.workout.ProgressPoint
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutSummary
import com.trainingapp.feature.workoutlogging.common.WorkoutFormats
import com.trainingapp.feature.workoutlogging.common.durationText
import com.trainingapp.feature.workoutlogging.common.label
import com.trainingapp.feature.workoutlogging.common.valueText
import com.trainingapp.feature.workoutlogging.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * A3 with its view model. It is the content of the client's "Postępy" tab: the client shell owns the bottom
 * navigation. With [onBack] (the temporary preview entry) it gets a back arrow instead of the top-level header.
 */
@Composable
fun ProgressRoute(
    onOpenWorkout: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: ProgressViewModel = koinViewModel(),
    /** False as a tab of the client shell, which owns the top bar (B0). */
    showHeader: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProgressScreen(
        showHeader = showHeader,
        state = state,
        actions = ProgressActions(
            onBack = onBack,
            onTab = viewModel::onTab,
            onSelectExercise = viewModel::onSelectExercise,
            onOpenWorkout = onOpenWorkout,
            onAddMeasurement = viewModel::onAddMeasurement,
            onDismissMeasurement = viewModel::onDismissMeasurement,
            onMeasurementKind = viewModel::onMeasurementKind,
            onMeasurementText = viewModel::onMeasurementText,
            onSaveMeasurement = viewModel::onSaveMeasurement,
            onRetry = viewModel::retry,
        ),
    )
}

data class ProgressActions(
    val onBack: (() -> Unit)? = null,
    val onTab: (ProgressTab) -> Unit = {},
    val onSelectExercise: (String) -> Unit = {},
    val onOpenWorkout: (String) -> Unit = {},
    val onAddMeasurement: () -> Unit = {},
    val onDismissMeasurement: () -> Unit = {},
    val onMeasurementKind: (MeasurementKind) -> Unit = {},
    val onMeasurementText: (String) -> Unit = {},
    val onSaveMeasurement: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

@Composable
fun ProgressScreen(state: ProgressUiState, actions: ProgressActions, showHeader: Boolean = true) {
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            if (showHeader) {
                val title = stringResource(Res.string.progress_title)
                val onBack = actions.onBack
                if (onBack != null) DetailHeader(title, onBack = onBack) else TopLevelHeader(title)
            }
        },
    ) { padding ->
        when (state.status) {
            ProgressStatus.Loading -> LoadingState(Modifier.padding(padding))
            ProgressStatus.Error -> ErrorState(stringResource(Res.string.progress_load_error), Modifier.padding(padding), onRetry = actions.onRetry)
            ProgressStatus.Content -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                item(key = "tabs") {
                    SegmentedTabs(
                        options = ProgressTab.entries.map { stringResource(it.labelRes) },
                        selectedIndex = state.tab.ordinal,
                        onSelect = { actions.onTab(ProgressTab.entries[it]) },
                    )
                }
                when (state.tab) {
                    ProgressTab.Exercises -> exercisesTab(state, actions)
                    ProgressTab.Measurements -> measurementsTab(state, actions)
                    ProgressTab.History -> historyTab(state, actions)
                    ProgressTab.Records -> recordsTab(state)
                }
            }
        }
    }
    state.form?.let { MeasurementSheet(it, state.languageTag, actions) }
}

private val ProgressTab.labelRes: StringResource
    get() = when (this) {
        ProgressTab.Exercises -> Res.string.progress_tab_exercises
        ProgressTab.Measurements -> Res.string.progress_tab_measurements
        ProgressTab.History -> Res.string.progress_tab_history
        ProgressTab.Records -> Res.string.progress_tab_records
    }

private fun LazyListScope.noWorkoutsYet() = item(key = "empty") {
    EmptyState(
        title = stringResource(Res.string.progress_empty_title),
        message = stringResource(Res.string.progress_empty_body),
        icon = Icons.Outlined.FitnessCenter,
    )
}

private fun LazyListScope.exercisesTab(state: ProgressUiState, actions: ProgressActions) {
    val selected = state.selectedExerciseId
    if (selected == null) {
        noWorkoutsYet()
        return
    }
    item(key = "exercise-picker") {
        var picking by rememberSaveable { mutableStateOf(false) }
        SelectorField(
            label = stringResource(Res.string.progress_exercise),
            value = state.exerciseName(selected),
            placeholder = stringResource(Res.string.progress_exercise),
            onClick = { picking = true },
        )
        if (picking) {
            SingleChoiceSheet(
                title = stringResource(Res.string.progress_exercise),
                options = state.loggedExerciseIds.map { SheetOption(it, state.exerciseName(it)) },
                selected = selected,
                onSelect = { picking = false; actions.onSelectExercise(it) },
                onDismiss = { picking = false },
            )
        }
    }
    item(key = "chart") { ChartCard(state) }
    item(key = "points-title") {
        Text(stringResource(Res.string.progress_best_sets), style = AppTheme.type.sectionTitle, color = AppTheme.colors.text, modifier = Modifier.semantics { heading() })
    }
    item(key = "points") {
        GroupedSurface {
            state.points.reversed().forEachIndexed { index, point ->
                if (index > 0) RowDivider()
                ListRow(
                    title = point.set.valueText(state.languageTag),
                    supporting = LocaleFormats.formatDate(point.atEpochMs, state.languageTag),
                    onClick = { actions.onOpenWorkout(point.workoutId) },
                )
            }
        }
    }
}

@Composable
private fun ChartCard(state: ProgressUiState) {
    val lang = state.languageTag
    val points = state.points
    val first = points.firstOrNull()
    val last = points.lastOrNull()
    val trackingType = last?.performance?.trackingType
    val chart = points.map { ChartPoint(chartValue(it), LocaleFormats.formatShortDate(it.atEpochMs, lang)) }
    val summary = if (first != null && last != null) {
        stringResource(Res.string.progress_chart_summary, state.exerciseName(last.exerciseId), first.set.valueText(lang), last.set.valueText(lang), points.size)
    } else {
        stringResource(Res.string.progress_chart_empty)
    }
    Surface(shape = AppShapes.featureCard, color = AppTheme.colors.surface, border = BorderStroke(1.dp, AppTheme.colors.outline)) {
        Column(Modifier.fillMaxWidth().padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(if (trackingType == TrackingType.WEIGHT_REPS || trackingType == null) Res.string.progress_metric_best_set else Res.string.progress_metric_best),
                style = AppTheme.type.label,
                color = AppTheme.colors.textSecondary,
            )
            if (last != null) Text(last.set.valueText(lang), style = AppTheme.type.statValue, color = AppTheme.colors.text)
            LineChart(points = chart, summary = summary)
        }
    }
}

/** Weight for weight × reps (reps break ties visually only through the label); otherwise the tracked value. */
private fun chartValue(point: ProgressPoint): Float = when (point.performance.trackingType) {
    TrackingType.WEIGHT_REPS -> point.performance.primary / 1000f
    TrackingType.DISTANCE_TIME -> point.performance.primary / 1000f
    TrackingType.REPS, TrackingType.TIME -> point.performance.primary.toFloat()
}

private fun LazyListScope.measurementsTab(state: ProgressUiState, actions: ProgressActions) {
    item(key = "add-measurement") {
        SecondaryButton(stringResource(Res.string.progress_add_measurement), onClick = actions.onAddMeasurement, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth())
    }
    if (state.measurements.isEmpty()) {
        item(key = "measurements-empty") {
            EmptyState(
                title = stringResource(Res.string.progress_measurements_empty_title),
                message = stringResource(Res.string.progress_measurements_empty_body),
                icon = Icons.Outlined.MonitorWeight,
            )
        }
        return
    }
    MeasurementKind.entries.forEach { kind ->
        val entries = state.measurements.filter { it.kind == kind }
        if (entries.isEmpty()) return@forEach
        item(key = "measurement-${kind.name}") {
            MeasurementGroup(kind, entries, state.languageTag)
        }
    }
}

@Composable
private fun MeasurementGroup(kind: MeasurementKind, entries: List<BodyMeasurement>, lang: String) {
    val unit = kind.unit.label()
    val latest = entries.first()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(kind.labelRes), style = AppTheme.type.sectionTitle, color = AppTheme.colors.text, modifier = Modifier.weight(1f).semantics { heading() })
            Text("${WorkoutFormats.decimal(latest.valueThousandths, lang)} $unit", style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
        }
        if (entries.size > 1) {
            val chronological = entries.reversed()
            LineChart(
                points = chronological.map { ChartPoint(it.valueThousandths / 1000f, LocaleFormats.formatShortDate(it.measuredAtEpochMs, lang)) },
                summary = stringResource(
                    Res.string.progress_measurement_summary,
                    stringResource(kind.labelRes),
                    "${WorkoutFormats.decimal(chronological.first().valueThousandths, lang)} $unit",
                    "${WorkoutFormats.decimal(latest.valueThousandths, lang)} $unit",
                    entries.size,
                ),
                height = 96.dp,
            )
        }
        GroupedSurface {
            entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                ListRow(
                    title = "${WorkoutFormats.decimal(entry.valueThousandths, lang)} $unit",
                    supporting = LocaleFormats.formatDate(entry.measuredAtEpochMs, lang),
                )
            }
        }
    }
}

private val MeasurementKind.labelRes: StringResource
    get() = when (this) {
        MeasurementKind.BODY_WEIGHT -> Res.string.measurement_body_weight
        MeasurementKind.WAIST -> Res.string.measurement_waist
        MeasurementKind.HIPS -> Res.string.measurement_hips
    }

private fun LazyListScope.historyTab(state: ProgressUiState, actions: ProgressActions) {
    if (state.history.isEmpty()) {
        noWorkoutsYet()
        return
    }
    item(key = "history") {
        GroupedSurface {
            state.history.forEachIndexed { index, log ->
                if (index > 0) RowDivider()
                HistoryRow(log, state.languageTag) { actions.onOpenWorkout(log.id) }
            }
        }
    }
}

@Composable
private fun HistoryRow(log: WorkoutLog, lang: String, onClick: () -> Unit) {
    val summary = WorkoutSummary.of(log, log.finishedAtEpochMs ?: log.startedAtEpochMs)
    ListRow(
        title = log.name,
        supporting = listOf(
            LocaleFormats.formatDate(log.startedAtEpochMs, lang),
            durationText(summary.durationMs),
            stringResource(Res.string.progress_history_sets, summary.completedSets),
        ).joinToString(" · "),
        onClick = onClick,
        onClickLabel = stringResource(Res.string.progress_open_workout),
    )
}

private fun LazyListScope.recordsTab(state: ProgressUiState) {
    if (state.records.isEmpty()) {
        noWorkoutsYet()
        return
    }
    item(key = "records-hint") {
        Text(stringResource(Res.string.progress_records_hint), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
    }
    item(key = "records") {
        GroupedSurface {
            state.records.forEachIndexed { index, record ->
                if (index > 0) RowDivider()
                ListRow(
                    title = state.exerciseName(record.exerciseId),
                    supporting = "${record.set.valueText(state.languageTag)} · ${LocaleFormats.formatDate(record.atEpochMs, state.languageTag)}",
                )
            }
        }
    }
}

@Composable
private fun MeasurementSheet(form: MeasurementForm, lang: String, actions: ProgressActions) {
    AppBottomSheet(onDismiss = actions.onDismissMeasurement, title = stringResource(Res.string.progress_add_measurement)) {
        SegmentedTabs(
            options = MeasurementKind.entries.map { stringResource(it.labelRes) },
            selectedIndex = form.kind.ordinal,
            onSelect = { actions.onMeasurementKind(MeasurementKind.entries[it]) },
        )
        AppTextField(
            value = form.text,
            onValueChange = actions.onMeasurementText,
            label = stringResource(Res.string.progress_measurement_field, stringResource(form.kind.labelRes), form.kind.unit.label()),
            error = form.error?.let { stringResource(it.messageRes) },
            supportingText = stringResource(Res.string.progress_measurement_private),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        PrimaryButton(stringResource(Res.string.progress_measurement_save), onClick = actions.onSaveMeasurement, loading = form.saving, modifier = Modifier.fillMaxWidth())
    }
}

private val MeasurementError.messageRes: StringResource
    get() = when (this) {
        MeasurementError.Required -> Res.string.progress_measurement_required
        MeasurementError.Invalid -> Res.string.progress_measurement_invalid
        MeasurementError.OutOfRange -> Res.string.progress_measurement_range
        MeasurementError.SaveFailed -> Res.string.progress_measurement_save_failed
    }
