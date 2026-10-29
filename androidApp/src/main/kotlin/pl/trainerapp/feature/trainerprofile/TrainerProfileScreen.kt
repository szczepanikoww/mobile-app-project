package pl.trainerapp.feature.trainerprofile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.PriceCheck
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainerapp.R
import pl.trainerapp.core.designsystem.AppShapes
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.core.designsystem.Avatar
import pl.trainerapp.core.designsystem.Corner
import pl.trainerapp.core.designsystem.CornerArc
import pl.trainerapp.core.designsystem.DetailHeader
import pl.trainerapp.core.designsystem.ErrorState
import pl.trainerapp.core.designsystem.InlineBanner
import pl.trainerapp.core.designsystem.PillTabs
import pl.trainerapp.core.designsystem.PrimaryButton
import pl.trainerapp.core.designsystem.SecondaryButton
import pl.trainerapp.core.designsystem.SectionHeader
import pl.trainerapp.core.designsystem.SkeletonBlock
import pl.trainerapp.core.designsystem.Spacing
import pl.trainerapp.core.designsystem.frosted
import pl.trainerapp.core.format.formatPln
import pl.trainerapp.core.format.formatUpdatedAt
import pl.trainerapp.feature.trainerprofile.model.AvailabilityDay
import pl.trainerapp.feature.trainerprofile.model.PayoutAccount
import pl.trainerapp.feature.trainerprofile.model.ProfileDraft
import pl.trainerapp.feature.trainerprofile.model.SettingKey
import pl.trainerapp.feature.trainerprofile.model.TrainerProfile
import pl.trainerapp.feature.trainerprofile.model.TrainerSettings

private val Gutter = Modifier.padding(horizontal = Spacing.screenGutter)

/** Navigation- and DI-aware entry point. [onUnavailable] covers actions whose features do not exist yet. */
@Composable
fun TrainerProfileRoute(
    repository: TrainerProfileRepository,
    onBack: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { TrainerProfileViewModel(repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrainerProfileScreen(state, viewModel::onAction, onBack, onUnavailable, modifier)
}

@Composable
private fun tabLabel(tab: ProfileTab): String = stringResource(
    when (tab) {
        ProfileTab.Profile -> R.string.profile_tab_profile
        ProfileTab.Settings -> R.string.profile_tab_settings
        ProfileTab.Availability -> R.string.profile_tab_availability
        ProfileTab.Payments -> R.string.profile_tab_payments
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerProfileScreen(
    state: TrainerProfileUiState,
    onAction: (TrainerProfileAction) -> Unit,
    onBack: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(R.string.profile_title), onBack = onBack)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onAction(TrainerProfileAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = Spacing.lg, bottom = Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxl),
            ) {
                when (state.status) {
                    ProfileLoadStatus.Loading -> item {
                        Column(modifier = Gutter, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            SkeletonBlock(height = 44.dp)
                            SkeletonBlock(height = 140.dp)
                            SkeletonBlock(height = 220.dp)
                        }
                    }
                    ProfileLoadStatus.Error -> item {
                        ErrorState(
                            title = stringResource(R.string.error_load_profile),
                            retryLabel = stringResource(R.string.action_retry),
                            onRetry = { onAction(TrainerProfileAction.Refresh) },
                            modifier = Gutter,
                        )
                    }
                    ProfileLoadStatus.Content -> content(state, onAction, onUnavailable)
                }
            }
        }
    }
}

private fun LazyListScope.content(
    state: TrainerProfileUiState,
    onAction: (TrainerProfileAction) -> Unit,
    onUnavailable: () -> Unit,
) {
    item {
        PillTabs(
            items = ProfileTab.entries,
            selected = state.tab,
            label = { tabLabel(it) },
            onSelect = { onAction(TrainerProfileAction.SelectTab(it)) },
            modifier = Gutter,
        )
    }
    val updated = state.lastUpdated
    if (state.isOffline && updated != null) {
        item { InlineBanner(stringResource(R.string.offline_banner, formatUpdatedAt(updated)), modifier = Gutter) }
    }
    val profile = state.profile
    val draft = state.draft
    val settings = state.settings
    val payout = state.payout
    when (state.tab) {
        ProfileTab.Profile -> if (profile != null && draft != null) {
            item { IdentityCard(profile) }
            item { ProfileForm(state, draft, onAction) }
        }
        ProfileTab.Settings -> if (settings != null) {
            item { NotificationSettings(settings, onAction) }
            item { PolicyCard() }
            item { AccountCard(onUnavailable) }
        }
        ProfileTab.Availability -> item { AvailabilityCard(state.availability, onAction) }
        ProfileTab.Payments -> if (payout != null) {
            item { PayoutsCard(payout, onUnavailable) }
        }
    }
}

