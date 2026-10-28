package com.trainingapp.feature.workoutbuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.contract.plan.PlanLimits
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppFilterChip
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.resources.ds_back
import com.trainingapp.designsystem.resources.ds_cancel
import com.trainingapp.designsystem.resources.ds_done
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.plan.DraftDay
import com.trainingapp.domain.plan.PlanDraftAction
import com.trainingapp.domain.plan.PlanStats
import com.trainingapp.domain.plan.PlanStatsCalculator
import com.trainingapp.domain.plan.supersetLabels
import com.trainingapp.feature.workoutbuilder.common.PlanEditorDestination
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.creator_add_from_library
import com.trainingapp.feature.workoutbuilder.resources.creator_autosave
import com.trainingapp.feature.workoutbuilder.resources.creator_edited_date
import com.trainingapp.feature.workoutbuilder.resources.creator_edited_hours
import com.trainingapp.feature.workoutbuilder.resources.creator_edited_minutes
import com.trainingapp.feature.workoutbuilder.resources.creator_edited_now
import com.trainingapp.feature.workoutbuilder.resources.creator_messages
import com.trainingapp.feature.workoutbuilder.resources.creator_name_label
import com.trainingapp.feature.workoutbuilder.resources.creator_name_placeholder
import com.trainingapp.feature.workoutbuilder.resources.creator_not_available
import com.trainingapp.feature.workoutbuilder.resources.creator_notifications
import com.trainingapp.feature.workoutbuilder.resources.creator_profile
import com.trainingapp.feature.workoutbuilder.resources.creator_save_template
import com.trainingapp.feature.workoutbuilder.resources.creator_stat_rpe
import com.trainingapp.feature.workoutbuilder.resources.creator_stat_sets
import com.trainingapp.feature.workoutbuilder.resources.creator_stat_time
import com.trainingapp.feature.workoutbuilder.resources.creator_stat_time_value
import com.trainingapp.feature.workoutbuilder.resources.creator_tag_add
import com.trainingapp.feature.workoutbuilder.resources.creator_tag_label
import com.trainingapp.feature.workoutbuilder.resources.creator_tag_remove
import com.trainingapp.feature.workoutbuilder.resources.creator_tags
import com.trainingapp.feature.workoutbuilder.resources.creator_title
import com.trainingapp.feature.workoutbuilder.resources.editor_assign
import com.trainingapp.feature.workoutbuilder.resources.editor_conflict_load_latest
import com.trainingapp.feature.workoutbuilder.resources.editor_conflict_message
import com.trainingapp.feature.workoutbuilder.resources.editor_conflict_save_as_new
import com.trainingapp.feature.workoutbuilder.resources.editor_conflict_title
import com.trainingapp.feature.workoutbuilder.resources.editor_day_empty_message
import com.trainingapp.feature.workoutbuilder.resources.editor_exercise_order
import com.trainingapp.feature.workoutbuilder.resources.editor_failed
import com.trainingapp.feature.workoutbuilder.resources.editor_invalid
import com.trainingapp.feature.workoutbuilder.resources.editor_not_found
import com.trainingapp.feature.workoutbuilder.resources.editor_offline
import com.trainingapp.feature.workoutbuilder.resources.editor_removed
import com.trainingapp.feature.workoutbuilder.resources.editor_retry
import com.trainingapp.feature.workoutbuilder.resources.editor_saved_version
import com.trainingapp.feature.workoutbuilder.resources.editor_sync_failed
import com.trainingapp.feature.workoutbuilder.resources.editor_undo
import com.trainingapp.feature.workoutbuilder.resources.positions_count
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import kotlin.time.Clock
import com.trainingapp.designsystem.resources.Res as DsRes

