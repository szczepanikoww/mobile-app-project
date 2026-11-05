package com.trainingapp.feature.workoutlogging.logger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.designsystem.component.ActionRow
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.BottomActionBar
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MenuAction
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.OverflowMenu
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SecondaryButton
import com.trainingapp.designsystem.component.SetCell
import com.trainingapp.designsystem.component.SetRow
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.domain.media.VideoProblem
import com.trainingapp.domain.workout.LoggedExercise
import com.trainingapp.domain.workout.RestTimer
import com.trainingapp.domain.workout.SetField
import com.trainingapp.domain.workout.SetLog
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.setFields
import com.trainingapp.feature.workoutbuilder.common.ExerciseImage
import com.trainingapp.feature.workoutbuilder.common.muscleAndEquipment
import com.trainingapp.feature.workoutbuilder.media.DemoVideoCard
import com.trainingapp.feature.workoutbuilder.media.rememberVideoPicker
import com.trainingapp.feature.workoutlogging.common.TechniqueVideoStatus
import com.trainingapp.feature.workoutlogging.common.WorkoutFormats
import com.trainingapp.feature.workoutlogging.common.rememberNowEpochMs
import com.trainingapp.feature.workoutlogging.common.setValueText
import com.trainingapp.feature.workoutlogging.common.valueText
import com.trainingapp.feature.workoutlogging.resources.*
import com.trainingapp.feature.workoutlogging.rest.rememberNotificationPermission
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** A1 with its view model. [onOpenSummary] opens A2 for the ended workout; [onExit] leaves after a discard. */
@Composable
fun LiveWorkoutRoute(
    onBack: () -> Unit,
    onOpenSummary: (String) -> Unit,
    onExit: () -> Unit,
    viewModel: LiveWorkoutViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission = rememberNotificationPermission()
    val currentOnOpenSummary by rememberUpdatedState(onOpenSummary)
    val currentOnExit by rememberUpdatedState(onExit)
    val picker = rememberVideoPicker(viewModel::onVideoPicked)

    LaunchedEffect(Unit) { viewModel.onScreenShown() }
    LaunchedEffect(permission.granted) { viewModel.onNotificationPermissionKnown(permission.granted) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LiveWorkoutEvent.OpenSummary -> currentOnOpenSummary(event.workoutLogId)
                LiveWorkoutEvent.Exit -> currentOnExit()
                LiveWorkoutEvent.RequestNotificationPermission -> permission.request(viewModel::onNotificationPermissionResult)
            }
        }
    }

    LiveWorkoutScreen(
        state = state,
        nowEpochMs = rememberNowEpochMs(TimeSource.System),
        actions = LiveWorkoutActions(
            onBack = onBack,
            onRetry = viewModel::load,
            onStart = viewModel::start,
            onFieldChange = viewModel::onFieldChange,
            onToggleSet = viewModel::onToggleSet,
            onAddSet = viewModel::onAddSet,
            onRemoveSet = viewModel::onRemoveSet,
            onSelectExercise = viewModel::onSelectExercise,
            onAdjustRest = viewModel::onAdjustRest,
            onSkipRest = viewModel::onSkipRest,
            onRestEnded = viewModel::onRestEnded,
            onFinish = viewModel::onFinishClick,
            onConfirmFinish = viewModel::confirmFinish,
            onDiscard = viewModel::onDiscardClick,
            onConfirmDiscard = viewModel::confirmDiscard,
            onRecordTechnique = viewModel::onRecordTechniqueClick,
            onRecordVideo = { viewModel.dismissDialog(); picker.record() },
            onPickVideo = { viewModel.dismissDialog(); picker.pickFromGallery() },
            canRecordVideo = picker.canRecord,
            onRetryVideo = viewModel::onRetryVideo,
            onDismissDialog = viewModel::dismissDialog,
            onDismissMessage = viewModel::dismissMessage,
            onAcceptNotificationRationale = viewModel::onNotificationRationaleAccepted,
            onOpenNotificationSettings = { viewModel.dismissMessage(); permission.openSettings() },
        ),
    )
}

