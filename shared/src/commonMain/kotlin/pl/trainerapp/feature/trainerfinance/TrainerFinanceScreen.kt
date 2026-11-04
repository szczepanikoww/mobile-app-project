package pl.trainerapp.feature.trainerfinance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.PriceCheck
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.stringResource
import pl.trainer.resources.*
import pl.trainer.ui.AppColors
import pl.trainer.ui.EmptyContent
import pl.trainer.ui.InfoBanner
import pl.trainer.ui.Layout
import pl.trainer.ui.PillTabs
import pl.trainerapp.core.designsystem.IconCircle
import pl.trainerapp.core.designsystem.OutlinedTile
import pl.trainerapp.core.designsystem.ScreenError
import pl.trainerapp.core.designsystem.ScreenLoading
import pl.trainerapp.core.designsystem.ScreenSectionTitle
import pl.trainerapp.core.format.formatPaymentWhen
import pl.trainerapp.core.format.formatPln
import pl.trainerapp.core.format.formatPlnSigned
import pl.trainerapp.core.format.formatUpdatedAt
import pl.trainerapp.feature.trainerfinance.model.FinancePeriod
import pl.trainerapp.feature.trainerfinance.model.FinanceSummary
import pl.trainerapp.feature.trainerfinance.model.PaymentItem
import pl.trainerapp.feature.trainerfinance.model.PaymentMethod
import pl.trainerapp.feature.trainerfinance.model.RevenueBar
import kotlin.time.Instant

private val ChartTrackHeight = 128.dp
private val ChartValueHeight = 20.dp
private val MinChartFill = 8.dp

/** Navigation- and DI-aware entry point. Everything below it only renders state and emits actions. */
@Composable
fun TrainerFinanceRoute(
    repository: TrainerFinanceRepository,
    onNavigate: (TrainerFinanceDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { TrainerFinanceViewModel(repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TrainerFinanceEvent.Navigate -> onNavigate(event.destination)
            }
        }
    }

    TrainerFinanceScreen(state = state, onAction = viewModel::onAction, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerFinanceScreen(
    state: TrainerFinanceUiState,
    onAction: (TrainerFinanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { onAction(TrainerFinanceAction.Refresh) },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Layout.gutter, vertical = Layout.padding),
            verticalArrangement = Arrangement.spacedBy(Layout.medium),
        ) {
            item {
                PillTabs(
                    FinancePeriod.entries,
                    state.period,
                    label = { periodLabel(it) },
                    onSelect = { onAction(TrainerFinanceAction.SelectPeriod(it)) },
                )
            }
            when {
                state.status == FinanceLoadStatus.Loading -> item { ScreenLoading(stringResource(Res.string.screen_loading)) }
                state.status == FinanceLoadStatus.Error -> item {
                    ScreenError(
                        title = stringResource(Res.string.error_load_finance),
                        body = stringResource(Res.string.screen_error_body),
                        retryLabel = stringResource(Res.string.action_retry),
                        onRetry = { onAction(TrainerFinanceAction.Refresh) },
                    )
                }
                state.summary != null -> content(state, state.summary, onAction)
            }
        }
    }
}

private fun LazyListScope.content(
    state: TrainerFinanceUiState,
    summary: FinanceSummary,
    onAction: (TrainerFinanceAction) -> Unit,
) {
    val updated = state.lastUpdated
    if (state.isOffline && updated != null) {
        item { InfoBanner(stringResource(Res.string.offline_banner, formatUpdatedAt(updated)), Icons.Outlined.CloudOff) }
    }

    item { RevenueCard(summary) }
    item { RevenueChart(summary) }
    item { MetricsSection(summary) }

    item {
        ScreenSectionTitle(stringResource(Res.string.finance_recent_payments), Modifier.padding(top = Layout.small)) {
            if (summary.payments.isNotEmpty()) {
                TextButton(onClick = { onAction(TrainerFinanceAction.OpenAllPayments) }, modifier = Modifier.heightIn(min = Layout.target)) {
                    Text(stringResource(Res.string.finance_see_all))
                }
            }
        }
    }
    if (summary.payments.isEmpty()) {
        item {
            EmptyContent(
                Icons.Outlined.Payments,
                stringResource(Res.string.finance_empty_title),
                stringResource(Res.string.finance_empty_body),
            )
        }
    } else {
        items(summary.payments, key = { it.id }) { payment ->
            PaymentRow(payment, summary.generatedAt) { onAction(TrainerFinanceAction.OpenPayment(payment)) }
        }
    }

    item { PayoutCard(summary, enabled = !state.isOffline, onAction = onAction) }
}

