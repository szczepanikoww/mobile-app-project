package com.trainingapp.feature.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.GroupedSurface
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.ListRow
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.RowDivider
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.client.presentation.PaymentSheetState
import com.trainingapp.feature.client.presentation.WalletUiState
import com.trainingapp.feature.client.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.Charge
import pl.trainer.domain.ChargeKind
import pl.trainer.domain.ChargeReason
import pl.trainer.domain.ChargeState
import pl.trainer.domain.Failure
import pl.trainer.domain.PaymentMethod
import pl.trainer.domain.PaymentState
import pl.trainer.domain.SessionPackage
import pl.trainer.ui.DateFormats

internal fun chargeReasonText(reason: ChargeReason): StringResource = when (reason) {
    ChargeReason.LateCancellation -> Res.string.reason_late_cancellation
    ChargeReason.LateReschedule -> Res.string.reason_late_reschedule
    ChargeReason.NoShow -> Res.string.reason_no_show
}

private fun PaymentMethod.label(): StringResource = when (this) {
    PaymentMethod.Card -> Res.string.method_card
    PaymentMethod.Blik -> Res.string.method_blik
    PaymentMethod.Przelewy24 -> Res.string.method_p24
    PaymentMethod.TestMode -> Res.string.method_test
}

/**
 * B3 "Portfel": what the client owes, what they have and what they paid. No package purchase (D1), no invoice PDF
 * (D2), no saved payment methods; renewal and expiry are shown only as the data gives them (G13).
 */