@Composable
fun PlanEditorScreen(
    destination: PlanEditorDestination,
    onBack: () -> Unit,
    onOpenPicker: (requestKey: String, singleSelect: Boolean) -> Unit,
    onOpenDraft: (draftId: String) -> Unit,
    onAssignToClient: (templateId: String, versionNumber: Int) -> Unit,
    viewModel: PlanEditorViewModel = koinViewModel(key = destination.toString()) { parametersOf(destination) },
    language: AppLanguage = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val currentOnOpenPicker by rememberUpdatedState(onOpenPicker)
    val currentOnOpenDraft by rememberUpdatedState(onOpenDraft)
    val currentOnAssign by rememberUpdatedState(onAssignToClient)
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PlanEditorEffect.Published ->
                    snackbar.showSnackbar(getString(Res.string.editor_saved_version, effect.versionNumber), duration = SnackbarDuration.Short)
                is PlanEditorEffect.Assign -> currentOnAssign(effect.templateId, effect.versionNumber)
                is PlanEditorEffect.ExerciseRemoved -> {
                    val result = snackbar.showSnackbar(
                        message = getString(Res.string.editor_removed, effect.exerciseName),
                        actionLabel = getString(Res.string.editor_undo),
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.onAction(effect.undo)
                }
                is PlanEditorEffect.OpenPicker -> currentOnOpenPicker(effect.requestKey, effect.singleSelect)
                is PlanEditorEffect.OpenDraft -> currentOnOpenDraft(effect.draftId)
                PlanEditorEffect.Close -> currentOnBack()
            }
        }
    }

    PlanEditorContent(
        state = state,
        languageTag = language.current(),
        snackbar = snackbar,
        onBack = onBack,
        onAction = viewModel::onAction,
        onAddExercises = viewModel::addExercises,
        onReplaceExercise = viewModel::replaceExercise,
        onRemoveExercise = viewModel::removeExercise,
        onSaveTemplate = viewModel::publish,
        onAssign = viewModel::assignToClient,
        onDismissConflict = viewModel::dismissConflict,
        onSaveAsNew = viewModel::saveAsNewPlan,
        onLoadLatest = viewModel::loadLatestVersion,
    )
}

