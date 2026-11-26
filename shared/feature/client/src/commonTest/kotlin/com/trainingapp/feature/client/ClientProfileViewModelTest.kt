package com.trainingapp.feature.client

import com.trainingapp.feature.client.presentation.ClientAccount
import com.trainingapp.feature.client.presentation.ClientProfileViewModel
import com.trainingapp.feature.client.presentation.NameError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
class ClientProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeAccount : ClientAccount {
        val calls = mutableListOf<String>()
        var next: CompletableDeferred<ClientAccount.SaveResult> = CompletableDeferred()

        override suspend fun saveDisplayName(name: String): ClientAccount.SaveResult {
            calls += name
            return next.await()
        }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class JoiningAccount(private val result: ClientAccount.JoinResult) : ClientAccount {
        val codes = mutableListOf<String>()
        override suspend fun saveDisplayName(name: String) = ClientAccount.SaveResult.Saved
        override suspend fun joinTrainer(code: String): ClientAccount.JoinResult { codes += code; return result }
    }

    @Test
    fun `an invite code joins the trainer and a wrong one keeps the sheet with the code`() = runTest(dispatcher) {
        val wrong = ClientProfileViewModel(JoiningAccount(ClientAccount.JoinResult.NotFound))
        wrong.openJoin("abc 123")
        assertEquals("ABC 123", wrong.state.value.join?.code)
        wrong.changeJoinCode("abc-123")
        wrong.submitJoin()
        runCurrent()
        assertEquals("ABC123", wrong.state.value.join?.code)
        assertEquals(com.trainingapp.feature.client.presentation.JoinError.NotFound, wrong.state.value.join?.error)

        val account = JoiningAccount(ClientAccount.JoinResult.Joined("Anna Trener"))
        val vm = ClientProfileViewModel(account)
        vm.openJoin()
        vm.submitJoin()
        assertEquals(com.trainingapp.feature.client.presentation.JoinError.Required, vm.state.value.join?.error)
        vm.changeJoinCode("trener001")
        vm.submitJoin()
        runCurrent()
        assertEquals(listOf("TRENER001"), account.codes)
        assertNull(vm.state.value.join)
        assertEquals("Anna Trener", vm.state.value.joinedTrainer)
    }

    @Test
    fun `an empty or too long name is not sent`() = runTest(dispatcher) {
        val account = FakeAccount()
        val vm = ClientProfileViewModel(account)
        vm.openEditor("Anna Kowalska")

        vm.changeName("   ")
        vm.save()
        assertEquals(NameError.Required, vm.state.value.editor?.error)

        vm.changeName("a".repeat(81))
        vm.save()
        assertEquals(NameError.TooLong, vm.state.value.editor?.error)

        runCurrent()
        assertTrue(account.calls.isEmpty())
    }

    @Test
    fun `saving sends the trimmed name once and closes the sheet only after confirmation`() = runTest(dispatcher) {
        val account = FakeAccount()
        val vm = ClientProfileViewModel(account)
        vm.openEditor("Anna Kowalska")
        vm.changeName("  Anna Nowak ")

        vm.save()
        vm.save()
        runCurrent()
        assertEquals(listOf("Anna Nowak"), account.calls)
        assertTrue(vm.state.value.editor!!.saving)

        // Neither closing nor typing interrupts the running save.
        vm.closeEditor()
        vm.changeName("x")
        assertEquals("  Anna Nowak ", vm.state.value.editor?.draft)

        account.next.complete(ClientAccount.SaveResult.Saved)
        runCurrent()
        assertNull(vm.state.value.editor)
        assertTrue(vm.state.value.saved)
        vm.dismissSaved()
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun `a failed save keeps the sheet open with the typed name`() = runTest(dispatcher) {
        val account = FakeAccount()
        val vm = ClientProfileViewModel(account)
        vm.openEditor("Anna Kowalska")
        vm.changeName("Anna Nowak")

        account.next.complete(ClientAccount.SaveResult.Offline)
        vm.save()
        runCurrent()
        val editor = assertNotNull(vm.state.value.editor)
        assertEquals("Anna Nowak", editor.draft)
        assertEquals(NameError.Offline, editor.error)
        assertFalse(editor.saving)
        assertFalse(vm.state.value.saved)

        // Retrying sends again and can succeed.
        account.next = CompletableDeferred(ClientAccount.SaveResult.Saved)
        vm.save()
        runCurrent()
        assertEquals(2, account.calls.size)
        assertNull(vm.state.value.editor)
    }

    @Test
    fun `an unchanged name closes without a request`() = runTest(dispatcher) {
        val account = FakeAccount()
        val vm = ClientProfileViewModel(account)
        vm.openEditor("Anna Kowalska")
        vm.save()
        runCurrent()
        assertNull(vm.state.value.editor)
        assertTrue(account.calls.isEmpty())
    }
}
