package com.trainingapp.feature.guest.auth

enum class AuthMode { LogIn, Register }

enum class AuthField { Name, Email, Password, Terms }

enum class FieldError { Required, InvalidEmail, PasswordTooShort, TermsRequired }

data class AuthInput(
    val mode: AuthMode,
    val name: String,
    val email: String,
    val password: String,
    val termsAccepted: Boolean,
) {
    override fun toString() = "AuthInput(mode=$mode, name=***, email=***, password=***, termsAccepted=$termsAccepted)"
}

/** Client-side checks before a request is sent. The server repeats them; this only gives fast inline feedback. */
object AuthValidator {
    const val MIN_PASSWORD_LENGTH = 8

    private val EmailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    fun validate(input: AuthInput): Map<AuthField, FieldError> = buildMap {
        if (input.mode == AuthMode.Register && input.name.isBlank()) put(AuthField.Name, FieldError.Required)
        val email = input.email.trim()
        when {
            email.isEmpty() -> put(AuthField.Email, FieldError.Required)
            !EmailPattern.matches(email) -> put(AuthField.Email, FieldError.InvalidEmail)
        }
        when {
            input.password.isBlank() -> put(AuthField.Password, FieldError.Required)
            // Only registration enforces the length; a login with a short password simply fails on the server.
            input.mode == AuthMode.Register && input.password.length < MIN_PASSWORD_LENGTH ->
                put(AuthField.Password, FieldError.PasswordTooShort)
        }
        if (input.mode == AuthMode.Register && !input.termsAccepted) put(AuthField.Terms, FieldError.TermsRequired)
    }
}
