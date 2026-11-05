package com.trainingapp.feature.client.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.DestructiveButton
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.client.presentation.BookingSheetState
import com.trainingapp.feature.client.presentation.CancellationSheetState
import com.trainingapp.feature.client.presentation.RescheduleSheetState
import com.trainingapp.feature.client.resources.*
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.AvailableSlot
import pl.trainer.domain.ChargeKind
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerRef
import pl.trainer.ui.CancellationPolicySummary
import pl.trainer.ui.DateFormats

/* B2 sheets: booking request, reschedule request, cancellation with a server quote, and the read-only policy. */

@Composable
private fun SheetProgress() {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.l), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = AppTheme.colors.brandStrong)
    }
}

/** Working days as chips: "Pon 2", "Wt 3". */
@Composable
private fun DayChips(dates: List<LocalDate>, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        dates.forEach { date ->
            FilterChip(
                selected = date == selected,
                onClick = { onSelect(date) },
                label = { Text(DateFormats.date(date, language(), "EEE d").replaceFirstChar { it.titlecase() }) },
                modifier = Modifier.heightIn(min = Sizes.minTouchTarget),
                colors = chipColors(),
            )
        }
    }
}

@Composable
private fun SlotChips(slots: List<AvailableSlot>, loading: Boolean, selected: AvailableSlot?, zone: String, onSelect: (AvailableSlot) -> Unit) {
    Text(stringResource(Res.string.free_slots), style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
    when {
        loading -> SheetProgress()
        slots.isEmpty() -> Text(stringResource(Res.string.no_free_slots), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            slots.forEach { slot ->
                FilterChip(
                    selected = slot == selected,
                    onClick = { onSelect(slot) },
                    label = { Text(DateFormats.time(slot.start, zone, language())) },
                    modifier = Modifier.heightIn(min = Sizes.minTouchTarget),
                    colors = chipColors(),
                )
            }
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = AppTheme.colors.softAccent,
    selectedLabelColor = AppTheme.colors.onSoftAccent,
)

data class BookingSheetActions(
    val onTrainer: (String) -> Unit,
    val onDate: (LocalDate) -> Unit,
    val onSlot: (AvailableSlot) -> Unit,
    val onPlace: (String) -> Unit,
    val onPolicyAccepted: (Boolean) -> Unit,
    val onShowPolicy: (CancellationPolicy) -> Unit,
    val onSend: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * "Poproś o sesję": trainer, day, free slot and place. The client ticks the trainer's current policy (never
 * pre-ticked). Sending creates a request, not a booking.
 */
@Composable
fun BookingRequestSheet(state: BookingSheetState, trainers: List<TrainerRef>, submitting: Boolean, offline: Boolean, actions: BookingSheetActions) {
    AppBottomSheet(onDismiss = actions.onDismiss, title = stringResource(Res.string.request_session)) {
        val options = state.options
        if (trainers.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                trainers.forEach { trainer ->
                    FilterChip(
                        selected = trainer.id == options?.trainer?.id || trainer.id == state.trainerId,
                        onClick = { actions.onTrainer(trainer.id) },
                        label = { Text(trainer.name) },
                        modifier = Modifier.heightIn(min = Sizes.minTouchTarget),
                        colors = chipColors(),
                    )
                }
            }
        }
        when {
            state.trainerId == null -> Text(stringResource(Res.string.booking_no_trainer), style = AppTheme.type.body, color = AppTheme.colors.text)
            state.loadingOptions -> SheetProgress()
            options == null -> InlineMessage(failureMessage(state.failure ?: Failure.Load), tone = MessageTone.Error)
            else -> {
                Text(options.trainer.name, style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
                Text(stringResource(Res.string.session_price, money(options.price)), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                DayChips(state.dates, state.date, actions.onDate)
                SlotChips(state.slots, state.loadingSlots, state.slot, options.timeZone, actions.onSlot)
                if (options.places.size > 1) {
                    Text(stringResource(Res.string.place), style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        options.places.forEach { place ->
                            FilterChip(
                                selected = place == state.place,
                                onClick = { actions.onPlace(place) },
                                label = { Text(place) },
                                modifier = Modifier.heightIn(min = Sizes.minTouchTarget),
                                colors = chipColors(),
                            )
                        }
                    }
                } else {
                    state.place?.let { Text(stringResource(Res.string.place_line, it), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary) }
                }
                Text(stringResource(Res.string.policy_heading, options.policy.version), style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
                CancellationPolicySummary(options.policy.rules, price = options.price)
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = Sizes.minTouchTarget)
                        .toggleable(value = state.policyAccepted, role = Role.Checkbox, onValueChange = actions.onPolicyAccepted),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Checkbox(checked = state.policyAccepted, onCheckedChange = null)
                    Text(stringResource(Res.string.accept_policy, options.trainer.name), style = AppTheme.type.body, color = AppTheme.colors.text, modifier = Modifier.weight(1f))
                }
                AppTextButton(stringResource(Res.string.show_policy), onClick = { actions.onShowPolicy(options.policy) })
                if (offline) InlineMessage(stringResource(Res.string.offline_action), tone = MessageTone.Offline)
                state.failure?.let { InlineMessage(failureMessage(it), tone = MessageTone.Error) }
                Text(stringResource(Res.string.request_not_booking), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                PrimaryButton(
                    stringResource(Res.string.send_request),
                    onClick = actions.onSend,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canSend,
                    loading = submitting,
                )
            }
        }
    }
}

data class RescheduleSheetActions(
    val onDate: (LocalDate) -> Unit,
    val onSlot: (AvailableSlot) -> Unit,
    val onSend: () -> Unit,
    val onDismiss: () -> Unit,
)

/** "Zmień termin": a free slot from the trainer's availability. The original session stays confirmed. */
@Composable
fun RescheduleSheet(state: RescheduleSheetState, submitting: Boolean, offline: Boolean, actions: RescheduleSheetActions) {
    val session = state.session
    AppBottomSheet(onDismiss = actions.onDismiss, title = stringResource(Res.string.reschedule)) {
        Text(
            stringResource(Res.string.current_time_line, shortDateTime(session.session.start, session.zone)),
            style = AppTheme.type.compactBody,
            color = AppTheme.colors.textSecondary,
        )
        DayChips(state.dates, state.date, actions.onDate)
        SlotChips(state.slots, state.loadingSlots, state.slot, session.zone, actions.onSlot)
        state.fee?.let { preview ->
            InlineMessage(
                stringResource(Res.string.late_reschedule_fee, "${preview.fee.policyPercent}%", money(preview.fee.amount)),
                tone = MessageTone.Warning,
            )
        }
        Text(stringResource(Res.string.original_stays_confirmed), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
        if (offline) InlineMessage(stringResource(Res.string.offline_action), tone = MessageTone.Offline)
        state.failure?.let { InlineMessage(failureMessage(it), tone = MessageTone.Error) }
        PrimaryButton(
            stringResource(Res.string.send_reschedule),
            onClick = actions.onSend,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.slot != null,
            loading = submitting,
        )
    }
}

data class CancellationSheetActions(
    val onConfirm: () -> Unit,
    val onRefreshQuote: () -> Unit,
    val onShowPolicy: (versionId: String) -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * "Odwołaj sesję" with a fresh server quote. The amount, its settlement and the quote's expiry are shown before
 * confirming; the session is never shown as cancelled before the server confirms.
 */
@Composable
fun CancellationSheet(state: CancellationSheetState, submitting: Boolean, actions: CancellationSheetActions) {
    val session = state.session
    AppBottomSheet(onDismiss = actions.onDismiss, title = stringResource(Res.string.cancel_title)) {
        Text(
            dayTitle(session.session.start.dateIn(session.zone)) + " · " + hours(session.session.start, session.session.end, session.zone),
            style = AppTheme.type.itemTitle,
            color = AppTheme.colors.text,
        )
        Text(session.trainer.name + " · " + session.session.location, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
        val quote = state.quote
        when {
            state.loadingQuote -> {
                SheetProgress()
                Text(stringResource(Res.string.quote_loading), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
            }
            quote == null -> InlineMessage(failureMessage(state.failure ?: Failure.Load), tone = MessageTone.Error, action = {
                AppTextButton(stringResource(Res.string.retry), onClick = actions.onRefreshQuote)
            })
            else -> {
                val fee = quote.fee
                when {
                    fee.usesFreeCancellation -> InlineMessage(stringResource(Res.string.quote_free_late, "${fee.policyPercent}%"), tone = MessageTone.Info)
                    fee.late && !fee.free -> InlineMessage(stringResource(Res.string.quote_late, "${fee.policyPercent}%", money(fee.amount)), tone = MessageTone.Warning)
                    else -> InlineMessage(stringResource(Res.string.quote_free), tone = MessageTone.Success)
                }
                if (!fee.free) {
                    Text(
                        when (quote.chargeKind) {
                            ChargeKind.Online -> stringResource(Res.string.settle_online, money(fee.amount))
                            ChargeKind.Cash -> stringResource(Res.string.settle_cash, money(fee.amount))
                            ChargeKind.PackageCredit -> stringResource(Res.string.settle_package, "${fee.policyPercent}%")
                        },
                        style = AppTheme.type.body,
                        color = AppTheme.colors.text,
                    )
                }
                Text(
                    stringResource(Res.string.free_late_left, quote.freeLateCancellationsLeft),
                    style = AppTheme.type.compactBody,
                    color = AppTheme.colors.textSecondary,
                )
                if (state.quoteUsable) {
                    val minutes = state.secondsLeft / 60
                    val seconds = state.secondsLeft % 60
                    Text(
                        stringResource(Res.string.quote_valid_for, "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"),
                        style = AppTheme.type.compactBody,
                        color = AppTheme.colors.textSecondary,
                    )
                } else {
                    InlineMessage(
                        stringResource(if (state.stale) Res.string.quote_stale else Res.string.quote_expired),
                        tone = MessageTone.Warning,
                    )
                    TonalButton(stringResource(Res.string.refresh_quote), onClick = actions.onRefreshQuote, modifier = Modifier.fillMaxWidth(), enabled = !submitting)
                }
                AppTextButton(stringResource(Res.string.policy_link, session.trainer.name), onClick = { actions.onShowPolicy(quote.policyVersionId) })
                state.failure?.let { failure ->
                    if (failure != Failure.QuoteExpired) InlineMessage(failureMessage(failure), tone = MessageTone.Error)
                }
                DestructiveButton(
                    if (fee.free) stringResource(Res.string.confirm_cancel_free) else stringResource(Res.string.confirm_cancel_fee, money(fee.amount)),
                    onClick = actions.onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.quoteUsable,
                    loading = submitting,
                )
            }
        }
        AppTextButton(stringResource(Res.string.back_to_session), onClick = actions.onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !submitting)
    }
}

/** A policy version, read-only: the version the client accepted at booking, or the current one when booking. */
@Composable
fun PolicySheet(policy: CancellationPolicy, onDismiss: () -> Unit) {
    AppBottomSheet(onDismiss = onDismiss, title = stringResource(Res.string.policy_title)) {
        Text(
            stringResource(
                Res.string.policy_version_line,
                policy.version,
                DateFormats.date(policy.createdAt.dateIn("Europe/Warsaw"), language(), "d MMMM yyyy"),
            ),
            style = AppTheme.type.compactBody,
            color = AppTheme.colors.textSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) { CancellationPolicySummary(policy.rules) }
        Text(stringResource(Res.string.policy_server_note), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
    }
}
