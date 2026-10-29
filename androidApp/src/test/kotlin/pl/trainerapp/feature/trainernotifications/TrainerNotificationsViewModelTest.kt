package pl.trainerapp.feature.trainernotifications

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
import pl.trainerapp.feature.trainernotifications.data.DemoTrainerNotificationsRepository
import pl.trainerapp.feature.trainernotifications.model.NotificationTarget

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerNotificationsViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `list is sorted newest first and counts unread`() = runTest {
        val vm = TrainerNotificationListViewModel(DemoTrainerNotificationsRepository(latencyMillis = 0))
        advanceUntilIdle()
        val snapshot = vm.state.value.snapshot!!
        assertEquals("n2", snapshot.items.first().id)
        assertEquals(3, snapshot.unreadCount)
    }

    @Test
    fun `opening a notification emits its id`() = runTest {
        val vm = TrainerNotificationListViewModel(DemoTrainerNotificationsRepository(latencyMillis = 0))
        advanceUntilIdle()
        vm.onAction(NotificationListAction.Open(vm.state.value.snapshot!!.items.first()))
        advanceUntilIdle()
        assertEquals(NotificationListEvent.OpenNotification("n2"), vm.events.first())
    }

    @Test
    fun `detail marks the notification read`() = runTest {
        val repo = DemoTrainerNotificationsRepository(latencyMillis = 0)
        val detail = TrainerNotificationDetailViewModel("n2", repo)
        advanceUntilIdle()
        assertEquals(NotificationTarget.Chat("c7", "Piotr Szymański"), detail.state.value.detail!!.notification.target)
        assertTrue(detail.state.value.detail!!.notification.read)

        val list = TrainerNotificationListViewModel(repo)
        advanceUntilIdle()
        assertEquals(2, list.state.value.snapshot!!.unreadCount)
    }

    @Test
    fun `unknown notification shows error`() = runTest {
        val vm = TrainerNotificationDetailViewModel("missing", DemoTrainerNotificationsRepository(latencyMillis = 0))
        advanceUntilIdle()
        assertEquals(NotificationsLoadStatus.Error, vm.state.value.status)
    }

    @Test
    fun `mark all read clears the unread count`() = runTest {
        val vm = TrainerNotificationListViewModel(DemoTrainerNotificationsRepository(latencyMillis = 0))
        advanceUntilIdle()
        vm.onAction(NotificationListAction.MarkAllRead)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.snapshot!!.unreadCount)
    }
}