// ---------------------------------------------------------------- Shared pieces

@Composable
private fun CardColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = Gutter.then(modifier).fillMaxWidth().frosted(AppShapes.frame, alpha = 0.95f).padding(Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Box(modifier = Gutter) { SectionHeader(title = text) }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.brand,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colors.border,
                uncheckedBorderColor = colors.border,
            ),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = valueColor)
    }
}

@Composable
private fun ActionRow(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

@Composable
private fun Divider() = HorizontalDivider(color = AppTheme.colors.border)

// ---------------------------------------------------------------- Profile tab

@Composable
private fun IdentityCard(profile: TrainerProfile) {
    val colors = AppTheme.colors
    Box(modifier = Gutter.fillMaxWidth().frosted(AppShapes.frame, alpha = 0.95f).clip(AppShapes.frame)) {
        CornerArc(Corner.TopEnd, 72.dp)
        Row(
            modifier = Modifier.padding(Spacing.xxl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Avatar(profile.name, size = 64.dp)
            Column {
                Text(profile.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(profile.email, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                if (profile.location.isNotBlank()) {
                    Text(profile.location, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileForm(
    state: TrainerProfileUiState,
    draft: ProfileDraft,
    onAction: (TrainerProfileAction) -> Unit,
) {
    val colors = AppTheme.colors
    val specializations = state.profile?.specializations.orEmpty()
    CardColumn {
        Text(stringResource(R.string.profile_manage), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        FormField(stringResource(R.string.profile_field_name), draft.name, { onAction(TrainerProfileAction.EditField(ProfileField.Name, it)) }, capitalization = KeyboardCapitalization.Words)
        FormField(stringResource(R.string.profile_field_phone), draft.phone, { onAction(TrainerProfileAction.EditField(ProfileField.Phone, it)) }, keyboardType = KeyboardType.Phone)
        FormField(stringResource(R.string.profile_field_location), draft.location, { onAction(TrainerProfileAction.EditField(ProfileField.Location, it)) }, capitalization = KeyboardCapitalization.Words)
        FormField(
            stringResource(R.string.profile_field_rate),
            draft.rateZl,
            { onAction(TrainerProfileAction.EditField(ProfileField.Rate, it)) },
            keyboardType = KeyboardType.Number,
            suffix = stringResource(R.string.profile_rate_suffix),
            error = draft.rate == null,
        )
        FormField(
            stringResource(R.string.profile_field_bio),
            draft.bio,
            { onAction(TrainerProfileAction.EditField(ProfileField.Bio, it)) },
            singleLine = false,
            capitalization = KeyboardCapitalization.Sentences,
        )
        if (specializations.isNotEmpty()) {
            Text(stringResource(R.string.profile_field_specializations), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                specializations.forEach { tag ->
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.brand,
                        modifier = Modifier
                            .clip(AppShapes.pill)
                            .background(colors.softBrand)
                            .border(1.dp, colors.brandBorder, AppShapes.pill)
                            .padding(horizontal = Spacing.md, vertical = 6.dp),
                    )
                }
            }
        }
        if (state.saveFailed) {
            Text(stringResource(R.string.profile_save_failed), style = MaterialTheme.typography.bodySmall, color = colors.dangerText)
        }
        if (state.justSaved) {
            Text(stringResource(R.string.profile_saved), style = MaterialTheme.typography.bodySmall, color = colors.success)
        }
        PrimaryButton(
            text = stringResource(if (state.isSaving) R.string.profile_saving else R.string.profile_save),
            onClick = { onAction(TrainerProfileAction.SaveProfile) },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    singleLine: Boolean = true,
    suffix: String? = null,
    error: Boolean = false,
) {
    val colors = AppTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(colors.brand),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(AppShapes.card)
                        .background(colors.chipSurface)
                        .border(1.dp, if (error) colors.danger else Color.Transparent, AppShapes.card)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) { inner() }
                    if (suffix != null) {
                        Text(suffix, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------- Settings tab

@Composable
private fun NotificationSettings(settings: TrainerSettings, onAction: (TrainerProfileAction) -> Unit) {
    fun set(key: SettingKey): (Boolean) -> Unit = { onAction(TrainerProfileAction.SetSetting(key, it)) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionTitle(stringResource(R.string.settings_notifications))
        CardColumn {
            SwitchRow(stringResource(R.string.settings_notify_messages), null, settings.notifyMessages, set(SettingKey.NotifyMessages))
            Divider()
            SwitchRow(stringResource(R.string.settings_notify_sessions), null, settings.notifySessions, set(SettingKey.NotifySessions))
            Divider()
            SwitchRow(stringResource(R.string.settings_notify_payments), null, settings.notifyPayments, set(SettingKey.NotifyPayments))
            Divider()
            SwitchRow(stringResource(R.string.settings_email_summary), stringResource(R.string.settings_email_summary_hint), settings.emailSummary, set(SettingKey.EmailSummary))
            Divider()
            SwitchRow(stringResource(R.string.settings_auto_confirm), stringResource(R.string.settings_auto_confirm_hint), settings.autoConfirm, set(SettingKey.AutoConfirm))
        }
    }
}

@Composable
private fun PolicyCard() {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionTitle(stringResource(R.string.settings_policy))
        CardColumn {
            InfoRow(stringResource(R.string.settings_policy_free), stringResource(R.string.settings_policy_free_value), colors.success)
            Divider()
            InfoRow(stringResource(R.string.settings_policy_half), stringResource(R.string.settings_policy_half_value), colors.warning)
            Divider()
            InfoRow(stringResource(R.string.settings_policy_full), stringResource(R.string.settings_policy_full_value), colors.dangerText)
        }
    }
}

@Composable
private fun AccountCard(onUnavailable: () -> Unit) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionTitle(stringResource(R.string.settings_account))
        CardColumn {
            InfoRow(stringResource(R.string.settings_language), stringResource(R.string.settings_language_value))
            Divider()
            ActionRow(stringResource(R.string.settings_change_password), MaterialTheme.colorScheme.onSurface, onUnavailable)
            Divider()
            ActionRow(stringResource(R.string.settings_terms), MaterialTheme.colorScheme.onSurface, onUnavailable)
            Divider()
            ActionRow(stringResource(R.string.settings_logout), colors.dangerText, onUnavailable)
        }
    }
}

// ---------------------------------------------------------------- Availability tab

@Composable
private fun AvailabilityCard(days: List<AvailabilityDay>, onAction: (TrainerProfileAction) -> Unit) {
    val colors = AppTheme.colors
    CardColumn {
        Text(stringResource(R.string.availability_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.availability_hint), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        days.forEachIndexed { index, day ->
            if (index > 0) Divider()
            SwitchRow(
                title = day.dayName,
                subtitle = if (day.enabled) day.hours else stringResource(R.string.availability_closed),
                checked = day.enabled,
                onChange = { onAction(TrainerProfileAction.SetDayEnabled(index, it)) },
            )
        }
    }
}

// ---------------------------------------------------------------- Payments tab

@Composable
private fun PayoutsCard(payout: PayoutAccount, onUnavailable: () -> Unit) {
    val colors = AppTheme.colors
    CardColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                modifier = Modifier.size(44.dp).background(colors.softBrand, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CreditCard, contentDescription = null, tint = colors.brand, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(payout.accountLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(if (payout.connected) R.string.payments_connected else R.string.payments_not_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (payout.connected) colors.success else colors.dangerText,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AmountTile(Icons.Outlined.AccountBalanceWallet, formatPln(payout.balance, alwaysDecimals = true), stringResource(R.string.payments_balance), Modifier.weight(1f))
            AmountTile(Icons.Outlined.PriceCheck, formatPln(payout.lastPayout), stringResource(R.string.payments_last_payout), Modifier.weight(1f))
        }
        SecondaryButton(stringResource(R.string.payments_manage), onUnavailable, Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.payments_history), onUnavailable, Modifier.fillMaxWidth())
    }
}

@Composable
private fun AmountTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(modifier = modifier.clip(AppShapes.tile).background(colors.chipSurface).padding(14.dp)) {
        Box(
            modifier = Modifier.size(32.dp).background(colors.softBrand, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.brand, modifier = Modifier.size(18.dp))
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(top = Spacing.md))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = colors.textMuted, modifier = Modifier.padding(top = Spacing.xs))
    }
}