@Composable
fun WalletContent(state: WalletUiState, onPay: (Charge) -> Unit, onRetry: () -> Unit) {
    val wallet = state.wallet
    when {
        state.loading -> LoadingState()
        wallet == null -> ErrorState(failureMessage(state.loadFailure ?: Failure.Load), onRetry = onRetry)
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            if (state.offline) InlineMessage(stringResource(Res.string.payment_needs_connection), tone = MessageTone.Offline)
            val owed = state.outstanding.filter { it.kind != ChargeKind.PackageCredit }
            if (owed.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    SectionHeader(stringResource(Res.string.to_pay))
                    owed.forEach { ChargeCard(it, state.offline, onPay) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                SectionHeader(stringResource(Res.string.packages))
                if (wallet.packages.isEmpty()) {
                    Text(stringResource(Res.string.no_package), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                }
                wallet.packages.forEach { PackageCard(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                SectionHeader(stringResource(Res.string.payment_history))
                val packageCredits = wallet.charges.filter { it.kind == ChargeKind.PackageCredit }
                if (wallet.payments.isEmpty() && packageCredits.isEmpty()) {
                    Text(stringResource(Res.string.no_payments), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                } else {
                    GroupedSurface {
                        wallet.payments.forEachIndexed { index, payment ->
                            if (index > 0) RowDivider()
                            val (label, tone) = when (payment.state) {
                                PaymentState.Paid -> Res.string.payment_paid to StatusTone.Success
                                PaymentState.Pending -> Res.string.payment_pending to StatusTone.Warning
                                PaymentState.Failed -> Res.string.payment_failed to StatusTone.Error
                                PaymentState.Refunded -> Res.string.payment_refunded to StatusTone.Neutral
                            }
                            ListRow(
                                title = stringResource(payment.reason?.let(::chargeReasonText) ?: Res.string.reason_session),
                                supporting = DateFormats.date(payment.at.dateIn("Europe/Warsaw"), language(), "d MMM yyyy") + " · " +
                                    stringResource(payment.method.label()),
                                badges = { StatusChip(stringResource(label), tone = tone) },
                                trailing = { Text(money(payment.amount), style = AppTheme.type.bodyStrong, color = AppTheme.colors.text) },
                            )
                        }
                        packageCredits.forEachIndexed { index, charge ->
                            if (index > 0 || wallet.payments.isNotEmpty()) RowDivider()
                            ListRow(
                                title = stringResource(chargeReasonText(charge.reason)),
                                supporting = stringResource(Res.string.settled_from_package, "${charge.packagePercent ?: 0}%"),
                                badges = { StatusChip(stringResource(Res.string.payment_settled), tone = StatusTone.Neutral) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChargeCard(charge: Charge, offline: Boolean, onPay: (Charge) -> Unit) {
    Surface(
        shape = AppShapes.featureCard,
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(chargeReasonText(charge.reason)), style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
            charge.sessionStart?.let {
                Text(
                    stringResource(Res.string.charge_session, shortDateTime(it, "Europe/Warsaw"), charge.trainer.name),
                    style = AppTheme.type.compactBody,
                    color = AppTheme.colors.textSecondary,
                )
            }
            Text(money(charge.amount), style = AppTheme.type.value, color = AppTheme.colors.text)
            when {
                charge.state == ChargeState.PaymentPending -> {
                    StatusChip(stringResource(Res.string.payment_pending), tone = StatusTone.Warning)
                    Text(stringResource(Res.string.payment_waiting_confirmation), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                }
                charge.kind == ChargeKind.Cash ->
                    Text(stringResource(Res.string.pay_trainer_cash), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                else -> PrimaryButton(
                    stringResource(Res.string.pay_amount, money(charge.amount)),
                    onClick = { onPay(charge) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !offline && charge.payableInApp,
                )
            }
        }
    }
}

@Composable
private fun PackageCard(pack: SessionPackage) {
    Surface(
        shape = AppShapes.featureCard,
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(pack.name, style = AppTheme.type.itemTitle, color = AppTheme.colors.text)
            Text(stringResource(Res.string.package_usage, pack.used, pack.total), style = AppTheme.type.body, color = AppTheme.colors.text)
            LinearProgressIndicator(
                progress = { if (pack.total > 0) pack.used.toFloat() / pack.total else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.brandStrong,
                trackColor = AppTheme.colors.subtleSurface,
            )
            Text(pack.trainer.name, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
            pack.validUntil?.let {
                Text(
                    stringResource(Res.string.package_valid_until, DateFormats.date(it, language(), "d MMMM yyyy")),
                    style = AppTheme.type.compactBody,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }
    }
}

/** The payment sheet (test mode in this phase). A submitted payment is pending until the server confirms it. */
@Composable
fun PaymentSheet(
    state: PaymentSheetState,
    submitting: Boolean,
    offline: Boolean,
    onMethod: (PaymentMethod) -> Unit,
    onPay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val charge = state.charge
    AppBottomSheet(onDismiss = onDismiss, title = stringResource(Res.string.payment_title)) {
        Text(money(charge.amount), style = AppTheme.type.value, color = AppTheme.colors.text)
        Text(stringResource(chargeReasonText(charge.reason)) + " · " + charge.trainer.name, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
        Text(stringResource(Res.string.payment_method), style = AppTheme.type.label, color = AppTheme.colors.textSecondary)
        Column(Modifier.selectableGroup()) {
            PaymentMethod.entries.forEach { method ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = Sizes.minTouchTarget)
                        .selectable(selected = method == state.method, role = Role.RadioButton, onClick = { onMethod(method) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    RadioButton(selected = method == state.method, onClick = null)
                    Text(stringResource(method.label()), style = AppTheme.type.body, color = AppTheme.colors.text)
                }
            }
        }
        InlineMessage(stringResource(Res.string.test_mode_note))
        if (offline) InlineMessage(stringResource(Res.string.payment_needs_connection), tone = MessageTone.Offline)
        state.failure?.let { InlineMessage(failureMessage(it), tone = MessageTone.Error) }
        PrimaryButton(
            stringResource(Res.string.pay_amount, money(charge.amount)),
            onClick = onPay,
            modifier = Modifier.fillMaxWidth(),
            enabled = !offline,
            loading = submitting,
        )
    }
}
