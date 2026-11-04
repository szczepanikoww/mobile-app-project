package com.trainingapp.feature.guest.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.AppTextField
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.PasswordField
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SegmentedTabs
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.component.RoleTile
import com.trainingapp.feature.guest.model.StartingRole
import com.trainingapp.feature.guest.resources.*
import org.jetbrains.compose.resources.stringResource

data class AuthActions(
    val onBack: () -> Unit,
    val onModeChange: (AuthMode) -> Unit,
    val onNameChange: (String, String) -> Unit,
    val onEmailChange: (String, String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onRoleChange: (StartingRole) -> Unit,
    val onTermsChange: (Boolean, String) -> Unit,
    val onSubmit: (String) -> Unit,
    val onGoogle: () -> Unit,
    val onBrowse: () -> Unit,
)

@Composable
private fun FieldError.text(): String = stringResource(
    when (this) {
        FieldError.Required -> Res.string.error_required
        FieldError.InvalidEmail -> Res.string.error_email
        FieldError.PasswordTooShort -> Res.string.error_password_short
        FieldError.TermsRequired -> Res.string.error_terms
    },
)

/**
 * Logowanie / Rejestracja: one form with two tabs, laid out like the New session form (one scrolling column, 24 dp
 * between groups, one primary button).
 *
 * The password lives only in this composable's `remember` state (not `rememberSaveable`, not the view model), so it
 * never reaches the saved-state bundle. It is passed to the view model on each change and on submit.
 */
@Composable
fun AuthScreen(state: AuthUiState, actions: AuthActions, termsVersion: String = "demo-2026-11") {
    var password by remember { mutableStateOf("") }
    var termsSheet by remember { mutableStateOf(false) }
    val register = state.mode == AuthMode.Register
    val submitLabel = stringResource(if (register) Res.string.create_account else Res.string.log_in)

    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = { DetailHeader(stringResource(Res.string.auth_title), onBack = actions.onBack, backEnabled = !state.busy) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = Sizes.contentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.xl)
                    .testTag("auth-form"),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxl),
            ) {
                SegmentedTabs(
                    options = listOf(stringResource(Res.string.log_in), stringResource(Res.string.create_account)),
                    selectedIndex = if (register) 1 else 0,
                    onSelect = { actions.onModeChange(if (it == 1) AuthMode.Register else AuthMode.LogIn) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    if (register) {
                        AppTextField(
                            value = state.name,
                            onValueChange = { actions.onNameChange(it, password) },
                            label = stringResource(Res.string.field_name),
                            error = state.visibleError(AuthField.Name)?.text(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        )
                    }
                    AppTextField(
                        value = state.email,
                        onValueChange = { actions.onEmailChange(it, password) },
                        label = stringResource(Res.string.field_email),
                        error = state.visibleError(AuthField.Email)?.text(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    PasswordField(
                        value = password,
                        onValueChange = { password = it; actions.onPasswordChange(it) },
                        label = stringResource(Res.string.field_password),
                        error = state.visibleError(AuthField.Password)?.text(),
                        supportingText = if (register) stringResource(Res.string.password_hint) else null,
                        imeAction = if (register) ImeAction.Next else ImeAction.Done,
                        keyboardActions = KeyboardActions(onDone = { actions.onSubmit(password) }),
                    )
                }

                if (register) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        Text(stringResource(Res.string.choose_role), style = AppTheme.type.itemTitle, color = AppTheme.colors.text,
                            modifier = Modifier.semantics { heading() })
                        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                            RoleTile(Icons.Outlined.FitnessCenter, stringResource(Res.string.role_client), stringResource(Res.string.role_client_hint),
                                selected = state.role == StartingRole.Client, onSelect = { actions.onRoleChange(StartingRole.Client) },
                                modifier = Modifier.weight(1f))
                            RoleTile(Icons.Outlined.Campaign, stringResource(Res.string.role_trainer), stringResource(Res.string.role_trainer_hint),
                                selected = state.role == StartingRole.Trainer, onSelect = { actions.onRoleChange(StartingRole.Trainer) },
                                modifier = Modifier.weight(1f))
                        }
                        Text(stringResource(Res.string.both_roles_note), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                    }
                    TermsRow(
                        checked = state.termsAccepted,
                        error = state.visibleError(AuthField.Terms)?.text(),
                        onCheckedChange = { actions.onTermsChange(it, password) },
                        onReadTerms = { termsSheet = true },
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    state.failure?.let { failure ->
                        InlineMessage(
                            text = failure.text(),
                            tone = if (failure == AuthFailure.Offline) MessageTone.Offline else MessageTone.Error,
                            action = if (failure == AuthFailure.AccountExists) {
                                { AppTextButton(stringResource(Res.string.log_in), onClick = { actions.onModeChange(AuthMode.LogIn) }) }
                            } else {
                                null
                            },
                        )
                    }
                    PrimaryButton(
                        text = submitLabel,
                        onClick = { actions.onSubmit(password) },
                        modifier = Modifier.fillMaxWidth().testTag("auth-submit"),
                        loading = state.submitting,
                        enabled = !state.googleSubmitting,
                        icon = if (register) Icons.AutoMirrored.Outlined.ArrowForward else Icons.AutoMirrored.Outlined.Login,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        HorizontalDivider(Modifier.weight(1f), color = AppTheme.colors.outline)
                        Text(stringResource(Res.string.divider_or), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                        HorizontalDivider(Modifier.weight(1f), color = AppTheme.colors.outline)
                    }
                    // D10: the button stays; the demo explains that Google sign-in is not connected yet.
                    TonalButton(stringResource(Res.string.continue_google), onClick = actions.onGoogle, modifier = Modifier.fillMaxWidth(),
                        loading = state.googleSubmitting, enabled = !state.submitting)
                    AppTextButton(stringResource(Res.string.browse_without_account), onClick = actions.onBrowse, modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy)
                }
                Spacer(Modifier.height(Spacing.l))
            }
        }
    }

    if (termsSheet) {
        AppBottomSheet(onDismiss = { termsSheet = false }, title = stringResource(Res.string.terms_title)) {
            Text(stringResource(Res.string.terms_body, termsVersion), style = AppTheme.type.body, color = AppTheme.colors.text)
            AppTextButton(stringResource(Res.string.close), onClick = { termsSheet = false }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AuthFailure.text(): String = stringResource(
    when (this) {
        AuthFailure.InvalidCredentials -> Res.string.failure_invalid
        AuthFailure.AccountExists -> Res.string.failure_exists
        AuthFailure.Offline -> Res.string.failure_offline
        AuthFailure.Unavailable -> Res.string.failure_unavailable
        AuthFailure.GoogleUnavailable -> Res.string.failure_google
    },
)

@Composable
private fun TermsRow(checked: Boolean, error: String?, onCheckedChange: (Boolean) -> Unit, onReadTerms: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.minTouchTarget)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                .semantics { if (error != null) this.error(error) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = AppTheme.colors.brandStrong))
            Text(stringResource(Res.string.accept_terms), style = AppTheme.type.body, color = AppTheme.colors.text,
                modifier = Modifier.padding(start = Spacing.s))
        }
        if (error != null) {
            Text(error, style = AppTheme.type.compactBody, color = AppTheme.colors.onErrorContainer, modifier = Modifier.padding(start = Spacing.l))
        }
        AppTextButton(stringResource(Res.string.read_terms), onClick = onReadTerms)
    }
}
