package com.trainingapp.feature.guest

import com.trainingapp.feature.guest.auth.AuthFailure
import com.trainingapp.feature.guest.auth.AuthField
import com.trainingapp.feature.guest.auth.AuthMode
import com.trainingapp.feature.guest.auth.AuthViewModel
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthResult
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.Registration
import com.trainingapp.feature.guest.model.Role
import com.trainingapp.feature.guest.model.StartingRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account completion state (docs/plans/firebase-auth.md F4) and the new provider results. */
class AuthCompletionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class ScriptedAuth(var next: AuthResult) : AuthRepository {
        override val session = MutableStateFlow<AuthSession?>(null)
        val completed = mutableListOf<Triple<String, StartingRole, String>>()
        var cancelled = 0
        override suspend fun logIn(email: String, password: String) = next
        override suspend fun register(request: Registration) = next
        override suspend fun continueWithGoogle() = next
        override suspend fun logOut() {}
        override suspend fun completeAccount(name: String, role: StartingRole, acceptedTermsVersion: String): AuthResult {
            completed += Triple(name, role, acceptedTermsVersion)
            return AuthResult.Success(AuthSession("uid-1", name, setOf(Role.Client), Role.Client))
        }
        override suspend fun cancelAccountCompletion() { cancelled++ }
    }

    @Test
    fun googleWithoutAccountOpensCompletionWithPrefilledFields() = runTest(dispatcher) {
        val auth = ScriptedAuth(AuthResult.NeedsAccount("ola@example.com", "Ola Google"))
        val model = AuthViewModel(AuthMode.LogIn, auth, termsVersion = "terms-1")
        model.continueWithGoogle()
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state.completing)
        assertEquals(AuthMode.Register, state.mode)
        assertEquals("ola@example.com", state.email)
        assertEquals("Ola Google", state.name)
        assertNull(state.failure)

        // Consent is still required; no password is asked.
        model.submit("")
        assertEquals(setOf(AuthField.Terms), model.state.value.errors.keys)
        model.onTermsChange(true, "")
        model.onRoleChange(StartingRole.Trainer)
        model.submit("")
        advanceUntilIdle()
        assertEquals(listOf(Triple("Ola Google", StartingRole.Trainer, "terms-1")), auth.completed)
        assertNotNull(model.state.value.signedIn)
    }

    @Test
    fun leavingCompletionForgetsTheProviderSignIn() = runTest(dispatcher) {
        val auth = ScriptedAuth(AuthResult.NeedsAccount("pol@example.com", null))
        val model = AuthViewModel(AuthMode.LogIn, auth)
        model.onEmailChange("pol@example.com", "haslo1234")
        model.submit("haslo1234")
        advanceUntilIdle()
        assertTrue(model.state.value.completing)

        model.onModeChange(AuthMode.LogIn)
        advanceUntilIdle()
        assertFalse(model.state.value.completing)
        assertEquals(AuthMode.LogIn, model.state.value.mode)
        assertEquals(1, auth.cancelled)
    }

    @Test
    fun cancelledGoogleShowsNothingAndTooManyAttemptsHasItsMessage() = runTest(dispatcher) {
        val auth = ScriptedAuth(AuthResult.Cancelled)
        val model = AuthViewModel(AuthMode.LogIn, auth)
        model.continueWithGoogle()
        advanceUntilIdle()
        assertNull(model.state.value.failure)
        assertFalse(model.state.value.busy)

        auth.next = AuthResult.TooManyAttempts
        model.continueWithGoogle()
        advanceUntilIdle()
        assertEquals(AuthFailure.TooManyAttempts, model.state.value.failure)
    }
}
