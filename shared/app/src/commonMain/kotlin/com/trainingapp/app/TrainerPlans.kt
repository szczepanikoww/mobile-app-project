package com.trainingapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.trainingapp.app.remote.RemotePlanAssigner
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.*
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.plan.OpenPlanDraft
import com.trainingapp.domain.plan.PlanLibraryItem
import com.trainingapp.domain.plan.PlanRepository
import com.trainingapp.domain.plan.SavePlanDraft
import com.trainingapp.feature.workoutbuilder.common.DefaultDayNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import pl.trainer.ui.AppColors
import pl.trainer.ui.HeroCard
import pl.trainer.ui.IconTile
import pl.trainer.ui.Layout
import pl.trainer.ui.PlanTarget
import pl.trainer.ui.TileTone
import pl.trainer.ui.WhiteCard
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A plan written out for one session (docs/plans/trainer-redesign.md, "Session detail"): set when the trainer opens a
 * copy of a plan in the editor, used when the editor's "Przypisz klientowi" comes back, so the published copy goes to
 * that client and session without asking again.
 */
class PendingSessionPlan {
    var target: PlanTarget? = null
}

/**
 * Plany (docs/plans/trainer-redesign.md): "Moje plany" opens the plan list (new plan inside it), "Biblioteka ćwiczeń"
 * opens the exercise base with the trainer's own exercises and videos.
 */
