package com.trainingapp.feature.guest

import com.trainingapp.feature.guest.auth.AuthField
import com.trainingapp.feature.guest.auth.AuthInput
import com.trainingapp.feature.guest.auth.AuthMode
import com.trainingapp.feature.guest.auth.AuthValidator
import com.trainingapp.feature.guest.auth.FieldError
import com.trainingapp.feature.guest.model.Registration
import com.trainingapp.feature.guest.model.StartingRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthValidatorTest {
    private fun register(name: String = "Ala Test", email: String = "ala@example.com", password: String = "12345678", terms: Boolean = true) =
        AuthValidator.validate(AuthInput(AuthMode.Register, name, email, password, terms))

    private fun logIn(email: String = "ala@example.com", password: String = "x") =
        AuthValidator.validate(AuthInput(AuthMode.LogIn, "", email, password, termsAccepted = false))

    @Test
    fun validRegistrationHasNoErrors() = assertTrue(register().isEmpty())

    @Test
    fun emailFormat() {
        assertEquals(FieldError.Required, register(email = "  ")[AuthField.Email])
        assertEquals(FieldError.InvalidEmail, register(email = "ala@")[AuthField.Email])
        assertEquals(FieldError.InvalidEmail, register(email = "ala example.com")[AuthField.Email])
        assertFalse(AuthField.Email in register(email = "  ala@example.com "))
    }

    @Test
    fun passwordLengthBoundaryOnlyForRegistration() {
        assertEquals(FieldError.PasswordTooShort, register(password = "1234567")[AuthField.Password])
        assertFalse(AuthField.Password in register(password = "12345678"))
        assertFalse(AuthField.Password in logIn(password = "short"))
        assertEquals(FieldError.Required, logIn(password = "")[AuthField.Password])
        assertEquals(FieldError.Required, register(password = "        ")[AuthField.Password])
    }

    @Test
    fun nameAndConsentAreRequiredOnlyForRegistration() {
        assertEquals(FieldError.Required, register(name = "   ")[AuthField.Name])
        assertEquals(FieldError.TermsRequired, register(terms = false)[AuthField.Terms])
        assertTrue(logIn().isEmpty())
    }

    @Test
    fun secretsAreRedactedInToString() {
        val registration = Registration("Ala", "ala@example.com", "secret-password", StartingRole.Client, "v1")
        assertFalse("secret-password" in registration.toString())
        assertFalse("ala@example.com" in registration.toString())
        assertFalse("secret-password" in AuthInput(AuthMode.LogIn, "", "a@b.pl", "secret-password", false).toString())
    }
}
