package pl.trainerapp.feature.trainerchats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.trainerapp.feature.trainerchats.data.DemoTrainerChatsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerChatsViewModelTest {

    private class FailingSend(private val delegate: TrainerChatsRepository) : TrainerChatsRepository by delegate {
        override suspend fun send(clientId: String, text: String) = error("offline")
    }

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `list shows conversations newest first with unread total`() = runTest {
        val vm = TrainerChatListViewModel(DemoTrainerChatsRepository(latencyMillis = 0))
        advanceUntilIdle()
        val snapshot = vm.state.value.snapshot!!
        assertEquals(ChatLoadStatus.Content, vm.state.value.status)
        assertEquals("c7", snapshot.conversations.first().clientId)
        assertEquals(3, snapshot.totalUnread)
    }

    @Test
    fun `opening a conversation emits the client id`() = runTest {
        val vm = TrainerChatListViewModel(DemoTrainerChatsRepository(latencyMillis = 0))
        advanceUntilIdle()
        vm.onAction(ChatListAction.Open(vm.state.value.snapshot!!.conversations.first()))
        advanceUntilIdle()
        assertEquals(ChatListEvent.OpenChat("c7"), vm.events.first())
    }

    @Test
    fun `opening a thread marks it read`() = runTest {
        val repo = DemoTrainerChatsRepository(latencyMillis = 0)
        val chat = TrainerChatViewModel("c7", repo)
        advanceUntilIdle()
        assertEquals("Piotr Szymański", chat.state.value.clientName)
        val list = TrainerChatListViewModel(repo)
        advanceUntilIdle()
        assertEquals(1, list.state.value.snapshot!!.totalUnread)
    }

    @Test
    fun `sending appends the message and clears the draft`() = runTest {
        val vm = TrainerChatViewModel("c1", DemoTrainerChatsRepository(latencyMillis = 0))
        advanceUntilIdle()
        val before = vm.state.value.messages.size
        vm.onAction(ChatAction.DraftChanged("  Do zobaczenia!  "))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(before + 1, state.messages.size)
        assertEquals("Do zobaczenia!", state.messages.last().text)
        assertTrue(state.messages.last().fromTrainer)
        assertEquals("", state.draft)
    }

    @Test
    fun `blank drafts are not sent`() = runTest {
        val vm = TrainerChatViewModel("c1", DemoTrainerChatsRepository(latencyMillis = 0))
        advanceUntilIdle()
        val before = vm.state.value.messages.size
        vm.onAction(ChatAction.DraftChanged("   "))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertEquals(before, vm.state.value.messages.size)
    }

    @Test
    fun `failed send keeps the draft and flags the error`() = runTest {
        val vm = TrainerChatViewModel("c1", FailingSend(DemoTrainerChatsRepository(latencyMillis = 0)))
        advanceUntilIdle()
        vm.onAction(ChatAction.DraftChanged("Hej"))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertEquals("Hej", vm.state.value.draft)
        assertTrue(vm.state.value.sendFailed)
        assertEquals(false, vm.state.value.isSending)
    }
}
