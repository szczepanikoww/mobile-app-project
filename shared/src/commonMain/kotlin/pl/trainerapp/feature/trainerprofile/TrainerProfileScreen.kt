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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainer.resources.*
import pl.trainerapp.core.designsystem.AppShapes
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.core.designsystem.Avatar
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
import pl.trainerapp.core.designsystem.IconCircle
import pl.trainerapp.core.designsystem.OutlinedTile
import pl.trainerapp.core.designsystem.ScreenLoading
import pl.trainer.ui.AppColors as SharedColors
import pl.trainer.ui.Layout
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import pl.trainerapp.core.format.formatPln
import pl.trainerapp.core.format.formatUpdatedAt
import pl.trainerapp.feature.trainerprofile.model.AvailabilityDay
import pl.trainerapp.feature.trainerprofile.model.PayoutAccount
import pl.trainerapp.feature.trainerprofile.model.ProfileDraft
import pl.trainerapp.feature.trainerprofile.model.SettingKey
import pl.trainerapp.feature.trainerprofile.model.TrainerProfile
import pl.trainerapp.feature.trainerprofile.model.TrainerSettings

private val Gutter = Modifier.padding(horizontal = Spacing.screenGutter)

/**
 * Navigation- and DI-aware entry point. [onUnavailable] covers actions whose features do not exist yet;
 * [onLogOut] signs out and returns to the guest views.
 */
@Composable
fun TrainerProfileRoute(
    repository: TrainerProfileRepository,
    onBack: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    onLogOut: () -> Unit = onUnavailable,
    /** The current cancellation policy version with the editor entry (B5); the static summary when null. */
    cancellationPolicy: (@Composable () -> Unit)? = null,
) {
    val viewModel = viewModel { TrainerProfileViewModel(repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrainerProfileScreen(state, viewModel::onAction, onBack, onUnavailable, modifier, onLogOut, cancellationPolicy)
}

@Composable
private fun tabLabel(tab: ProfileTab): String = stringResource(
    when (tab) {
        ProfileTab.Profile -> Res.string.profile_tab_profile
        ProfileTab.Settings -> Res.string.profile_tab_settings
        ProfileTab.Availability -> Res.string.profile_tab_availability
        ProfileTab.Payments -> Res.string.profile_tab_payments
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
    onLogOut: () -> Unit = onUnavailable,
    cancellationPolicy: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(title = stringResource(Res.string.profile_title), onBack = onBack)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onAction(TrainerProfileAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Layout.padding),
                verticalArrangement = Arrangement.spacedBy(Layout.section),
            ) {
                when (state.status) {
                    ProfileLoadStatus.Loading -> item { ScreenLoading(stringResource(Res.string.screen_loading)) }
                    ProfileLoadStatus.Error -> item {
                        ErrorState(
                            title = stringResource(Res.string.error_load_profile),
                            retryLabel = stringResource(Res.string.action_retry),
                            onRetry = { onAction(TrainerProfileAction.Refresh) },
                            modifier = Gutter,
                        )
                    }
                    ProfileLoadStatus.Content -> content(state, onAction, onUnavailable, onLogOut, cancellationPolicy)
                }
            }
        }
    }
}

private fun LazyListScope.content(
    state: TrainerProfileUiState,
    onAction: (TrainerProfileAction) -> Unit,
    onUnavailable: () -> Unit,
    onLogOut: () -> Unit,
    cancellationPolicy: (@Composable () -> Unit)?,
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
        item { InlineBanner(stringResource(Res.string.offline_banner, formatUpdatedAt(updated)), modifier = Gutter) }
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
            item { if (cancellationPolicy != null) cancellationPolicy() else PolicyCard() }
            item { AccountCard(onUnavailable, onLogOut) }
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
        modifier = Gutter.then(modifier).fillMaxWidth().frosted(Layout.tile).padding(Layout.padding),
        verticalArrangement = Arrangement.spacedBy(Layout.small),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Box(modifier = Gutter) { SectionHeader(title = text) }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Layout.medium),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = valueColor)
    }
}

@Composable
private fun ActionRow(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.target)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Layout.medium),
    )
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

// ---------------------------------------------------------------- Profile tab

