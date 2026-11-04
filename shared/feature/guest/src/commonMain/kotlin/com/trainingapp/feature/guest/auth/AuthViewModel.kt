package com.trainingapp.feature.guest.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthResult
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.Registration
import com.trainingapp.feature.guest.model.StartingRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthFailure { InvalidCredentials, AccountExists, Offline, Unavailable, GoogleUnavailable }

/**
 * The form state. The password is deliberately not here: it stays in screen-local state and is passed to
 * [AuthViewModel.submit], so it never reaches saved state or logs.
 */
data class AuthUiState(
    val mode: AuthMode,
    val name: String = "",
    val email: String = "",
    val role: StartingRole = StartingRole.Client,
    val termsAccepted: Boolean = false,
    val errors: Map<AuthField, FieldError> = emptyMap(),
    /** Fields that show validation: set on the first submit, then validated live. */
    val validatedFields: Set<AuthField> = emptySet(),
    val submitting: Boolean = false,
    val googleSubmitting: Boolean = false,
    val failure: AuthFailure? = null,
    /** Set once sign-in succeeded; the route navigates to the role shell. */
    val signedIn: AuthSession? = null,
) {
    val busy: Boolean get() = submitting || googleSubmitting

    fun visibleError(field: AuthField): FieldError? = errors[field]?.takeIf { field in validatedFields }
}

class AuthViewModel(
    initialMode: AuthMode,
    private val repository: AuthRepository,
    /** The version of the terms the user accepts. No documents exist yet (D13); the version is still recorded. */
    private val termsVersion: String = "demo-2026-11",
) : ViewModel() {

    private val mutableState = MutableStateFlow(AuthUiState(mode = initialMode))
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

    /** Switching tabs keeps e-mail and password; name, role and consent only apply to registration. */
    fun onModeChange(mode: AuthMode) = mutableState.update {
        it.copy(mode = mode, failure = null, validatedFields = emptySet(), errors = emptyMap())
    }

    fun onNameChange(value: String, password: String) = edit(password) { it.copy(name = value) }

    fun onEmailChange(value: String, password: String) = edit(password) { it.copy(email = value) }

    fun onPasswordChange(password: String) = edit(password) { it }

    fun onRoleChange(role: StartingRole) = mutableState.update { it.copy(role = role) }

    fun onTermsChange(accepted: Boolean, password: String) = edit(password) { it.copy(termsAccepted = accepted) }

    private fun edit(password: String, change: (AuthUiState) -> AuthUiState) = mutableState.update { current ->
        val next = change(current)
        next.copy(errors = AuthValidator.validate(next.input(password)))
    }

    fun submit(password: String) {
        val current = state.value
        if (current.busy || current.signedIn != null) return
        val errors = AuthValidator.validate(current.input(password))
        if (errors.isNotEmpty()) {
            mutableState.update { it.copy(errors = errors, validatedFields = AuthField.entries.toSet(), failure = null) }
            return
        }
        mutableState.update { it.copy(errors = emptyMap(), submitting = true, failure = null) }
        viewModelScope.launch {
            val result = when (current.mode) {
                AuthMode.LogIn -> repository.logIn(current.email.trim(), password)
                AuthMode.Register -> repository.register(
                    Registration(current.name.trim(), current.email.trim(), password, current.role, termsVersion),
                )
            }
            handle(result)
        }
    }

    fun continueWithGoogle() {
        if (state.value.busy || state.value.signedIn != null) return
        mutableState.update { it.copy(googleSubmitting = true, failure = null) }
        viewModelScope.launch { handle(repository.continueWithGoogle()) }
    }

    fun dismissFailure() = mutableState.update { it.copy(failure = null) }

    /** All input is kept on every failure. */
    private fun handle(result: AuthResult) = mutableState.update {
        val done = it.copy(submitting = false, googleSubmitting = false)
        when (result) {
            is AuthResult.Success -> done.copy(signedIn = result.session)
            AuthResult.InvalidCredentials -> done.copy(failure = AuthFailure.InvalidCredentials)
            AuthResult.AccountExists -> done.copy(failure = AuthFailure.AccountExists)
            AuthResult.Offline -> done.copy(failure = AuthFailure.Offline)
            AuthResult.Unavailable -> done.copy(failure = AuthFailure.Unavailable)
            AuthResult.NotSupported -> done.copy(failure = AuthFailure.GoogleUnavailable)
        }
    }

    private fun AuthUiState.input(password: String) = AuthInput(mode, name, email, password, termsAccepted)
}
