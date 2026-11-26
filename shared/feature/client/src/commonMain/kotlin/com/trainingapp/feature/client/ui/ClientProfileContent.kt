package com.trainingapp.feature.client.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.ActionRow
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.DestructiveButton
import com.trainingapp.designsystem.component.GroupedSurface
import com.trainingapp.designsystem.component.ListRow
import com.trainingapp.designsystem.component.PersonPortrait
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.RowDivider
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.SegmentedTabs
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.contract.account.UpdateMeRequest
import com.trainingapp.feature.client.presentation.NameEditorState
import com.trainingapp.feature.client.presentation.NameError
import com.trainingapp.feature.client.resources.*
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.TrainerRef
import pl.trainer.ui.LocalProfilePhotoChange
import pl.trainer.ui.MyPhoto
import pl.trainer.ui.PhotoUploadState

/** What the profile and settings view can do; the account actions come from the app. */
data class ClientProfileActions(
    /** Opens the "Edytuj dane" sheet (the shell renders it). */
    val onEditDetails: () -> Unit,
    /** Shown only when the session holds both roles. */
    val onSwitchToTrainer: (() -> Unit)?,
    val onLogOut: () -> Unit,
    val onFindTrainer: () -> Unit,
    /** "Mam kod od trenera". */
    val onJoinWithCode: () -> Unit = {},
    /** "Moje siłownie": saves the chosen gym ids. */
    val onSaveGyms: (Set<String>) -> Unit = {},
    /** Options whose features do not exist yet (password change, terms). */
    val onUnavailable: () -> Unit,
)

/**
 * The client's "Profil" (opened from the avatar in the top bar), laid out like the trainer profile: a "Profil" tab
 * with the identity, photo and trainer, and an "Ustawienia" tab with the account options and log-out. The shell
 * renders the header with the back arrow.
 */
@Composable
fun ClientProfileContent(
    displayName: String,
    initials: String,
    /** The client's trainers from the schedule; null while it loads. */
    trainers: List<TrainerRef>?,
    actions: ClientProfileActions,
    modifier: Modifier = Modifier,
    gyms: com.trainingapp.feature.client.presentation.ClientGyms? = null,
    gymsFailed: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        SegmentedTabs(
            options = listOf(stringResource(Res.string.profile_tab_profile), stringResource(Res.string.profile_tab_settings)),
            selectedIndex = tab,
            onSelect = { tab = it },
        )
        if (tab == 0) {
            ProfileTab(displayName, initials, isAlsoTrainer = actions.onSwitchToTrainer != null, trainers, actions)
            if (gyms != null) ClientGymsSection(gyms, gymsFailed, actions.onSaveGyms)
        } else {
            SettingsTab(actions)
        }
    }
}

@Composable
private fun ProfileTab(
    displayName: String,
    initials: String,
    isAlsoTrainer: Boolean,
    trainers: List<TrainerRef>?,
    actions: ClientProfileActions,
) {
    GroupedSurface {
        Column(Modifier.padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                PersonPortrait(initials, size = 64.dp, photoOf = MyPhoto)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(displayName, style = AppTheme.type.sectionTitle, color = AppTheme.colors.text)
                    Text(
                        stringResource(if (isAlsoTrainer) Res.string.profile_role_both else Res.string.profile_role_client),
                        style = AppTheme.type.compactBody,
                        color = AppTheme.colors.textSecondary,
                    )
                }
            }
            AppTextButton(
                text = stringResource(Res.string.profile_edit_details),
                onClick = actions.onEditDetails,
                icon = Icons.Outlined.Edit,
            )
            LocalProfilePhotoChange.current?.let { change ->
                AppTextButton(
                    text = stringResource(Res.string.change_photo),
                    onClick = change.pick,
                    enabled = change.state != PhotoUploadState.Uploading,
                    icon = Icons.Outlined.AddAPhoto,
                )
            }
        }
    }
    if (trainers != null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SectionHeader(stringResource(Res.string.profile_trainer))
            if (trainers.isEmpty()) {
                ActionRow(Icons.Outlined.Search, stringResource(Res.string.find_trainer), actions.onFindTrainer, subtitle = stringResource(Res.string.profile_no_trainer))
            } else {
                GroupedSurface {
                    trainers.forEachIndexed { index, trainer ->
                        if (index > 0) RowDivider()
                        ListRow(trainer.name, leading = { PersonPortrait(trainer.initials, size = 40.dp, photoOf = trainer.id) })
                    }
                }
            }
            ActionRow(Icons.Outlined.Key, stringResource(Res.string.join_with_code), actions.onJoinWithCode, subtitle = stringResource(Res.string.join_with_code_hint))
        }
    }
}