/** Everything the screen can ask for. Grouped so previews and tests pass one object. */
data class LiveWorkoutActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onFieldChange: (String, SetField, String) -> Unit = { _, _, _ -> },
    val onToggleSet: (String) -> Unit = {},
    val onAddSet: (String) -> Unit = {},
    val onRemoveSet: (String) -> Unit = {},
    val onSelectExercise: (Int) -> Unit = {},
    val onAdjustRest: (Int) -> Unit = {},
    val onSkipRest: () -> Unit = {},
    val onRestEnded: () -> Unit = {},
    val onFinish: () -> Unit = {},
    val onConfirmFinish: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onConfirmDiscard: () -> Unit = {},
    val onRecordTechnique: () -> Unit = {},
    val onRecordVideo: () -> Unit = {},
    val onPickVideo: () -> Unit = {},
    val canRecordVideo: Boolean = true,
    val onRetryVideo: (String) -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onAcceptNotificationRationale: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
)

/** A1 "Trening na żywo": state in, actions out, so it renders in previews without services. */
@Composable
fun LiveWorkoutScreen(state: LiveWorkoutUiState, nowEpochMs: Long, actions: LiveWorkoutActions) {
    val log = state.log
    var showExerciseList by rememberSaveable { mutableStateOf(false) }
    var infoExerciseId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            DetailHeader(
                title = stringResource(Res.string.live_title),
                onBack = actions.onBack,
                actions = {
                    if (log != null) {
                        OverflowMenu(
                            listOf(
                                MenuAction(stringResource(Res.string.live_exercise_list), Icons.AutoMirrored.Outlined.FormatListBulleted) { showExerciseList = true },
                                MenuAction(stringResource(Res.string.live_discard), Icons.Outlined.DeleteOutline, destructive = true, onClick = actions.onDiscard),
                            ),
                        )
                    }
                },
            )
        },
        bottomBar = {
            val rest = log?.rest
            when {
                state.phase == LivePhase.NotStarted -> BottomActionBar {
                    PrimaryButton(stringResource(Res.string.live_start), onClick = actions.onStart, loading = state.starting, modifier = Modifier.fillMaxWidth())
                }
                rest != null -> RestBar(rest, nowEpochMs, actions)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.phase) {
                LivePhase.Loading -> LoadingState()
                LivePhase.LoadFailed -> ErrorState(stringResource(Res.string.live_load_error), onRetry = actions.onRetry)
                LivePhase.NoPlan -> EmptyState(
                    title = stringResource(Res.string.live_no_plan_title),
                    message = stringResource(Res.string.live_no_plan_body),
                    actionLabel = stringResource(Res.string.live_refresh),
                    onAction = actions.onRetry,
                )
                LivePhase.NotStarted -> NotStartedContent(state)
                LivePhase.Active -> if (log != null) {
                    ActiveContent(state, log, nowEpochMs, actions, onShowInfo = { infoExerciseId = it }, onShowList = { showExerciseList = true })
                }
            }
        }
    }

    if (showExerciseList && log != null) {
        AppBottomSheet(onDismiss = { showExerciseList = false }, title = stringResource(Res.string.live_exercise_list)) {
            log.exercises.forEachIndexed { index, exercise ->
                val done = exercise.completedSets.size
                ActionRow(
                    icon = if (done == exercise.sets.size && done > 0) Icons.Outlined.CheckCircle else Icons.Outlined.Timer,
                    title = state.exerciseName(exercise.exerciseId),
                    subtitle = stringResource(Res.string.live_sets_done, done, exercise.sets.size),
                    onClick = {
                        showExerciseList = false
                        actions.onSelectExercise(index)
                    },
                )
            }
        }
    }
    infoExerciseId?.let { id ->
        ExerciseInfoSheet(state.exercises[id], state.languageTag, onDismiss = { infoExerciseId = null })
    }
    Dialogs(state, actions)
}

