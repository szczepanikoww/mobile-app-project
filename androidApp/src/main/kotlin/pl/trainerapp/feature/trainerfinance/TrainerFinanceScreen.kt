package pl.trainerapp.feature.trainerfinance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PriceCheck
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainerapp.R
import pl.trainerapp.core.designsystem.AppShapes
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.core.designsystem.Corner
import pl.trainerapp.core.designsystem.CornerArc
import pl.trainerapp.core.designsystem.EmptyState
import pl.trainerapp.core.designsystem.ErrorState
import pl.trainerapp.core.designsystem.InlineBanner
import pl.trainerapp.core.designsystem.SectionHeader
import pl.trainerapp.core.designsystem.SkeletonBlock
import pl.trainerapp.core.designsystem.Spacing
import pl.trainerapp.core.designsystem.frosted
import pl.trainerapp.core.format.formatPaymentWhen
import pl.trainerapp.core.format.formatPln
import pl.trainerapp.core.format.formatPlnSigned
import pl.trainerapp.core.format.formatUpdatedAt
import pl.trainerapp.feature.trainerfinance.model.FinancePeriod
import pl.trainerapp.feature.trainerfinance.model.FinanceSummary
import pl.trainerapp.feature.trainerfinance.model.PaymentItem
import pl.trainerapp.feature.trainerfinance.model.PaymentMethod
import pl.trainerapp.feature.trainerfinance.model.RevenueBar
import java.time.Instant

