package com.trainingapp.feature.guest

import com.trainingapp.feature.guest.auth.AuthFailure
import com.trainingapp.feature.guest.auth.AuthField
import com.trainingapp.feature.guest.auth.AuthMode
import com.trainingapp.feature.guest.auth.AuthViewModel
import com.trainingapp.feature.guest.data.DemoAccounts
import com.trainingapp.feature.guest.data.DemoAuthRepository
import com.trainingapp.feature.guest.model.GuestIntent
import com.trainingapp.feature.guest.model.Role
import com.trainingapp.feature.guest.model.StartingRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val network = FakeNetwork()
    private val repository = DemoAuthRepository(network, latencyMs = 100)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.filledRegister(): AuthViewModel = AuthViewModel(AuthMode.Register, repository).apply {
        onNameChange("Ala Test", PASSWORD)
        onEmailChange("ala@example.com", PASSWORD)
        onTermsChange(true, PASSWORD)
    }

    @Test
    fun doubleTapSendsOneRequest() = runTest(dispatcher) {
        val model = filledRegister()
        model.submit(PASSWORD)
        model.submit(PASSWORD)
        assertTrue(model.state.value.submitting)
        advanceUntilIdle()
        assertEquals(1, repository.requestCount)
        assertNotNull(model.state.value.signedIn)
    }

    @Test
    fun validationShowsOnSubmitThenLive() = runTest(dispatcher) {
        val model = AuthViewModel(AuthMode.Register, repository)
        model.onEmailChange("ala", "")
        assertNull(model.state.value.visibleError(AuthField.Email), "nothing is shown before the first submit")
        model.submit("")
        assertEquals(0, repository.requestCount)
        assertNotNull(model.state.value.visibleError(AuthField.Email))
        model.onEmailChange("ala@example.com", "")
        assertNull(model.state.value.visibleError(AuthField.Email))
    }

    @Test
    fun offlineNeverSucceedsAndKeepsInput() = runTest(dispatcher) {
        network.isOnline.value = false
        val model = filledRegister()
        model.submit(PASSWORD)
        advanceUntilIdle()
        val state = model.state.value
        assertEquals(AuthFailure.Offline, state.failure)
        assertNull(state.signedIn)
        assertNull(repository.session.value)
        assertEquals("Ala Test", state.name)
        assertEquals("ala@example.com", state.email)
        assertTrue(state.termsAccepted)
    }

    @Test
    fun eachFailureKeepsInput() = runTest(dispatcher) {
        val login = AuthViewModel(AuthMode.LogIn, repository)
        login.onEmailChange("nobody@example.com", PASSWORD)
        login.submit(PASSWORD)
        advanceUntilIdle()
        assertEquals(AuthFailure.InvalidCredentials, login.state.value.failure)
        assertEquals("nobody@example.com", login.state.value.email)

        repository.unavailable = true
        login.submit(PASSWORD)
        advanceUntilIdle()
        assertEquals(AuthFailure.Unavailable, login.state.value.failure)
        repository.unavailable = false

        val register = AuthViewModel(AuthMode.Register, repository)
        register.onNameChange("Trener", PASSWORD)
        register.onEmailChange(DemoAccounts.TRAINER_EMAIL, PASSWORD)
        register.onTermsChange(true, PASSWORD)
        register.submit(PASSWORD)
        advanceUntilIdle()
        assertEquals(AuthFailure.AccountExists, register.state.value.failure)
        assertEquals(DemoAccounts.TRAINER_EMAIL, register.state.value.email)
    }

    @Test
    fun switchingTabsKeepsValues() = runTest(dispatcher) {
        val model = filledRegister()
        repeat(3) {
            model.onModeChange(AuthMode.LogIn)
            model.onModeChange(AuthMode.Register)
        }
        with(model.state.value) {
            assertEquals("Ala Test", name)
            assertEquals("ala@example.com", email)
            assertTrue(termsAccepted)
        }
    }

    @Test
    fun signInResolvesTheRole() = runTest(dispatcher) {
        val trainer = AuthViewModel(AuthMode.LogIn, repository)
        trainer.onEmailChange(DemoAccounts.TRAINER_EMAIL, PASSWORD)
        trainer.submit(PASSWORD)
        advanceUntilIdle()
        assertEquals(Role.Trainer, trainer.state.value.signedIn?.activeRole)

        val newTrainer = filledRegister()
        newTrainer.onRoleChange(StartingRole.Trainer)
        newTrainer.onEmailChange("nowy.trener@example.com", PASSWORD)
        newTrainer.submit(PASSWORD)
        advanceUntilIdle()
        assertEquals(Role.Trainer, newTrainer.state.value.signedIn?.activeRole)

        val client = filledRegister()
        client.submit(PASSWORD)
        advanceUntilIdle()
        assertEquals(Role.Client, client.state.value.signedIn?.activeRole)
    }

    @Test
    fun googleIsNotConnectedYet() = runTest(dispatcher) {
        val model = AuthViewModel(AuthMode.LogIn, repository)
        model.continueWithGoogle()
        advanceUntilIdle()
        assertEquals(AuthFailure.GoogleUnavailable, model.state.value.failure)
        assertNull(model.state.value.signedIn)
    }

    @Test
    fun pendingIntentSurvivesSerialisationAndCreatesNothing() {
        val intent: GuestIntent = GuestIntent.RequestSession(trainerId = "tr-michal")
        val json = Json.encodeToString(GuestIntent.serializer(), intent)
        assertEquals(intent, Json.decodeFromString(GuestIntent.serializer(), json))
        // Carrying the intent through sign-in makes no catalogue or auth request by itself.
        assertEquals(0, repository.requestCount)
    }

    private companion object {
        const val PASSWORD = "haslo-testowe"
    }
}