@Composable
private fun NotStartedContent(state: LiveWorkoutUiState) {
    val today = state.today ?: return
    val day = today.day
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(Res.string.live_today), style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
                Text(day.name.ifBlank { today.planVersion.body.name }, style = AppTheme.type.screenTitle, color = AppTheme.colors.text, modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(Res.string.live_plan_meta, today.planVersion.body.name, day.exercises.size, day.exercises.sumOf { it.sets.size }),
                    style = AppTheme.type.compactBody,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }
        items(day.exercises, key = { it.id }) { planned ->
            val exercise = state.exercises[planned.exerciseId]
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                ExerciseImage(exercise, size = Sizes.thumbnail)
                Column(Modifier.weight(1f)) {
                    Text(state.exerciseName(planned.exerciseId), style = AppTheme.type.bodyStrong, color = AppTheme.colors.text)
                    val prev = state.previous[planned.exerciseId]
                    Text(
                        if (prev != null) stringResource(Res.string.live_previous, prev.valueText(state.languageTag)) else pluralStringResource(Res.plurals.live_set_count, planned.sets.size, planned.sets.size),
                        style = AppTheme.type.compactBody,
                        color = AppTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveContent(
    state: LiveWorkoutUiState,
    log: WorkoutLog,
    nowEpochMs: Long,
    actions: LiveWorkoutActions,
    onShowInfo: (String) -> Unit,
    onShowList: () -> Unit,
) {
    val exercise = log.currentExercise
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.l, bottom = Spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item(key = "header") { WorkoutHeader(state, log, nowEpochMs, actions.onFinish) }
        state.message?.let { message -> item(key = "message") { MessageBanner(message, actions) } }
        if (exercise == null) {
            item(key = "empty") { EmptyState(stringResource(Res.string.live_no_exercises), message = null) }
        } else {
            item(key = "exercise-${exercise.id}") {
                ExerciseCard(state, log, exercise, onShowInfo, onShowList, actions)
            }
            item(key = "sets-title") {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.live_sets_title), style = AppTheme.type.sectionTitle, color = AppTheme.colors.text, modifier = Modifier.weight(1f).semantics { heading() })
                    if (exercise.plannedSetCount > 0) {
                        Text(pluralStringResource(Res.plurals.live_goal_sets, exercise.plannedSetCount, exercise.plannedSetCount), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                    }
                }
            }
            items(exercise.sets, key = { it.id }) { set ->
                SetEntry(state, exercise, set, isCurrent = set.id == exercise.currentSet?.id, actions)
            }
            item(key = "actions") {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    SecondaryButton(stringResource(Res.string.live_add_set), onClick = { actions.onAddSet(exercise.id) }, icon = Icons.Outlined.Add)
                    TonalButton(
                        stringResource(Res.string.live_record_technique),
                        onClick = actions.onRecordTechnique,
                        icon = Icons.Outlined.Videocam,
                        loading = state.importingVideo,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutHeader(state: LiveWorkoutUiState, log: WorkoutLog, nowEpochMs: Long, onFinish: () -> Unit) {
    val colors = AppTheme.colors
    val elapsed = (log.finishedAtEpochMs ?: nowEpochMs) - log.startedAtEpochMs
    val elapsedLabel = stringResource(Res.string.live_elapsed_description, WorkoutFormats.clock(elapsed))
    Surface(shape = AppShapes.featureCard, color = colors.surface, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)) {
        Column(Modifier.fillMaxWidth().padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Column(Modifier.weight(1f)) {
                    Text(log.name, style = AppTheme.type.sectionTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                    Text(
                        WorkoutFormats.clock(elapsed),
                        style = AppTheme.type.statValue,
                        color = colors.text,
                        modifier = Modifier.semantics { contentDescription = elapsedLabel },
                    )
                }
                PrimaryButton(stringResource(Res.string.live_finish), onClick = onFinish)
            }
            SyncLine(state)
        }
    }
}

/** "Zapisano na telefonie": local only in this phase. Pending, failed and conflict states come with sync. */
@Composable
private fun SyncLine(state: LiveWorkoutUiState) {
    val colors = AppTheme.colors
    val (icon, text, tint) = when {
        state.saveFailed -> Triple(Icons.Outlined.ErrorOutline, stringResource(Res.string.live_save_failed), colors.onErrorContainer)
        state.saving -> Triple(Icons.Outlined.Sync, stringResource(Res.string.live_saving), colors.textSecondary)
        else -> Triple(Icons.Outlined.CheckCircle, stringResource(Res.string.live_saved_on_device), colors.success)
    }
    Row(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = AppTheme.type.compactBody, color = if (state.saveFailed) colors.onErrorContainer else colors.textSecondary)
    }
}

@Composable
private fun MessageBanner(message: LiveMessage, actions: LiveWorkoutActions) {
    val (text, tone) = when (message) {
        LiveMessage.Restored -> stringResource(Res.string.live_restored) to MessageTone.Info
        LiveMessage.VideoSent -> stringResource(Res.string.live_video_sent) to MessageTone.Success
        LiveMessage.VideoKeptOnSet -> stringResource(Res.string.live_video_kept) to MessageTone.Info
        is LiveMessage.VideoRejected -> stringResource(
            when (message.problem) {
                VideoProblem.TOO_LONG -> Res.string.live_video_too_long
                VideoProblem.TOO_LARGE -> Res.string.live_video_too_large
                VideoProblem.EMPTY -> Res.string.live_video_unreadable
            },
        ) to MessageTone.Error
        LiveMessage.VideoUnreadable -> stringResource(Res.string.live_video_unreadable) to MessageTone.Error
        LiveMessage.VideoFailed -> stringResource(Res.string.live_video_failed) to MessageTone.Error
        LiveMessage.NotificationsDenied -> stringResource(Res.string.live_notifications_denied) to MessageTone.Warning
    }
    InlineMessage(text, tone = tone) {
        Row {
            if (message == LiveMessage.NotificationsDenied) {
                AppTextButton(stringResource(Res.string.live_open_settings), onClick = actions.onOpenNotificationSettings)
            }
            AppTextButton(stringResource(Res.string.live_dismiss), onClick = actions.onDismissMessage)
        }
    }
}

@Composable
private fun ExerciseCard(
    state: LiveWorkoutUiState,
    log: WorkoutLog,
    entry: LoggedExercise,
    onShowInfo: (String) -> Unit,
    onShowList: () -> Unit,
    actions: LiveWorkoutActions,
) {
    val colors = AppTheme.colors
    val exercise = state.exercises[entry.exerciseId]
    val index = log.currentExerciseIndex
    Surface(shape = AppShapes.featureCard, color = colors.surface, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)) {
        Column(Modifier.fillMaxWidth().padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            // Previous / next exercise. Stitch has no way to move between exercises; arrows and a list sheet add it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.onSelectExercise(index - 1) }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.live_previous_exercise))
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(AppShapes.pill)
                        .clickable(role = Role.Button, onClick = onShowList)
                        .padding(vertical = Spacing.s),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusChip(stringResource(Res.string.live_exercise_position, index + 1, log.exercises.size), tone = StatusTone.Accent)
                }
                IconButton(onClick = { actions.onSelectExercise(index + 1) }, enabled = index < log.exercises.lastIndex) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(Res.string.live_next_exercise))
                }
            }
            exercise?.demoMediaId?.let { mediaId ->
                // The trainer's demo video plays only when READY; DemoVideoCard shows any other state instead.
                DemoVideoCard(thumbnailPath = state.media[mediaId]?.thumbnailPath, durationMs = state.media[mediaId]?.durationMs, asset = state.media[mediaId])
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                if (exercise?.demoMediaId == null) ExerciseImage(exercise, size = Sizes.thumbnail)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(state.exerciseName(entry.exerciseId), style = AppTheme.type.sectionTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                    if (exercise != null) Text(exercise.muscleAndEquipment(), style = AppTheme.type.compactBody, color = colors.textSecondary)
                }
                IconButton(onClick = { onShowInfo(entry.exerciseId) }) {
                    Icon(Icons.Outlined.Info, stringResource(Res.string.live_exercise_info), tint = colors.textSecondary)
                }
            }
            state.previous[entry.exerciseId]?.let { previous ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    Text(stringResource(Res.string.live_previous, previous.valueText(state.languageTag)), style = AppTheme.type.compactBody, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun SetEntry(state: LiveWorkoutUiState, exercise: LoggedExercise, set: SetLog, isCurrent: Boolean, actions: LiveWorkoutActions) {
    val colors = AppTheme.colors
    val number = exercise.sets.indexOf(set) + 1
    val typeLabel = stringResource(
        when (set.type) {
            SetType.WARM_UP -> Res.string.set_type_warm_up
            SetType.WORKING -> Res.string.set_type_working
            SetType.DROP -> Res.string.set_type_drop
        },
    )
    val fields = exercise.trackingType.setFields + if (exercise.rpeEnabled) listOf(SetField.RPE) else emptyList()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(if (isCurrent) colors.surface else colors.background)
            .then(if (isCurrent) Modifier.border(2.dp, colors.brandAccent, AppShapes.card) else Modifier)
            .padding(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            StatusChip(typeLabel, tone = if (set.type == SetType.WARM_UP) StatusTone.Neutral else StatusTone.Accent)
            if (isCurrent) StatusChip(stringResource(Res.string.live_now), tone = StatusTone.Warning)
            Spacer(Modifier.weight(1f))
            set.planned?.let { planned ->
                Text(
                    stringResource(
                        Res.string.live_plan_value,
                        setValueText(planned.weightGrams, planned.repsMin, planned.durationSeconds, planned.distanceMeters, state.languageTag),
                    ),
                    style = AppTheme.type.compactBody,
                    color = if (isCurrent) colors.text else colors.textSecondary,
                )
            }
            if (set.planned == null) {
                IconButton(onClick = { actions.onRemoveSet(set.id) }) {
                    Icon(Icons.Outlined.Close, stringResource(Res.string.live_remove_set, number), tint = colors.textSecondary)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SetRow(
                setLabel = number.toString(),
                setLabelDescription = stringResource(Res.string.live_set_description, number, typeLabel),
                highlightLabel = isCurrent,
                cells = fields.map { field -> setCell(state, set, field, actions) },
                modifier = Modifier.weight(1f),
            )
            TickButton(set, number, onClick = { actions.onToggleSet(set.id) })
        }
        set.techniqueMediaId?.let { mediaId ->
            TechniqueVideoStatus(state.media[mediaId], onRetry = { actions.onRetryVideo(mediaId) }, modifier = Modifier.padding(horizontal = Spacing.xs))
        }
    }
}

@Composable
private fun setCell(state: LiveWorkoutUiState, set: SetLog, field: SetField, actions: LiveWorkoutActions): SetCell {
    val key = FieldKey(set.id, field)
    val text = state.drafts[key] ?: WorkoutFormats.fieldText(set, field, state.languageTag)
    val (description, suffix, prefix) = when (field) {
        SetField.WEIGHT -> Triple(stringResource(Res.string.field_weight), stringResource(Res.string.unit_kg), null)
        SetField.REPS -> Triple(stringResource(Res.string.field_reps), stringResource(Res.string.unit_reps), null)
        SetField.RPE -> Triple(stringResource(Res.string.field_rpe), stringResource(Res.string.unit_rpe), "@")
        SetField.DURATION -> Triple(stringResource(Res.string.field_duration), null, null)
        SetField.DISTANCE -> Triple(stringResource(Res.string.field_distance), stringResource(Res.string.unit_km), null)
    }
    return SetCell(
        value = text,
        onValueChange = { actions.onFieldChange(set.id, field, it) },
        contentDescription = description,
        prefix = prefix,
        suffix = suffix,
        accent = field == SetField.RPE,
        keyboardType = if (field == SetField.REPS) KeyboardType.Number else KeyboardType.Decimal,
        isError = key in state.invalid,
        large = field == SetField.WEIGHT || field == SetField.REPS,
        weight = if (field == SetField.RPE) 0.8f else 1f,
    )
}

@Composable
private fun TickButton(set: SetLog, number: Int, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val done = set.isCompleted
    val label = stringResource(if (done) Res.string.live_untick_set else Res.string.live_tick_set, number)
    val stateLabel = stringResource(if (done) Res.string.live_set_done else Res.string.live_set_not_done)
    Box(
        Modifier
            .size(Sizes.minTouchTarget)
            .clip(CircleShape)
            .background(if (done) colors.brandStrong else colors.subtleSurface)
            .border(1.dp, if (done) colors.brandStrong else colors.fieldOutline, CircleShape)
            .clickable(role = Role.Checkbox, onClickLabel = label, onClick = onClick)
            .semantics { stateDescription = stateLabel; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = if (done) colors.onBrandStrong else colors.textSecondary)
    }
}

/** The rest bar: time left from the stored end time, −15 s / +15 s and skip. Announces the end to screen readers. */
@Composable
private fun RestBar(rest: RestTimer, nowEpochMs: Long, actions: LiveWorkoutActions) {
    val colors = AppTheme.colors
    val remaining = rest.remainingSeconds(nowEpochMs)
    val currentOnRestEnded by rememberUpdatedState(actions.onRestEnded)
    LaunchedEffect(rest.endsAtEpochMs, remaining == 0) { if (remaining == 0) currentOnRestEnded() }
    val remainingDescription = stringResource(Res.string.live_rest_remaining_description, WorkoutFormats.clock(remaining * 1000L))
    BottomActionBar {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.softAccent), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Timer, contentDescription = null, tint = colors.onSoftAccent)
            }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = remainingDescription }) {
                Text(stringResource(Res.string.live_rest), style = AppTheme.type.label, color = colors.textSecondary)
                Text(WorkoutFormats.clock(remaining * 1000L), style = AppTheme.type.statValue, color = colors.text)
            }
            AppTextButton(stringResource(Res.string.live_rest_minus), onClick = { actions.onAdjustRest(-RestTimer.ADJUST_STEP_SECONDS) })
            AppTextButton(stringResource(Res.string.live_rest_plus), onClick = { actions.onAdjustRest(RestTimer.ADJUST_STEP_SECONDS) })
            IconButton(onClick = actions.onSkipRest) {
                Icon(Icons.Outlined.SkipNext, stringResource(Res.string.live_rest_skip), tint = colors.brandStrong)
            }
        }
    }
}