private val Gutter = Modifier.padding(horizontal = Spacing.screenGutter)
private val SectionInnerGap = Spacing.sm
private val ChartTrackHeight = 128.dp
private val ChartBubbleHeight = 24.dp
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
            contentPadding = PaddingValues(top = Spacing.xs, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxl),
        ) {
            when {
                state.status == FinanceLoadStatus.Loading -> item { LoadingContent() }
                state.status == FinanceLoadStatus.Error -> item {
                    ErrorState(
                        title = stringResource(R.string.error_load_finance),
                        retryLabel = stringResource(R.string.action_retry),
                        onRetry = { onAction(TrainerFinanceAction.Refresh) },
                        modifier = Gutter,
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

    item { FinanceHeader(state.period, onAction) }

    if (state.isOffline && updated != null) {
        item { InlineBanner(stringResource(R.string.offline_banner, formatUpdatedAt(updated)), modifier = Gutter) }
    }

    item { HeroCard(summary) }
    item { RevenueChart(summary) }
    item { MetricsSection(summary) }
    item { PaymentsSection(summary, onAction) }
    item { PayoutCard(summary, enabled = !state.isOffline, onAction = onAction) }
}

@Composable
private fun LoadingContent() {
    Column(modifier = Gutter, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SkeletonBlock(height = 44.dp, modifier = Modifier.fillMaxWidth(0.7f))
        SkeletonBlock(height = 180.dp)
        SkeletonBlock(height = 220.dp)
        SkeletonBlock(height = 64.dp)
    }
}

@Composable
private fun FinanceHeader(selected: FinancePeriod, onAction: (TrainerFinanceAction) -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().then(Gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                stringResource(R.string.finance_overline).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            Text(
                stringResource(R.string.finance_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        PeriodSwitcher(selected) { onAction(TrainerFinanceAction.SelectPeriod(it)) }
    }
}

@Composable
private fun periodLabel(period: FinancePeriod): String = stringResource(
    when (period) {
        FinancePeriod.Week -> R.string.finance_period_week
        FinancePeriod.Month -> R.string.finance_period_month
        FinancePeriod.Year -> R.string.finance_period_year
    },
)

@Composable
private fun PeriodSwitcher(selected: FinancePeriod, onSelect: (FinancePeriod) -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .clip(AppShapes.pill)
            .background(colors.chipSurface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FinancePeriod.entries.forEach { period ->
            val active = period == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(AppShapes.pill)
                    .background(if (active) colors.brand else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(period) }
                    .semantics { this.selected = active }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    periodLabel(period),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) Color.White else colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun periodRevenueLabel(period: FinancePeriod): String = stringResource(
    when (period) {
        FinancePeriod.Week -> R.string.finance_revenue_week
        FinancePeriod.Month -> R.string.finance_revenue_month
        FinancePeriod.Year -> R.string.finance_revenue_year
    },
)

@Composable
private fun HeroCard(summary: FinanceSummary) {
    val colors = AppTheme.colors
    val percent = summary.changePercent
    Box(modifier = Gutter.fillMaxWidth().frosted(AppShapes.frame, alpha = 0.95f).clip(AppShapes.frame)) {
        CornerArc(Corner.TopEnd, 72.dp)
        Column(modifier = Modifier.padding(Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IconBubble(Icons.Outlined.AccountBalanceWallet, background = colors.softBrand, tint = colors.brand)
                Text(
                    periodRevenueLabel(summary.period),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                if (percent != null) ChangeBadge(percent)
            }
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatPln(summary.revenue).removeSuffix(" zł"),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        " zł",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.brand,
                    )
                }
                if (percent != null) {
                    Text(
                        stringResource(R.string.finance_vs_previous, formatPlnSigned(summary.change)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            summary.goal?.let { goal -> GoalProgress(goal = formatPln(goal), progress = summary.goalProgress ?: 0f) }
        }
    }
}

@Composable
private fun ChangeBadge(percent: Int) {
    val colors = AppTheme.colors
    val positive = percent >= 0
    val tint = if (positive) colors.success else colors.dangerText
    Row(
        modifier = Modifier
            .clip(AppShapes.pill)
            .background(if (positive) colors.successContainer else colors.softBrand)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ArrowUpward,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp).rotate(if (positive) 0f else 180f),
        )
        Text(
            "${if (positive) "+" else ""}$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun GoalProgress(goal: String, progress: Float) {
    val colors = AppTheme.colors
    val percent = (progress * 100).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.finance_goal, goal),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = colors.textMuted,
            )
            Text("$percent%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(AppShapes.pill)
                .background(colors.chipSurface)
                .semantics { contentDescription = "$percent%" },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceAtLeast(0.02f))
                    .clip(AppShapes.pill)
                    .background(Brush.horizontalGradient(listOf(colors.brand, Color(0xFFFFB693)))),
            )
        }
    }
}

@Composable
private fun RevenueChart(summary: FinanceSummary) {
    val colors = AppTheme.colors
    val max = summary.bars.maxOfOrNull { it.revenue.grosze }?.coerceAtLeast(1L) ?: 1L
    Column(
        modifier = Gutter.fillMaxWidth().frosted(AppShapes.frame, alpha = 0.95f).padding(Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(Icons.Outlined.BarChart, contentDescription = null, tint = colors.brand, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.finance_chart_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
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
    val colors = AppTheme.colors
    val description = "${bar.label}: ${formatPln(bar.revenue)}"
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Fixed-height slot so every track starts at the same y, with or without the value bubble.
        Box(modifier = Modifier.height(ChartBubbleHeight), contentAlignment = Alignment.Center) {
            if (current) {
                Text(
                    formatPln(bar.revenue),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(AppShapes.pill)
                        .background(colors.brand)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .width(if (current) 32.dp else 28.dp)
                .height(ChartTrackHeight)
                .clip(AppShapes.pill)
                .background(if (current) colors.softBrand else colors.chipSurface),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((ChartTrackHeight * ratio).coerceAtLeast(MinChartFill))
                    .clip(AppShapes.pill)
                    .background(
                        if (current) Brush.verticalGradient(listOf(colors.brand, Color(0xFFA04100)))
                        else Brush.verticalGradient(listOf(Color(0xFFCBD5E1), Color(0xFFB6C3D6))),
                    ),
            )
        }
        Text(
            bar.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (current) colors.brand else colors.textMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private enum class Tone { Brand, Neutral, Success, Warning, Danger }

@Composable
private fun MetricsSection(summary: FinanceSummary) {
    val colors = AppTheme.colors
    Column(modifier = Gutter, verticalArrangement = Arrangement.spacedBy(SectionInnerGap)) {
        SectionHeader(
            title = stringResource(R.string.finance_metrics),
            action = {
                Text(
                    stringResource(R.string.finance_metrics_caption),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            MetricTile(
                Icons.Outlined.FitnessCenter, Tone.Brand,
                value = stringResource(R.string.finance_sessions_value, summary.sessionsDone),
                label = stringResource(R.string.finance_sessions_done),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                Icons.Outlined.Payments, Tone.Neutral,
                value = formatPln(summary.sessionsValue),
                label = stringResource(R.string.finance_sessions_worth),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            MetricTile(
                Icons.Outlined.EventBusy, Tone.Warning,
                value = formatPln(summary.cancellationFees),
                label = stringResource(R.string.finance_cancellation_fees),
                badge = if (summary.cancellationFeeCount > 0) {
                    stringResource(R.string.finance_fee_count, summary.cancellationFeeCount)
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                Icons.Outlined.PendingActions, if (summary.overdue.grosze > 0) Tone.Danger else Tone.Neutral,
                value = formatPln(summary.overdue),
                label = stringResource(R.string.finance_overdue),
                badge = if (summary.overdueClients > 0) {
                    stringResource(R.string.finance_overdue_people, summary.overdueClients)
                } else {
                    null
                },
                emphasizeValue = summary.overdue.grosze > 0,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            MetricTile(
                Icons.Outlined.PriceCheck, Tone.Success,
                value = formatPln(summary.paymentsTotal),
                label = stringResource(R.string.finance_payments_total),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                Icons.Outlined.CreditCard, Tone.Neutral,
                value = formatPln(summary.lastPayout),
                label = stringResource(R.string.finance_paid_out),
                labelColor = colors.success,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricTile(
    icon: ImageVector,
    tone: Tone,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    emphasizeValue: Boolean = false,
    labelColor: Color = AppTheme.colors.textMuted,
) {
    val colors = AppTheme.colors
    val (bubble, tint) = when (tone) {
        Tone.Brand -> colors.softBrand to colors.brand
        Tone.Neutral -> colors.chipSurface to colors.textMuted
        Tone.Success -> colors.successContainer to colors.success
        Tone.Warning -> colors.softBrand to colors.warning
        Tone.Danger -> Color(0xFFFFDAD6) to colors.danger
    }
    Column(modifier = modifier.frosted(AppShapes.tile, alpha = 0.9f).padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconBubble(icon, background = bubble, tint = tint)
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tone == Tone.Danger) colors.dangerText else colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(lineHeight = 24.sp),
            color = if (emphasizeValue) colors.dangerText else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = labelColor,
            maxLines = 2,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun IconBubble(icon: ImageVector, background: Color, tint: Color) {
    Box(
        modifier = Modifier.size(32.dp).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PaymentsSection(summary: FinanceSummary, onAction: (TrainerFinanceAction) -> Unit) {
    val colors = AppTheme.colors
    Column(modifier = Gutter, verticalArrangement = Arrangement.spacedBy(SectionInnerGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = colors.brand, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.finance_recent_payments),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (summary.payments.isNotEmpty()) {
                Text(
                    stringResource(R.string.finance_see_all),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.brand,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onAction(TrainerFinanceAction.OpenAllPayments) }
                        .padding(vertical = 14.dp),
                )
            }
        }
        if (summary.payments.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.finance_empty_title),
                body = stringResource(R.string.finance_empty_body),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                summary.payments.forEach { payment ->
                    PaymentRow(payment, summary.generatedAt) { onAction(TrainerFinanceAction.OpenPayment(payment)) }
                }
            }
        }
    }
}

@Composable
private fun PaymentRow(payment: PaymentItem, now: Instant, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val methodLabel = stringResource(
        when (payment.method) {
            PaymentMethod.Blik -> R.string.finance_method_blik
            PaymentMethod.Cash -> R.string.finance_method_cash
            PaymentMethod.Card -> R.string.finance_method_card
        },
    )
    val methodIcon = when (payment.method) {
        PaymentMethod.Blik -> Icons.Outlined.Smartphone
        PaymentMethod.Cash -> Icons.Outlined.Payments
        PaymentMethod.Card -> Icons.Outlined.CreditCard
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .frosted(AppShapes.tile, alpha = 0.9f)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier.size(44.dp).background(if (payment.isFee) colors.softBrand else colors.chipSurface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(methodIcon, contentDescription = null, tint = if (payment.isFee) colors.brand else colors.textMuted, modifier = Modifier.size(22.dp))
            }
            Box(
                modifier = Modifier.size(16.dp).background(if (payment.isFee) colors.warning else colors.success, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (payment.isFee) Icons.Outlined.Warning else Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                payment.clientName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.finance_payment_detail, payment.description, methodLabel),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatPlnSigned(payment.amount),
                style = MaterialTheme.typography.titleMedium,
                color = if (payment.isFee) MaterialTheme.colorScheme.onSurface else colors.success,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                formatPaymentWhen(payment.at, payment.zone, now),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun PayoutCard(summary: FinanceSummary, enabled: Boolean, onAction: (TrainerFinanceAction) -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Gutter
            .fillMaxWidth()
            .clip(AppShapes.tile)
            .background(colors.chipSurface)
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.finance_payout_title),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Text(
                stringResource(R.string.finance_stripe_balance, formatPln(summary.stripeBalance, alwaysDecimals = true)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(AppShapes.pill)
                .background(if (enabled && summary.stripeBalance.grosze > 0) colors.brand else colors.border)
                .clickable(
                    enabled = enabled && summary.stripeBalance.grosze > 0,
                    role = Role.Button,
                ) { onAction(TrainerFinanceAction.RequestPayout) }
                .padding(horizontal = Spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.finance_payout_action),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}