@Composable
fun TrainerPlansTab(actions: WorkoutNavigationActions) {
    val plans: PlanRepository = koinInject()
    val library by remember(plans) { plans.observeLibrary() }.collectAsState(emptyList())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Layout.gutter, vertical = Layout.padding),
        verticalArrangement = Arrangement.spacedBy(Layout.medium),
    ) {
        HeroCard(onClick = actions.onOpenPlans) {
            IconTile(Icons.Outlined.FitnessCenter, TileTone.Brand, size = 44.dp)
            Spacer(Modifier.height(Layout.medium))
            Text(stringResource(Res.string.plans_my_plans), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                if (library.isEmpty()) stringResource(Res.string.plans_my_plans_empty) else stringResource(Res.string.plans_my_plans_count, library.size),
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f),
            )
            Spacer(Modifier.height(Layout.medium))
            Button(onClick = actions.onNewPlan, shape = Layout.pill, modifier = Modifier.heightIn(min = Layout.target),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AppColors.brandStrong)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.size(Layout.small)); Text(stringResource(Res.string.plans_new_plan))
            }
        }
        if (library.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            library.sortedByDescending { it.updatedAtEpochMs }.take(3).forEach { item ->
                WhiteCard(onClick = actions.onOpenPlans) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
                        IconTile(Icons.Outlined.FitnessCenter, TileTone.Neutral, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(item.name.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(Res.string.plans_plan_summary, item.dayCount, item.exerciseCount), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        WhiteCard(onClick = actions.onOpenExercises) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
                IconTile(Icons.AutoMirrored.Outlined.LibraryBooks, TileTone.Change, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.plans_library), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.plans_library_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The plan of a client (their default plan) or of one session, inside the client card and the session details.
 * Assigning uses the server's assignment API; without a server it says so. "Rozpisz dla tej sesji" copies the chosen
 * plan into a new draft named after the session and opens it in the editor; its "Przypisz klientowi" assigns it to
 * this session ([PendingSessionPlan]). The server keeps one current plan per client, so a plan given to a session also
 * becomes the client's current plan.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun TrainerPlanSection(
    target: PlanTarget,
    assigner: RemotePlanAssigner?,
    onChanged: () -> Unit,
    onOpenDraft: (draftId: String) -> Unit,
    onOpenPlans: () -> Unit,
) {
    val plans: PlanRepository = koinInject()
    val openDraft: OpenPlanDraft = koinInject()
    val saveDraft: SavePlanDraft = koinInject()
    val language: AppLanguage = koinInject()
    val dayNames: DefaultDayNames = koinInject()
    val pending: PendingSessionPlan = koinInject()
    val scope = rememberCoroutineScope()
    var defaultPlan by remember(target.clientId) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(target.clientId, refreshKey, assigner) {
        defaultPlan = runCatching { assigner?.activePlan(target.clientId)?.name }.getOrNull()
    }
    val current = if (target.sessionId != null) target.planTitle else defaultPlan
    WhiteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            IconTile(Icons.Outlined.FitnessCenter, if (current != null) TileTone.Brand else TileTone.Neutral)
            Column(Modifier.weight(1f)) {
                Text(current ?: stringResource(Res.string.plan_none), style = MaterialTheme.typography.titleMedium)
                val hint = when {
                    target.sessionId != null && current == null && defaultPlan != null -> stringResource(Res.string.plan_session_default, defaultPlan!!)
                    target.sessionId != null -> stringResource(Res.string.plan_session_hint)
                    else -> stringResource(Res.string.plan_default_hint)
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Layout.medium))
        OutlinedButton(onClick = { picking = true }, shape = Layout.pill, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
            Text(stringResource(if (current == null) Res.string.plan_choose else Res.string.plan_change))
        }
    }
    if (picking) {
        PlanPickerDialog(
            target = target,
            plans = plans,
            assigner = assigner,
            onDismiss = { picking = false },
            onAssigned = { picking = false; refreshKey++; onChanged() },
            onOpenPlans = { picking = false; onOpenPlans() },
            onTailor = { item ->
                picking = false
                val templateId = item.templateId ?: return@PlanPickerDialog
                scope.launch {
                    val names = dayNames.names(26)
                    val source = openDraft.forTemplate(templateId, LocaleFormats.decimalSeparator(language.current())) { names.getOrElse(it) { "${it + 1}" } }
                        ?: return@launch
                    val copy = saveDraft(openDraft.duplicate(source, listOfNotNull(item.name, target.sessionTitle).joinToString(" · ").take(80)))
                    pending.target = target
                    onOpenDraft(copy.draftId)
                }
            },
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun PlanPickerDialog(
    target: PlanTarget,
    plans: PlanRepository,
    assigner: RemotePlanAssigner?,
    onDismiss: () -> Unit,
    onAssigned: () -> Unit,
    onOpenPlans: () -> Unit,
    onTailor: (PlanLibraryItem) -> Unit,
) {
    val library by remember(plans) { plans.observeLibrary() }.collectAsState(null)
    val published = library?.filter { it.templateId != null && it.latestVersion != null }?.sortedBy { it.name.lowercase() }
    var selected by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Failure?>(null) }
    val operationId = remember { Uuid.random().toString() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(plans) { runCatching { plans.refresh() } }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(if (target.sessionId != null) Res.string.plan_pick_session_title else Res.string.plan_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                when {
                    assigner == null -> Text(stringResource(Res.string.assign_message), style = MaterialTheme.typography.bodyMedium)
                    published == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    published.isEmpty() -> Text(stringResource(Res.string.plan_pick_empty), style = MaterialTheme.typography.bodyMedium)
                    else -> Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                        published.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                    selected = selected == item.templateId, enabled = !submitting, role = Role.RadioButton,
                                    onClick = { selected = item.templateId; error = null },
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == item.templateId, onClick = null, enabled = !submitting)
                                Column(Modifier.padding(start = Layout.small)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(Res.string.plans_plan_summary, item.dayCount, item.exerciseCount), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (target.sessionId != null && selected != null) Text(stringResource(Res.string.plan_tailor_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let {
                    Text(stringResource(if (it == Failure.Offline) Res.string.assign_offline else Res.string.assign_failed),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            val item = published?.firstOrNull { it.templateId == selected }
            when {
                assigner == null -> TextButton(onClick = onDismiss) { Text(stringResource(Res.string.understood)) }
                published?.isEmpty() == true -> TextButton(onClick = onOpenPlans) { Text(stringResource(Res.string.open_plans)) }
                else -> Column(horizontalAlignment = Alignment.End) {
                    TextButton(enabled = item != null && !submitting, onClick = {
                        val chosen = item ?: return@TextButton
                        submitting = true
                        error = null
                        scope.launch {
                            try {
                                assigner.assign(target.clientId, chosen.templateId!!, chosen.latestVersion!!, operationId, target.sessionId)
                                onAssigned()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: TrainerException) {
                                error = e.failure
                            } catch (e: Exception) {
                                error = Failure.Save
                            } finally {
                                submitting = false
                            }
                        }
                    }) { Text(stringResource(Res.string.assign_confirm)) }
                    if (target.sessionId != null) TextButton(enabled = item != null && !submitting, onClick = { item?.let(onTailor) }) {
                        Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp)); Spacer(Modifier.size(Layout.tiny)); Text(stringResource(Res.string.plan_tailor))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text(stringResource(Res.string.assign_cancel)) } },
    )
}