/** S2 "Kreator Planu", laid out as the Stitch reference (docs/design/stitch/plan-creator/kreator-planu.jpg). */
@Composable
fun PlanEditorContent(
    state: PlanEditorUiState,
    languageTag: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAction: (PlanDraftAction) -> Unit,
    onAddExercises: (dayId: String) -> Unit,
    onReplaceExercise: (dayId: String, plannedExerciseId: String) -> Unit,
    onRemoveExercise: (dayId: String, plannedExerciseId: String) -> Unit,
    onSaveTemplate: () -> Unit,
    onAssign: () -> Unit,
    onDismissConflict: () -> Unit,
    onSaveAsNew: () -> Unit,
    onLoadLatest: () -> Unit,
) {
    val draft = state.draft
    var settingsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var showTags by rememberSaveable { mutableStateOf(false) }
    var showNotAvailable by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = { CreatorTopBar(onBack = onBack, onNotAvailable = { showNotAvailable = true }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (draft != null) CreatorBottomBar(publishing = state.publishing, onSaveTemplate = onSaveTemplate, onAssign = onAssign)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // The warm wash behind the header in the reference.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Brush.verticalGradient(listOf(AppTheme.colors.softAccent, AppTheme.colors.background))),
            )
            when {
                state.loading -> LoadingState()
                state.notFound || draft == null -> ErrorState(stringResource(Res.string.editor_not_found))
                else -> {
                    val issues = remember(state.issues) { EditorIssues(state.issues) }
                    val day = state.selectedDay ?: draft.days.first()
                    val stats = remember(day, state.exercises) { PlanStatsCalculator.of(day) { state.exercises[it]?.trackingType } }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.gutter)
                            .padding(top = Spacing.s, bottom = Spacing.xxl),
                        verticalArrangement = Arrangement.spacedBy(Spacing.l),
                    ) {
                        AutosaveStatus(state.savedOnDevice, state.lastSavedAtEpochMs, languageTag)
                        state.banner?.let { EditorBannerMessage(it, onRetry = onSaveTemplate) }
                        NameCard(
                            name = draft.name,
                            onNameChange = { onAction(PlanDraftAction.Rename(it)) },
                            onOpenTags = { showTags = true },
                            error = issues.name?.let { planIssueText(it) },
                        )
                        if (draft.tags.isNotEmpty() || issues.tags != null) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                draft.tags.forEach { tag -> AppFilterChip(label = tag, selected = true, onClick = { showTags = true }) }
                            }
                            issues.tags?.let { Text(planIssueText(it), style = AppTheme.type.compactBody, color = AppTheme.colors.onErrorContainer) }
                        }
                        StatTiles(stats, languageTag)
                        ExerciseOrderHeader(day.exercises.size)
                        issues.dayIssues(day.id).forEach { InlineMessage(planIssueText(it), tone = MessageTone.Error) }
                        if (day.exercises.isEmpty()) {
                            Text(
                                stringResource(Res.string.editor_day_empty_message),
                                style = AppTheme.type.body,
                                color = AppTheme.colors.textSecondary,
                                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.l),
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            DayExercises(
                                day = day,
                                state = state,
                                languageTag = languageTag,
                                issues = issues,
                                onAction = onAction,
                                onOpenSettings = { settingsFor = it },
                                onReplace = { onReplaceExercise(day.id, it) },
                                onRemove = { onRemoveExercise(day.id, it) },
                            )
                        }
                        PrimaryButton(
                            text = stringResource(Res.string.creator_add_from_library),
                            onClick = { onAddExercises(day.id) },
                            icon = Icons.Outlined.Add,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        )
                    }

                    settingsFor?.let { plannedId -> day.exercises.firstOrNull { it.id == plannedId } }?.let { planned ->
                        ExerciseSettingsSheet(
                            planned = planned,
                            exerciseName = state.exercises[planned.exerciseId]?.name(languageTag).orEmpty(),
                            onApply = { settings ->
                                onAction(
                                    PlanDraftAction.UpdateSettings(
                                        day.id, planned.id, settings.restSeconds, planned.rpeEnabled, settings.tempo, settings.note, settings.accessory,
                                    ),
                                )
                                settingsFor = null
                            },
                            onDismiss = { settingsFor = null },
                        )
                    }
                    if (showTags) {
                        TagsSheet(
                            tags = draft.tags,
                            onApply = { onAction(PlanDraftAction.UpdateTags(it)); showTags = false },
                            onDismiss = { showTags = false },
                        )
                    }
                    state.conflictLatestVersion?.let { latest ->
                        ConflictDialog(latest, onSaveAsNew = onSaveAsNew, onLoadLatest = onLoadLatest, onDismiss = onDismissConflict)
                    }
                }
            }
        }
    }

    if (showNotAvailable) {
        AlertDialog(
            onDismissRequest = { showNotAvailable = false },
            text = { Text(stringResource(Res.string.creator_not_available), style = AppTheme.type.body) },
            confirmButton = { AppTextButton(stringResource(DsRes.string.ds_done), onClick = { showNotAvailable = false }) },
        )
    }
}

/** Back, the two-line "Kreator Planu" title, messages, notifications and the profile avatar. */
@Composable
private fun CreatorTopBar(onBack: () -> Unit, onNotAvailable: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .padding(start = Spacing.xs, end = Spacing.l, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(DsRes.string.ds_back), tint = colors.text) }
        Text(
            stringResource(Res.string.creator_title),
            style = AppTheme.type.screenTitle,
            color = colors.text,
            maxLines = 2,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.s, top = Spacing.xs)
                .semantics { heading() },
        )
        IconButton(onClick = onNotAvailable) {
            Icon(Icons.Outlined.ChatBubbleOutline, stringResource(Res.string.creator_messages), tint = colors.text)
        }
        IconButton(onClick = onNotAvailable) {
            Icon(Icons.Outlined.NotificationsNone, stringResource(Res.string.creator_notifications), tint = colors.text)
        }
        IconButton(
            onClick = onNotAvailable,
            colors = IconButtonDefaults.iconButtonColors(containerColor = colors.brandStrong, contentColor = colors.onBrandStrong),
        ) {
            Icon(Icons.Outlined.PersonOutline, stringResource(Res.string.creator_profile))
        }
    }
}