@Composable
private fun ExerciseInfoSheet(exercise: Exercise?, languageTag: String, onDismiss: () -> Unit) {
    AppBottomSheet(onDismiss = onDismiss, title = exercise?.name(languageTag)) {
        if (exercise == null) {
            Text(stringResource(Res.string.live_exercise_info_missing), style = AppTheme.type.body)
            return@AppBottomSheet
        }
        ExerciseImage(exercise, Modifier.padding(vertical = Spacing.s).size(160.dp))
        Text(exercise.muscleAndEquipment(), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
        val steps = exercise.instructions(languageTag)
        if (steps.isEmpty()) {
            Text(stringResource(Res.string.live_exercise_no_instructions), style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
        } else {
            steps.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("${i + 1}.", style = AppTheme.type.bodyStrong, color = AppTheme.colors.text)
                    Text(step, style = AppTheme.type.body, color = AppTheme.colors.text)
                }
            }
        }
    }
}

@Composable
private fun Dialogs(state: LiveWorkoutUiState, actions: LiveWorkoutActions) {
    when (val dialog = state.dialog) {
        null -> Unit
        is LiveDialog.ConfirmFinish -> AlertDialog(
            onDismissRequest = actions.onDismissDialog,
            title = { Text(stringResource(Res.string.live_finish_title)) },
            text = { Text(pluralStringResource(Res.plurals.live_finish_unchecked, dialog.uncheckedSets, dialog.uncheckedSets), style = AppTheme.type.body) },
            confirmButton = { AppTextButton(stringResource(Res.string.live_finish_confirm), onClick = actions.onConfirmFinish) },
            dismissButton = { AppTextButton(stringResource(Res.string.live_keep_training), onClick = actions.onDismissDialog) },
        )
        LiveDialog.ConfirmDiscard -> AlertDialog(
            onDismissRequest = actions.onDismissDialog,
            title = { Text(stringResource(Res.string.live_discard_title)) },
            text = { Text(stringResource(Res.string.live_discard_body), style = AppTheme.type.body) },
            confirmButton = { AppTextButton(stringResource(Res.string.live_discard_confirm), onClick = actions.onConfirmDiscard, destructive = true) },
            dismissButton = { AppTextButton(stringResource(Res.string.live_keep_training), onClick = actions.onDismissDialog) },
        )
        LiveDialog.NotificationRationale -> AlertDialog(
            onDismissRequest = actions.onDismissDialog,
            title = { Text(stringResource(Res.string.live_notifications_title)) },
            text = { Text(stringResource(Res.string.live_notifications_body), style = AppTheme.type.body) },
            confirmButton = { AppTextButton(stringResource(Res.string.live_notifications_allow), onClick = actions.onAcceptNotificationRationale) },
            dismissButton = { AppTextButton(stringResource(Res.string.live_notifications_later), onClick = actions.onDismissDialog) },
        )
        LiveDialog.VideoSource -> AppBottomSheet(onDismiss = actions.onDismissDialog, title = stringResource(Res.string.live_record_technique)) {
            Text(stringResource(Res.string.live_video_hint), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
            if (actions.canRecordVideo) ActionRow(Icons.Outlined.Videocam, stringResource(Res.string.live_video_record), onClick = actions.onRecordVideo)
            ActionRow(Icons.Outlined.PhotoLibrary, stringResource(Res.string.live_video_pick), onClick = actions.onPickVideo)
        }
    }
}
