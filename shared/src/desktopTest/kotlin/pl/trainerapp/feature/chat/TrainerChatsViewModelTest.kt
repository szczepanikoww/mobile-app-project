package pl.trainerapp.feature.chat

import com.trainingapp.domain.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pl.trainerapp.feature.chat.data.DemoChatMedia
import pl.trainerapp.feature.chat.data.DemoChatStore
import pl.trainerapp.feature.chat.data.DemoClientChatRepository
import pl.trainerapp.feature.chat.data.DemoClientConversations
import pl.trainerapp.feature.chat.data.TrainerChatsFixtures
import pl.trainerapp.feature.chat.data.DemoTrainerChatsRepository
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.MediaRef
import com.trainingapp.domain.media.MediaState

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerChatsViewModelTest {

    private class FailingSend(private val delegate: TrainerChatsRepository) : TrainerChatsRepository by delegate {
        override suspend fun send(clientId: String, text: String) = error("offline")
    }

    private class FakeMedia(initial: Map<String, MediaAsset> = emptyMap()) : ChatMediaSource {
        val assets = MutableStateFlow(initial)
        override fun observe(mediaId: String): Flow<MediaAsset?> = assets.map { it[mediaId] }
        override suspend fun retry(mediaId: String) = Unit
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun repo(store: DemoChatStore = DemoChatStore()) = DemoTrainerChatsRepository(store, latencyMillis = 0)

    @Test
    fun `the sender sees a message as seen once the other side opens the thread`() = runTest {
        val store = DemoChatStore()
        val trainer = repo(store)
        val client = DemoClientChatRepository(store, "c2", latencyMillis = 0)

        val sent = trainer.send("c2", "Do zobaczenia jutro")
        // Not read yet: the receipt still sits under the older message the client had read.
        assertEquals("c2-1", trainer.thread("c2").seenMessageId)

        client.thread() // the client opens the conversation
        val after = trainer.thread("c2")
        assertEquals(sent.id, after.seenMessageId)
        assertTrue(after.seenAt != null)

        // A newer message of mine is not seen until it is read too.
        val newer = trainer.send("c2", "I jeszcze jedno")
        val again = trainer.thread("c2")
        assertEquals(sent.id, again.seenMessageId)
        assertTrue(newer.id != again.seenMessageId)
    }

    @Test
    fun `active people are the ones seen within the last minutes`() = runTest {
        val store = DemoChatStore()
        val snapshot = repo(store).conversations()
        val now = snapshot.generatedAt
        val active = snapshot.conversations.filter { pl.trainerapp.feature.chat.model.isActive(it.peerActiveAt, now) }.map { it.relationshipId }
        assertEquals(TrainerChatsFixtures.activeIds, active.toSet())
        assertFalse(pl.trainerapp.feature.chat.model.isActive(null, now))
        assertFalse(pl.trainerapp.feature.chat.model.isActive(now - kotlin.time.Duration.parse("1h"), now))
    }

    @Test
    fun `the conversation view model exposes the receipt and the activity`() = runTest {
        val store = DemoChatStore()
        val trainer = repo(store)
        trainer.send("c1", "Cześć")
        DemoClientChatRepository(store, "c1", latencyMillis = 0).thread()

        val vm = TrainerChatViewModel("c1", trainer)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(state.messages.last { it.author == ChatRole.Trainer }.id, state.seenMessageId)
        assertTrue(state.seenAt != null)
        assertTrue(state.peerActiveAt != null)
    }

    @Test
    fun `client list shows only the client's own conversation, named after the trainer`() = runTest {
        val store = DemoChatStore()
        val vm = TrainerChatListViewModel(DemoClientConversations(store, setOf("c6"), latencyMillis = 0))
        advanceUntilIdle()
        val rows = vm.state.value.snapshot!!.conversations
        assertEquals(listOf("c6"), rows.map { it.relationshipId })
        assertEquals(TrainerChatsFixtures.trainerName, rows.single().name)
        // The trainer's unread count is not the client's.
        assertEquals(0, rows.single().unread)
    }

    @Test
    fun `list shows conversations newest first with unread total`() = runTest {
        val vm = TrainerChatListViewModel(repo())
        advanceUntilIdle()
        val snapshot = vm.state.value.snapshot!!
        assertEquals(ChatLoadStatus.Content, vm.state.value.status)
        assertEquals("c7", snapshot.conversations.first().relationshipId)
        assertEquals(3, snapshot.totalUnread)
    }

    @Test
    fun `opening a conversation emits the relationship id`() = runTest {
        val vm = TrainerChatListViewModel(repo())
        advanceUntilIdle()
        vm.onAction(ChatListAction.Open(vm.state.value.snapshot!!.conversations.first()))
        advanceUntilIdle()
        assertEquals(ChatListEvent.OpenChat("c7"), vm.events.first())
    }

    @Test
    fun `opening a thread marks it read`() = runTest {
        val repo = repo()
        val chat = TrainerChatViewModel("c7", repo)
        advanceUntilIdle()
        assertEquals("Piotr Szymański", chat.state.value.name)
        val list = TrainerChatListViewModel(repo)
        advanceUntilIdle()
        assertEquals(1, list.state.value.snapshot!!.totalUnread)
    }

    @Test
    fun `sending appends the message and clears the draft`() = runTest {
        val vm = TrainerChatViewModel("c1", repo())
        advanceUntilIdle()
        val before = vm.state.value.messages.size
        vm.onAction(ChatAction.DraftChanged("  Do zobaczenia!  "))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(before + 1, state.messages.size)
        assertEquals("Do zobaczenia!", state.messages.last().text)
        assertEquals(ChatRole.Trainer, state.messages.last().author)
        assertEquals("", state.draft)
    }

    @Test
    fun `blank drafts are not sent`() = runTest {
        val vm = TrainerChatViewModel("c1", repo())
        advanceUntilIdle()
        val before = vm.state.value.messages.size
        vm.onAction(ChatAction.DraftChanged("   "))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertEquals(before, vm.state.value.messages.size)
    }

    @Test
    fun `failed send keeps the draft and flags the error`() = runTest {
        val vm = TrainerChatViewModel("c1", FailingSend(repo()))
        advanceUntilIdle()
        vm.onAction(ChatAction.DraftChanged("Hej"))
        vm.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertEquals("Hej", vm.state.value.draft)
        assertTrue(vm.state.value.sendFailed)
        assertEquals(false, vm.state.value.isSending)
    }

    @Test
    fun `a client message appears in the trainer list with an unread count and the other way round`() = runTest {
        val store = DemoChatStore()
        val client = ClientChatViewModel(DemoClientChatRepository(store, "c1", latencyMillis = 0))
        advanceUntilIdle()
        assertEquals("Michał", client.state.value.name)
        client.onAction(ChatAction.DraftChanged("Czy mogę dziś przyjść 10 min później?"))
        client.onAction(ChatAction.Send)
        advanceUntilIdle()

        val list = TrainerChatListViewModel(repo(store))
        advanceUntilIdle()
        val row = list.state.value.snapshot!!.conversations.first()
        assertEquals("c1", row.relationshipId)
        assertEquals(1, row.unread)
        assertFalse(row.lastFromMe)

        val trainer = TrainerChatViewModel("c1", repo(store))
        advanceUntilIdle()
        assertEquals("Czy mogę dziś przyjść 10 min później?", trainer.state.value.messages.last().text)
        trainer.onAction(ChatAction.DraftChanged("Jasne."))
        trainer.onAction(ChatAction.Send)
        advanceUntilIdle()
        assertEquals(1, store.conversations(ChatRole.Client).first { it.relationshipId == "c1" }.unread)
    }

    @Test
    fun `an attachment follows its upload state and is playable only when ready`() = runTest {
        val store = DemoChatStore()
        val media = FakeMedia()
        store.send("c1", ChatRole.Client, "", MediaRef("v1", "Przysiad", 2, 3))
        val vm = ClientChatViewModel(DemoClientChatRepository(store, "c1", latencyMillis = 0), media)
        advanceUntilIdle()
        assertNull(vm.state.value.media["v1"])
        val uploading = DemoChatMedia.fixtures.getValue("m1").copy(id = "v1", state = MediaState.UPLOADING, progress = 0.4f)
        media.assets.value = mapOf("v1" to uploading)
        advanceUntilIdle()
        assertEquals(MediaState.UPLOADING, vm.state.value.media["v1"]?.state)
        media.assets.value = mapOf("v1" to uploading.copy(state = MediaState.READY, progress = 1f))
        advanceUntilIdle()
        assertEquals(MediaState.READY, vm.state.value.media["v1"]?.state)
    }

    @Test
    fun `reviewing a video removes it from pending reviews and a reply quotes it`() = runTest {
        val store = DemoChatStore()
        assertEquals(listOf("m1"), store.pendingReviews().map { it.mediaId })
        val review = TechniqueVideoViewModel("m1", ChatRole.Trainer, store, repo(store), FakeMedia(DemoChatMedia.fixtures))
        advanceUntilIdle()
        assertEquals(ReviewStatus.Content, review.state.value.status)
        assertEquals("Ania Kruk", review.state.value.clientName)
        review.onReplyChange("Plecy proste, dobrze!")
        review.sendReply()
        review.sendReply()
        advanceUntilIdle()
        val thread = store.open("c6", ChatRole.Trainer).messages
        assertEquals(1, thread.count { it.quotedMediaId == "m1" })
        review.markReviewed()
        advanceUntilIdle()
        assertTrue(store.pendingReviews().isEmpty())
    }
}