/** "● SZKIC AUTOZAPISU" on the left, "🕒 Edytowano 2 min temu" on the right. */
@Composable
private fun AutosaveStatus(savedOnDevice: Boolean, lastSavedAtEpochMs: Long?, languageTag: String) {
    if (!savedOnDevice) return
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(
            Modifier
                .weight(1f)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(colors.softAccent, colors.softAccent.copy(alpha = 0f))))
                .padding(horizontal = Spacing.m, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.brandAccent))
            Text(stringResource(Res.string.creator_autosave).uppercase(), style = AppTheme.type.overline, color = colors.onSoftAccent)
        }
        if (lastSavedAtEpochMs != null) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                Text(editedText(lastSavedAtEpochMs, languageTag), style = AppTheme.type.label, color = colors.textSecondary)
            }
        }
    }
}

/** Relative time that refreshes every half minute. */
@Composable
private fun editedText(savedAt: Long, languageTag: String): String {
    val now by produceState(Clock.System.now().toEpochMilliseconds(), savedAt) {
        while (true) {
            value = Clock.System.now().toEpochMilliseconds()
            delay(30_000)
        }
    }
    val minutes = ((now - savedAt).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.creator_edited_now)
        minutes < 60 -> stringResource(Res.string.creator_edited_minutes, minutes)
        minutes < 24 * 60 -> stringResource(Res.string.creator_edited_hours, minutes / 60)
        else -> stringResource(Res.string.creator_edited_date, LocaleFormats.formatDate(savedAt, languageTag))
    }
}

/** The white pill with the dumbbell icon, "NAZWA JEDNOSTKI" label, the editable name and the tag button. */
@Composable
private fun NameCard(name: String, onNameChange: (String) -> Unit, onOpenTags: () -> Unit, error: String?) {
    val colors = AppTheme.colors
    val label = stringResource(Res.string.creator_name_label)
    val shape = RoundedCornerShape(40.dp)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(8.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
                .clip(shape)
                .background(colors.surface)
                .padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(colors.tonalSurface), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.FitnessCenter, contentDescription = null, tint = colors.brandAccent, modifier = Modifier.rotate(-45f))
            }
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = AppTheme.type.overline, color = colors.textSecondary)
                BasicTextField(
                    value = name,
                    onValueChange = { onNameChange(it.take(PlanLimits.NAME_MAX)) },
                    singleLine = true,
                    textStyle = AppTheme.type.sectionTitle.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.brandStrong),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) {
                                Text(stringResource(Res.string.creator_name_placeholder), style = AppTheme.type.sectionTitle, color = colors.textSecondary)
                            }
                            inner()
                        }
                    },
                )
            }
            IconButton(onClick = onOpenTags, colors = IconButtonDefaults.iconButtonColors(containerColor = colors.subtleSurface)) {
                Icon(Icons.Outlined.LocalOffer, stringResource(Res.string.creator_tags), tint = colors.textSecondary)
            }
        }
        if (error != null) {
            Text(error, style = AppTheme.type.compactBody, color = colors.onErrorContainer, modifier = Modifier.padding(horizontal = Spacing.l))
        }
    }
}