@Composable
private fun SettingsTab(actions: ClientProfileActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.settings_account))
        GroupedSurface {
            ListRow(
                stringResource(Res.string.settings_language),
                trailing = { Text(stringResource(Res.string.settings_language_value), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary) },
            )
            RowDivider()
            ListRow(stringResource(Res.string.settings_change_password), onClick = actions.onUnavailable)
            RowDivider()
            ListRow(stringResource(Res.string.settings_terms), onClick = actions.onUnavailable)
            actions.onSwitchToTrainer?.let { switch ->
                RowDivider()
                ListRow(
                    stringResource(Res.string.switch_to_trainer),
                    onClick = switch,
                    leading = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = AppTheme.colors.brandStrong) },
                )
            }
        }
    }
    DestructiveButton(
        text = stringResource(Res.string.log_out),
        onClick = actions.onLogOut,
        icon = Icons.AutoMirrored.Outlined.Logout,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Moje siłownie": the gyms the client attends; their trainers see them in the client card. */
@Composable
private fun ClientGymsSection(gyms: com.trainingapp.feature.client.presentation.ClientGyms, failed: Boolean, onSave: (Set<String>) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(stringResource(Res.string.client_gyms_title))
        val chosen = gyms.all.filter { it.id in gyms.selected }
        GroupedSurface {
            Column(Modifier.padding(Spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (chosen.isEmpty()) {
                    Text(stringResource(Res.string.client_gyms_none), style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
                } else {
                    chosen.forEach { Text(it.name, style = AppTheme.type.itemTitle, color = AppTheme.colors.text) }
                }
                if (failed) Text(stringResource(Res.string.client_gyms_failed), style = AppTheme.type.compactBody, color = AppTheme.colors.onErrorContainer)
                AppTextButton(text = stringResource(Res.string.client_gyms_choose), onClick = { choosing = true })
            }
        }
    }
    if (choosing) {
        var picked by remember { mutableStateOf(gyms.selected) }
        AppBottomSheet(onDismiss = { choosing = false }, title = stringResource(Res.string.client_gyms_title)) {
            gyms.all.forEach { gym ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = gym.id in picked, role = Role.Checkbox) {
                        picked = if (it) picked + gym.id else picked - gym.id
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = gym.id in picked, onCheckedChange = null)
                    Column(Modifier.padding(start = Spacing.s)) {
                        Text(gym.name, style = AppTheme.type.body, color = AppTheme.colors.text)
                        if (gym.address.isNotBlank()) Text(gym.address, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                    }
                }
            }
            PrimaryButton(text = stringResource(Res.string.client_gyms_save), onClick = { choosing = false; onSave(picked) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** "Mam kod od trenera": the code from the trainer's invite; joining waits for the server. */
@Composable
internal fun JoinTrainerSheet(
    state: com.trainingapp.feature.client.presentation.JoinState,
    onChange: (String) -> Unit,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss, title = stringResource(Res.string.join_with_code)) {
        val error = when (state.error) {
            com.trainingapp.feature.client.presentation.JoinError.Required -> stringResource(Res.string.join_code_required)
            com.trainingapp.feature.client.presentation.JoinError.NotFound -> stringResource(Res.string.join_code_not_found)
            com.trainingapp.feature.client.presentation.JoinError.Offline -> stringResource(Res.string.join_code_offline)
            com.trainingapp.feature.client.presentation.JoinError.Failed -> stringResource(Res.string.join_code_failed)
            com.trainingapp.feature.client.presentation.JoinError.Unavailable -> stringResource(Res.string.join_code_unavailable)
            null -> null
        }
        AppTextField(
            value = state.code,
            onValueChange = onChange,
            label = stringResource(Res.string.join_code_label),
            error = error,
            supportingText = stringResource(Res.string.join_with_code_hint),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onJoin() }),
        )
        PrimaryButton(
            text = stringResource(Res.string.join_action),
            onClick = onJoin,
            enabled = state.code.isNotBlank(),
            loading = state.joining,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * "Edytuj dane": the client's own name, as their trainers see it. Saving waits for the server; the sheet stays open
 * with the typed name when the save fails.
 */
@Composable
internal fun EditNameSheet(
    state: NameEditorState,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss, title = stringResource(Res.string.profile_edit_details)) {
        val error = when (state.error) {
            NameError.Required -> stringResource(Res.string.profile_name_required)
            NameError.TooLong -> stringResource(Res.string.profile_name_too_long, UpdateMeRequest.DISPLAY_NAME_MAX_LENGTH)
            NameError.Rejected -> stringResource(Res.string.profile_name_rejected)
            NameError.Offline -> stringResource(Res.string.profile_name_offline)
            NameError.Failed -> stringResource(Res.string.profile_name_failed)
            null -> null
        }
        AppTextField(
            value = state.draft,
            onValueChange = onChange,
            label = stringResource(Res.string.profile_name_label),
            error = error,
            supportingText = stringResource(Res.string.profile_name_hint),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
        )
        PrimaryButton(
            text = stringResource(Res.string.profile_save),
            onClick = onSave,
            enabled = state.changed,
            loading = state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