@Composable
private fun periodLabel(period: FinancePeriod): String = stringResource(
    when (period) {
        FinancePeriod.Week -> Res.string.finance_period_week
        FinancePeriod.Month -> Res.string.finance_period_month
        FinancePeriod.Year -> Res.string.finance_period_year
    },
)

@Composable
private fun periodRevenueLabel(period: FinancePeriod): String = stringResource(
    when (period) {
        FinancePeriod.Week -> Res.string.finance_revenue_week
        FinancePeriod.Month -> Res.string.finance_revenue_month
        FinancePeriod.Year -> Res.string.finance_revenue_year
    },
)

@Composable
private fun RevenueCard(summary: FinanceSummary) {
    val percent = summary.changePercent
    OutlinedTile(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
            Text(
                periodRevenueLabel(summary.period),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (percent != null) ChangeLabel(percent)
        }
        Text(formatPln(summary.revenue), style = MaterialTheme.typography.headlineMedium)
        if (percent != null) {
            Text(
                stringResource(Res.string.finance_vs_previous, formatPlnSigned(summary.change)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        summary.goal?.let { goal -> GoalProgress(goal = formatPln(goal), progress = summary.goalProgress ?: 0f) }
    }
}

/** Change against the previous period: icon, sign and colour, as a status pill. */
@Composable
private fun ChangeLabel(percent: Int) {
    val positive = percent >= 0
    Surface(
        color = if (positive) AppColors.successSurface else AppColors.errorSurface,
        contentColor = if (positive) AppColors.success else AppColors.errorText,
        shape = Layout.pill,
    ) {
        Row(
            Modifier.padding(horizontal = Layout.small, vertical = Layout.tiny),
            horizontalArrangement = Arrangement.spacedBy(Layout.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (positive) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown, contentDescription = null, modifier = Modifier.size(14.dp))
            Text("${if (positive) "+" else ""}$percent%", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun GoalProgress(goal: String, progress: Float) {
    val percent = (progress * 100).toInt()
    Column(Modifier.padding(top = Layout.tiny), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(Res.string.finance_goal, goal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("$percent%", style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(Layout.pill),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun RevenueChart(summary: FinanceSummary) {
    val max = summary.bars.maxOfOrNull { it.revenue.grosze }?.coerceAtLeast(1L) ?: 1L
    OutlinedTile(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.finance_chart_title), style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().padding(top = Layout.small),
            horizontalArrangement = Arrangement.spacedBy(Layout.small),
            verticalAlignment = Alignment.Bottom,
        ) {
            summary.bars.forEachIndexed { index, bar ->
                ChartColumn(bar, ratio = bar.revenue.grosze.toFloat() / max, current = index == summary.currentBar, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChartColumn(bar: RevenueBar, ratio: Float, current: Boolean, modifier: Modifier = Modifier) {
    val description = "${bar.label}: ${formatPln(bar.revenue)}"
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Layout.tiny),
    ) {
        // Fixed-height slot so every track starts at the same y, with or without the value.
        Box(Modifier.height(ChartValueHeight), contentAlignment = Alignment.Center) {
            if (current) {
                Text(formatPln(bar.revenue), style = MaterialTheme.typography.labelMedium, color = primary, maxLines = 1, softWrap = false)
            }
        }
        Box(
            Modifier
                .width(24.dp)
                .height(ChartTrackHeight)
                .clip(Layout.pill)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((ChartTrackHeight * ratio).coerceAtLeast(MinChartFill))
                    .clip(Layout.pill)
                    .background(if (current) primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Text(
            bar.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (current) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun MetricsSection(summary: FinanceSummary) {
    Column(Modifier.padding(top = Layout.small), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
        ScreenSectionTitle(stringResource(Res.string.finance_metrics)) {
            Text(
                stringResource(Res.string.finance_metrics_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
            MetricTile(
                Icons.Outlined.FitnessCenter,
                value = stringResource(Res.string.finance_sessions_value, summary.sessionsDone),
                label = stringResource(Res.string.finance_sessions_done),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                Icons.Outlined.Payments,
                value = formatPln(summary.sessionsValue),
                label = stringResource(Res.string.finance_sessions_worth),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
            MetricTile(
                Icons.Outlined.EventBusy,
                value = formatPln(summary.cancellationFees),
                label = stringResource(Res.string.finance_cancellation_fees),
                badge = if (summary.cancellationFeeCount > 0) {
                    stringResource(Res.string.finance_fee_count, summary.cancellationFeeCount)
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            val overdue = summary.overdue.grosze > 0
            MetricTile(
                Icons.Outlined.PendingActions,
                value = formatPln(summary.overdue),
                label = stringResource(Res.string.finance_overdue),
                badge = if (summary.overdueClients > 0) {
                    stringResource(Res.string.finance_overdue_people, summary.overdueClients)
                } else {
                    null
                },
                tint = if (overdue) AppColors.errorText else MaterialTheme.colorScheme.primary,
                valueColor = if (overdue) AppColors.errorText else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
            MetricTile(
                Icons.Outlined.PriceCheck,
                value = formatPln(summary.paymentsTotal),
                label = stringResource(Res.string.finance_payments_total),
                tint = AppColors.success,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                Icons.Outlined.CreditCard,
                value = formatPln(summary.lastPayout),
                label = stringResource(Res.string.finance_paid_out),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricTile(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    OutlinedTile(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.weight(1f))
            if (badge != null) {
                Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun PaymentRow(payment: PaymentItem, now: Instant, onClick: () -> Unit) {
    val methodLabel = stringResource(
        when (payment.method) {
            PaymentMethod.Blik -> Res.string.finance_method_blik
            PaymentMethod.Cash -> Res.string.finance_method_cash
            PaymentMethod.Card -> Res.string.finance_method_card
        },
    )
    val methodIcon = when (payment.method) {
        PaymentMethod.Blik -> Icons.Outlined.Smartphone
        PaymentMethod.Cash -> Icons.Outlined.Payments
        PaymentMethod.Card -> Icons.Outlined.CreditCard
    }
    OutlinedTile(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.medium), verticalAlignment = Alignment.CenterVertically) {
            if (payment.isFee) {
                IconCircle(methodIcon, AppColors.warningSurface, AppColors.warningText)
            } else {
                IconCircle(methodIcon, AppColors.successSurface, AppColors.success)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(payment.clientName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(Res.string.finance_payment_detail, payment.description, methodLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(
                    formatPlnSigned(payment.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (payment.isFee) MaterialTheme.colorScheme.onSurface else AppColors.success,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    formatPaymentWhen(payment.at, payment.zone, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PayoutCard(summary: FinanceSummary, enabled: Boolean, onAction: (TrainerFinanceAction) -> Unit) {
    OutlinedTile(Modifier.fillMaxWidth().padding(top = Layout.small), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(stringResource(Res.string.finance_payout_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(Res.string.finance_stripe_balance, formatPln(summary.stripeBalance, alwaysDecimals = true)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onAction(TrainerFinanceAction.RequestPayout) },
            enabled = enabled && summary.stripeBalance.grosze > 0,
            shape = Layout.pill,
            modifier = Modifier.fillMaxWidth().padding(top = Layout.tiny).heightIn(min = Layout.target),
        ) {
            Text(stringResource(Res.string.finance_payout_action))
        }
    }
}