/** ŁĄCZNIE SERII · SZAC. CZAS · ŚR. RPE. */
@Composable
private fun StatTiles(stats: PlanStats, languageTag: String) {
    val separator = LocaleFormats.decimalSeparator(languageTag)
    val rpe = stats.averageRpe?.let { value ->
        val tenths = (value * 10).roundToInt()
        "${tenths / 10}$separator${tenths % 10}"
    } ?: "–"
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        StatTile(stringResource(Res.string.creator_stat_sets), "${stats.totalSets}", highlighted = false, modifier = Modifier.weight(1f))
        StatTile(
            stringResource(Res.string.creator_stat_time),
            stringResource(Res.string.creator_stat_time_value, stats.estimatedMinutes),
            highlighted = true,
            modifier = Modifier.weight(1f),
        )
        StatTile(stringResource(Res.string.creator_stat_rpe), rpe, highlighted = false, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, highlighted: Boolean, modifier: Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) colors.softAccent.copy(alpha = 0.5f) else colors.surface)
            .padding(vertical = Spacing.l, horizontal = Spacing.s)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(label.uppercase(), style = AppTheme.type.overline, color = colors.textSecondary, textAlign = TextAlign.Center, maxLines = 2)
        Text(value, style = AppTheme.type.statValue, color = if (highlighted) colors.brandAccent else colors.text, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ExerciseOrderHeader(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = null, tint = AppTheme.colors.brandAccent)
        Text(
            stringResource(Res.string.editor_exercise_order),
            style = AppTheme.type.sectionTitle,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(pluralStringResource(Res.plurals.positions_count, count, count), style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
    }
}

/** "Zapisz szablon" and "Przypisz klientowi" side by side, above the keyboard and system bars. */
@Composable
private fun CreatorBottomBar(publishing: Boolean, onSaveTemplate: () -> Unit, onAssign: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surface)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        HorizontalDivider(color = AppTheme.colors.outline)
        Row(
            Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            TonalButton(
                text = stringResource(Res.string.creator_save_template),
                onClick = onSaveTemplate,
                loading = publishing,
                icon = Icons.Outlined.BookmarkBorder,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
            PrimaryButton(
                text = stringResource(Res.string.editor_assign),
                onClick = onAssign,
                enabled = !publishing,
                icon = Icons.Outlined.PersonAddAlt,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
        }
    }
}

@Composable
private fun EditorBannerMessage(banner: EditorBanner, onRetry: () -> Unit) {
    val (text, tone) = when (banner) {
        EditorBanner.Invalid -> stringResource(Res.string.editor_invalid) to MessageTone.Error
        EditorBanner.Offline -> stringResource(Res.string.editor_offline) to MessageTone.Offline
        is EditorBanner.SyncFailed -> stringResource(Res.string.editor_sync_failed, banner.exerciseNames.joinToString()) to MessageTone.Error
        EditorBanner.Failed -> stringResource(Res.string.editor_failed) to MessageTone.Error
    }
    InlineMessage(
        text = text,
        tone = tone,
        action = if (banner == EditorBanner.Invalid) null else ({ AppTextButton(stringResource(Res.string.editor_retry), onClick = onRetry) }),
    )
}

@Composable
private fun TagsSheet(tags: List<String>, onApply: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf(tags) }
    var input by rememberSaveable { mutableStateOf("") }
    val canAdd = input.isNotBlank() && current.size < PlanLimits.TAGS_MAX
    AppBottomSheet(onDismiss = onDismiss, title = stringResource(Res.string.creator_tags)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            current.forEach { tag ->
                val removeLabel = stringResource(Res.string.creator_tag_remove, tag)
                AppFilterChip(
                    label = tag,
                    selected = true,
                    leadingIcon = Icons.Outlined.Close,
                    onClick = { current = current - tag },
                    modifier = Modifier.semantics { contentDescription = removeLabel },
                )
            }
        }
        AppTextField(
            value = input,
            onValueChange = { input = it.take(PlanLimits.TAG_MAX) },
            label = stringResource(Res.string.creator_tag_label),
        )
        AppTextButton(
            text = stringResource(Res.string.creator_tag_add),
            icon = Icons.Outlined.Add,
            enabled = canAdd,
            onClick = {
                current = (current + input.trim()).distinct()
                input = ""
            },
        )
        PrimaryButton(stringResource(DsRes.string.ds_done), onClick = { onApply(current) }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The exercises of the plan, in order. A long press on the drag handle drags the card; the card menu and the
 * handle's accessibility actions move it one step.
 */
@Composable
private fun DayExercises(
    day: DraftDay,
    state: PlanEditorUiState,
    languageTag: String,
    issues: EditorIssues,
    onAction: (PlanDraftAction) -> Unit,
    onOpenSettings: (plannedExerciseId: String) -> Unit,
    onReplace: (plannedExerciseId: String) -> Unit,
    onRemove: (plannedExerciseId: String) -> Unit,
) {
    val labels = remember(day.exercises) { day.supersetLabels() }
    val showSupersetLetters = labels.values.distinct().size > 1
    val heights = remember(day.id) { mutableStateMapOf<String, Int>() }
    var draggingId by remember(day.id) { mutableStateOf<String?>(null) }
    var dragOffset by remember(day.id) { mutableFloatStateOf(0f) }
    val gapPx = with(LocalDensity.current) { Spacing.xl.toPx() }
    val currentExercises by rememberUpdatedState(day.exercises)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        day.exercises.forEachIndexed { index, planned ->
            key(planned.id) {
                val isDragging = draggingId == planned.id
                val last = day.exercises.lastIndex
                val move = { to: Int -> onAction(PlanDraftAction.MoveExercise(day.id, planned.id, to)) }
                val callbacks = PlannedExerciseCallbacks(
                    onMoveUp = if (index > 0) ({ move(index - 1) }) else null,
                    onMoveDown = if (index < last) ({ move(index + 1) }) else null,
                    onLinkWithNext = if (index < last && labels[planned.id].let { it == null || it != labels[day.exercises[index + 1].id] }) {
                        { onAction(PlanDraftAction.LinkWithNext(day.id, planned.id)) }
                    } else {
                        null
                    },
                    onUnlink = if (planned.supersetGroup != null) ({ onAction(PlanDraftAction.Unlink(day.id, planned.id)) }) else null,
                    onOpenSettings = { onOpenSettings(planned.id) },
                    onReplace = { onReplace(planned.id) },
                    onRemove = { onRemove(planned.id) },
                    onAddSet = { onAction(PlanDraftAction.AddSet(day.id, planned.id)) },
                    onRemoveSet = { setId -> onAction(PlanDraftAction.RemoveSet(day.id, planned.id, setId)) },
                    onCycleSetType = { setId -> onAction(PlanDraftAction.CycleSetType(day.id, planned.id, setId)) },
                    onSetValue = { setId, field, text -> onAction(PlanDraftAction.UpdateSetValue(day.id, planned.id, setId, field, text)) },
                )
                val dragHandle = Modifier.pointerInput(planned.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggingId = planned.id
                            dragOffset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                        },
                        onDragEnd = {
                            val ids = currentExercises.map { it.id }
                            val from = ids.indexOf(planned.id)
                            if (from >= 0) {
                                val to = dropIndex(from, dragOffset, ids, heights, gapPx)
                                if (to != from) onAction(PlanDraftAction.MoveExercise(day.id, planned.id, to))
                            }
                            draggingId = null
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            draggingId = null
                            dragOffset = 0f
                        },
                    )
                }
                Box(
                    Modifier
                        .onSizeChanged { heights[planned.id] = it.height }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f },
                ) {
                    PlannedExerciseCard(
                        position = index + 1,
                        planned = planned,
                        exercise = state.exercises[planned.exerciseId],
                        languageTag = languageTag,
                        supersetLabel = labels[planned.id],
                        showSupersetLetter = showSupersetLetters,
                        issues = issues,
                        callbacks = callbacks,
                        dragHandleModifier = dragHandle,
                    )
                }
            }
        }
    }
}

