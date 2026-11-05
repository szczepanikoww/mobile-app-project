package pl.trainerapp.feature.trainerprofile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.trainingapp.contract.policy.PolicyError
import com.trainingapp.contract.policy.PolicyPreset
import com.trainingapp.contract.policy.PolicyValidator
import pl.trainer.presentation.CancellationPolicyViewModel
import pl.trainer.presentation.PolicyEditorUiState
import pl.trainer.ui.AppColors
import pl.trainer.ui.CancellationPolicySummary
import pl.trainer.ui.ConfirmDialog
import pl.trainer.ui.formatPolicyDate
import pl.trainer.ui.InfoBanner
import pl.trainer.ui.Layout
import pl.trainer.ui.formatMoney
import pl.trainer.resources.*
import pl.trainer.ui.PlatformBackHandler
import pl.trainerapp.core.designsystem.DetailHeader

/** The view-model key shared by the profile summary and the editor, so a saved version shows in both at once. */
const val CancellationPolicyModelKey = "cancellation-policy"

/**
 * B5 "Polityka odwołań": presets, fee tiers, no-show fee and grace period, free late cancellations, a non-blocking
 * legal warning and the exact client preview. Saving creates a new version for bookings made afterwards.
 */
@Composable
fun CancellationPolicyScreen(model: CancellationPolicyViewModel, state: PolicyEditorUiState, onBack: () -> Unit) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (state.dirty) confirmDiscard = true else onBack() }
    PlatformBackHandler(enabled = true, onBack = leave)
    Column(Modifier.fillMaxSize()) {
        DetailHeader(stringResource(Res.string.policy_editor_title), onBack = leave)
        val current = state.current
        val draft = state.draft
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            current == null || draft == null -> Column(Modifier.padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                InfoBanner(stringResource(Res.string.policy_load_error), error = true)
                TextButton(onClick = model::reload, modifier = Modifier.heightIn(min = Layout.target)) { Text(stringResource(Res.string.policy_retry)) }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = Layout.contentWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Layout.gutter),
                    verticalArrangement = Arrangement.spacedBy(Layout.section),
                ) {
                    Text(
                        stringResource(Res.string.policy_current_version, current.version, createdDate(state)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.savedVersion?.let { InfoBanner(stringResource(Res.string.policy_saved, it)) }

                    Section(stringResource(Res.string.policy_presets)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
                            PolicyPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = state.preset == preset,
                                    onClick = { model.applyPreset(preset) },
                                    enabled = !state.saving,
                                    label = { Text(stringResource(preset.label())) },
                                    modifier = Modifier.heightIn(min = Layout.target),
                                    colors = chipColors(),
                                )
                            }
                        }
                        if (state.preset == null) {
                            Text(stringResource(Res.string.policy_preset_custom), style = MaterialTheme.typography.labelLarge, color = AppColors.onSoftAccent)
                        }
                    }

                    Section(stringResource(Res.string.policy_tiers)) {
                        Text(
                            stringResource(Res.string.policy_free_until, draft.sortedTiers.firstOrNull()?.hoursBefore ?: 0),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        draft.sortedTiers.forEachIndexed { index, tier ->
                            Surface(
                                shape = Layout.panel,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(Res.string.policy_tier_label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                        if (draft.sortedTiers.size > 1) {
                                            IconButton(onClick = { model.removeTier(index) }, enabled = !state.saving) {
                                                Icon(Icons.Outlined.DeleteOutline, stringResource(Res.string.policy_remove_tier))
                                            }
                                        }
                                    }
                                    Stepper(
                                        value = stringResource(Res.string.policy_hours_value, tier.hoursBefore),
                                        onDecrease = CancellationPolicyViewModel.HourSteps.lastOrNull { it < tier.hoursBefore }?.let { { model.setTierHours(index, it) } },
                                        onIncrease = CancellationPolicyViewModel.HourSteps.firstOrNull { it > tier.hoursBefore }?.let { { model.setTierHours(index, it) } },
                                        enabled = !state.saving,
                                    )
                                    PercentChips(tier.feePercent, enabled = !state.saving) { model.setTierPercent(index, it) }
                                    state.price?.let { price ->
                                        Text(
                                            stringResource(Res.string.policy_amount, formatMoney(price.percentOf(tier.feePercent))),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (draft.sortedTiers.size < CancellationPolicyViewModel.MAX_TIERS) {
                            TextButton(onClick = model::addTier, enabled = !state.saving, modifier = Modifier.heightIn(min = Layout.target)) {
                                Icon(Icons.Outlined.Add, null)
                                Text(stringResource(Res.string.policy_add_tier))
                            }
                        }
                    }

                    Section(stringResource(Res.string.policy_no_show_title)) {
                        PercentChips(draft.noShowPercent, enabled = !state.saving, onSelect = model::setNoShowPercent)
                        Text(stringResource(Res.string.policy_grace_title), style = MaterialTheme.typography.bodyLarge)
                        Stepper(
                            value = stringResource(Res.string.policy_grace_value, draft.graceMinutes),
                            onDecrease = if (draft.graceMinutes > PolicyValidator.graceRange.first) ({ model.setGraceMinutes(draft.graceMinutes - 5) }) else null,
                            onIncrease = if (draft.graceMinutes < PolicyValidator.graceRange.last) ({ model.setGraceMinutes(draft.graceMinutes + 5) }) else null,
                            enabled = !state.saving,
                        )
                        Text(stringResource(Res.string.policy_grace_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Section(stringResource(Res.string.policy_leniency_title)) {
                        Stepper(
                            value = stringResource(Res.string.policy_leniency_value, draft.freeLateCancellationsPerMonth),
                            onDecrease = if (draft.freeLateCancellationsPerMonth > 0) ({ model.setFreeLateCancellations(draft.freeLateCancellationsPerMonth - 1) }) else null,
                            onIncrease = if (draft.freeLateCancellationsPerMonth < PolicyValidator.MAX_FREE_CANCELLATIONS) {
                                { model.setFreeLateCancellations(draft.freeLateCancellationsPerMonth + 1) }
                            } else null,
                            enabled = !state.saving,
                        )
                    }

                    if (state.errors.isNotEmpty()) {
                        InfoBanner(
                            stringResource(if (PolicyError.TierOrder in state.errors) Res.string.policy_error_order else Res.string.policy_error_generic),
                            error = true,
                        )
                    }
                    if (state.warnings.isNotEmpty()) InfoBanner(stringResource(Res.string.policy_warning), Icons.Outlined.Gavel)

                    Section(stringResource(Res.string.policy_preview_title)) {
                        Surface(shape = Layout.panel, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            CancellationPolicySummary(draft, Modifier.padding(Layout.padding), price = state.price)
                        }
                    }

                    Text(stringResource(Res.string.policy_applies_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.saveFailure?.let { failure ->
                        InfoBanner(stringResource(when (failure) {
                            pl.trainer.domain.Failure.Offline -> Res.string.policy_save_offline
                            pl.trainer.domain.Failure.Conflict -> Res.string.policy_save_conflict
                            else -> Res.string.policy_save_error
                        }), error = true)
                    }
                    pl.trainer.ui.PrimaryButton(stringResource(Res.string.policy_save), enabled = state.canSave, loading = state.saving, onClick = model::save)
                }
            }
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.policy_discard_title),
            body = stringResource(Res.string.policy_discard_body),
            confirm = stringResource(Res.string.policy_discard_confirm),
            submitting = false,
            onDismiss = { confirmDiscard = false },
            onConfirm = { confirmDiscard = false; model.discard(); onBack() },
        )
    }
}

/** The profile's "Polityka odwołań" card: the current version in the client's words, and the editor entry. */
@Composable
fun CancellationPolicySummaryCard(state: PolicyEditorUiState, onEdit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
        Text(stringResource(Res.string.settings_policy), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Surface(
            shape = Layout.card,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                val current = state.current
                if (current == null) {
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else Text(stringResource(Res.string.policy_load_error), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        stringResource(Res.string.policy_current_version, current.version, createdDate(state)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CancellationPolicySummary(current.rules, price = state.price)
                }
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = Layout.target)) { Text(stringResource(Res.string.policy_edit)) }
            }
        }
    }
}

private fun createdDate(state: PolicyEditorUiState): String = state.current?.let { formatPolicyDate(it.createdAt) }.orEmpty()

private fun PolicyPreset.label(): StringResource = when (this) {
    PolicyPreset.Flexible -> Res.string.policy_preset_flexible
    PolicyPreset.Standard -> Res.string.policy_preset_standard
    PolicyPreset.Strict -> Res.string.policy_preset_strict
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = AppColors.softAccent,
    selectedLabelColor = AppColors.onSoftAccent,
)

/** Percentage chips (0, 25, 50, 75, 100), not a free slider. */
@Composable
private fun PercentChips(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
        PolicyValidator.allowedPercents.forEach { percent ->
            FilterChip(
                selected = percent == selected,
                onClick = { onSelect(percent) },
                enabled = enabled,
                label = { Text(stringResource(Res.string.policy_percent, percent)) },
                modifier = Modifier.heightIn(min = Layout.target),
                colors = chipColors(),
            )
        }
    }
}

/** A labelled stepper: a null action disables its button at the end of the range. */
@Composable
private fun Stepper(value: String, onDecrease: (() -> Unit)?, onIncrease: (() -> Unit)?, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
        IconButton(onClick = { onDecrease?.invoke() }, enabled = enabled && onDecrease != null) {
            Icon(Icons.Outlined.Remove, stringResource(Res.string.policy_decrease))
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 72.dp).semantics { stateDescription = value },
        )
        IconButton(onClick = { onIncrease?.invoke() }, enabled = enabled && onIncrease != null) {
            Icon(Icons.Outlined.Add, stringResource(Res.string.policy_increase))
        }
    }
}