@Composable
private fun IdentityCard(profile: TrainerProfile) {
    CardColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.padding)) {
            Avatar(profile.name, size = 64.dp)
            Column(verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                Text(profile.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (profile.location.isNotBlank()) {
                    Text(profile.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val specializations = state.profile?.specializations.orEmpty()
    CardColumn {
        Text(stringResource(Res.string.profile_manage), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        FormField(stringResource(Res.string.profile_field_name), draft.name, { onAction(TrainerProfileAction.EditField(ProfileField.Name, it)) }, capitalization = KeyboardCapitalization.Words)
        FormField(stringResource(Res.string.profile_field_phone), draft.phone, { onAction(TrainerProfileAction.EditField(ProfileField.Phone, it)) }, keyboardType = KeyboardType.Phone)
        FormField(stringResource(Res.string.profile_field_location), draft.location, { onAction(TrainerProfileAction.EditField(ProfileField.Location, it)) }, capitalization = KeyboardCapitalization.Words)
        FormField(
            stringResource(Res.string.profile_field_rate),
            draft.rateZl,
            { onAction(TrainerProfileAction.EditField(ProfileField.Rate, it)) },
            keyboardType = KeyboardType.Number,
            suffix = stringResource(Res.string.profile_rate_suffix),
            error = draft.rate == null,
        )
        FormField(
            stringResource(Res.string.profile_field_bio),
            draft.bio,
            { onAction(TrainerProfileAction.EditField(ProfileField.Bio, it)) },
            singleLine = false,
            capitalization = KeyboardCapitalization.Sentences,
        )
        if (specializations.isNotEmpty()) {
            Text(stringResource(Res.string.profile_field_specializations), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Layout.small), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                specializations.forEach { tag ->
                    Surface(color = SharedColors.softAccent, contentColor = SharedColors.onSoftAccent, shape = Layout.pill) {
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = Layout.medium, vertical = Layout.tiny),
                        )
                    }
                }
            }
        }
        if (state.saveFailed) {
            Text(stringResource(Res.string.profile_save_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (state.justSaved) {
            Text(stringResource(Res.string.profile_saved), style = MaterialTheme.typography.bodyMedium, color = SharedColors.success)
        }
        PrimaryButton(
            text = stringResource(if (state.isSaving) Res.string.profile_saving else Res.string.profile_save),
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
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
        isError = error,
        suffix = suffix?.let { { Text(it) } },
        shape = Layout.card,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- Settings tab

@Composable
private fun NotificationSettings(settings: TrainerSettings, onAction: (TrainerProfileAction) -> Unit) {
    fun set(key: SettingKey): (Boolean) -> Unit = { onAction(TrainerProfileAction.SetSetting(key, it)) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionTitle(stringResource(Res.string.settings_notifications))
        CardColumn {
            SwitchRow(stringResource(Res.string.settings_notify_messages), null, settings.notifyMessages, set(SettingKey.NotifyMessages))
            Divider()
            SwitchRow(stringResource(Res.string.settings_notify_sessions), null, settings.notifySessions, set(SettingKey.NotifySessions))
            Divider()
            SwitchRow(stringResource(Res.string.settings_notify_payments), null, settings.notifyPayments, set(SettingKey.NotifyPayments))
            Divider()
            SwitchRow(stringResource(Res.string.settings_email_summary), stringResource(Res.string.settings_email_summary_hint), settings.emailSummary, set(SettingKey.EmailSummary))
            Divider()
            SwitchRow(stringResource(Res.string.settings_auto_confirm), stringResource(Res.string.settings_auto_confirm_hint), settings.autoConfirm, set(SettingKey.AutoConfirm))
        }
    }
}

@Composable
private fun PolicyCard() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionTitle(stringResource(Res.string.settings_policy))
        CardColumn {
            InfoRow(stringResource(Res.string.settings_policy_free), stringResource(Res.string.settings_policy_free_value), SharedColors.success)
            Divider()
            InfoRow(stringResource(Res.string.settings_policy_half), stringResource(Res.string.settings_policy_half_value), SharedColors.warningText)
            Divider()
            InfoRow(stringResource(Res.string.settings_policy_full), stringResource(Res.string.settings_policy_full_value), SharedColors.errorText)
        }
    }
}

@Composable
private fun AccountCard(onUnavailable: () -> Unit, onLogOut: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionTitle(stringResource(Res.string.settings_account))
        CardColumn {
            InfoRow(stringResource(Res.string.settings_language), stringResource(Res.string.settings_language_value))
            Divider()
            ActionRow(stringResource(Res.string.settings_change_password), MaterialTheme.colorScheme.onSurface, onUnavailable)
            Divider()
            ActionRow(stringResource(Res.string.settings_terms), MaterialTheme.colorScheme.onSurface, onUnavailable)
            Divider()
            ActionRow(stringResource(Res.string.settings_logout), SharedColors.errorText, onLogOut)
        }
    }
}

// ---------------------------------------------------------------- Availability tab

@Composable
private fun AvailabilityCard(days: List<AvailabilityDay>, onAction: (TrainerProfileAction) -> Unit) {
    CardColumn {
        Text(stringResource(Res.string.availability_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(Res.string.availability_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        days.forEachIndexed { index, day ->
            if (index > 0) Divider()
            SwitchRow(
                title = day.dayName,
                subtitle = if (day.enabled) day.hours else stringResource(Res.string.availability_closed),
                checked = day.enabled,
                onChange = { onAction(TrainerProfileAction.SetDayEnabled(index, it)) },
            )
        }
    }
}

// ---------------------------------------------------------------- Payments tab

@Composable
private fun PayoutsCard(payout: PayoutAccount, onUnavailable: () -> Unit) {
    CardColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            IconCircle(Icons.Outlined.CreditCard, SharedColors.softAccent, MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(payout.accountLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (payout.connected) Res.string.payments_connected else Res.string.payments_not_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (payout.connected) SharedColors.success else SharedColors.errorText,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
            AmountTile(Icons.Outlined.AccountBalanceWallet, formatPln(payout.balance, alwaysDecimals = true), stringResource(Res.string.payments_balance), Modifier.weight(1f))
            AmountTile(Icons.Outlined.PriceCheck, formatPln(payout.lastPayout), stringResource(Res.string.payments_last_payout), Modifier.weight(1f))
        }
        SecondaryButton(stringResource(Res.string.payments_manage), onUnavailable, Modifier.fillMaxWidth())
        SecondaryButton(stringResource(Res.string.payments_history), onUnavailable, Modifier.fillMaxWidth())
    }
}

@Composable
private fun AmountTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    OutlinedTile(modifier) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