/** Where a card dragged by [offset] pixels from [from] lands: it passes a neighbour once it covers half of it. */
internal fun dropIndex(from: Int, offset: Float, ids: List<String>, heights: Map<String, Int>, gapPx: Float): Int {
    var target = from
    var remaining = offset
    if (offset > 0) {
        while (target < ids.lastIndex) {
            val next = (heights[ids[target + 1]] ?: 0) + gapPx
            if (remaining > next / 2) {
                remaining -= next
                target++
            } else {
                break
            }
        }
    } else {
        while (target > 0) {
            val previous = (heights[ids[target - 1]] ?: 0) + gapPx
            if (-remaining > previous / 2) {
                remaining += previous
                target--
            } else {
                break
            }
        }
    }
    return target
}

/** A stale base version: the draft is kept, and the trainer chooses what happens next. */
@Composable
private fun ConflictDialog(latestVersion: Int, onSaveAsNew: () -> Unit, onLoadLatest: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_conflict_title)) },
        text = { Text(stringResource(Res.string.editor_conflict_message, latestVersion), style = AppTheme.type.body) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                AppTextButton(stringResource(Res.string.editor_conflict_save_as_new), onClick = onSaveAsNew)
                AppTextButton(stringResource(Res.string.editor_conflict_load_latest, latestVersion), onClick = onLoadLatest, destructive = true)
                AppTextButton(stringResource(DsRes.string.ds_cancel), onClick = onDismiss)
            }
        },
    )
}
