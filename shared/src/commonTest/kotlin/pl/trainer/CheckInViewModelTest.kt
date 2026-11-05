package pl.trainer

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import pl.trainer.data.DemoServerNow
import pl.trainer.data.DemoTrainerRepository
import kotlin.time.Duration.Companion.minutes
import pl.trainer.domain.*
import pl.trainer.presentation.Attendance
import pl.trainer.presentation.CheckInViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CheckInViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @BeforeTest fun before() { Dispatchers.setMain(StandardTestDispatcher()) }
    @AfterTest fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    private fun model(repository: TrainerRepository, id: String = "s1") =
        CheckInViewModel(id, repository).also { stores += ViewModelStore().also { s -> s.put("test", it) } }

    /** Counts commands, as a server would. */
    private class Counting(val delegate: DemoTrainerRepository) : TrainerRepository by delegate {
        var completes = 0
        override suspend fun completeSession(id: String, revision: Int): TrainerDashboard { completes++; return delegate.completeSession(id, revision) }
    }

    @Test fun doubleTapOnPresentCompletesOnce() = runTest {
        val repo = Counting(DemoTrainerRepository(0))
        val vm = model(repo)
        advanceUntilIdle()
        vm.markPresent()
        vm.markPresent()
        advanceUntilIdle()
        assertEquals(1, repo.completes)
        assertEquals(Attendance.Present, vm.state.value.recorded)
    }

    @Test fun noShowNeedsTheGracePeriodAndAConfirmation() = runTest {
        // 09:00: the session has started, but the 15 min grace period has not passed.
        val early = model(DemoTrainerRepository(0, serverNow = { DemoServerNow - 20.minutes }))
        advanceUntilIdle()
        early.requestNoShow()
        assertFalse(early.state.value.confirmNoShow)

        val vm = model(DemoTrainerRepository(0))
        advanceUntilIdle()
        vm.requestNoShow()
        assertTrue(vm.state.value.confirmNoShow)
        vm.confirmNoShow()
        advanceUntilIdle()
        assertEquals(Attendance.NoShow, vm.state.value.recorded)
        assertEquals(SessionStatus.NoShow, vm.state.value.info?.session?.status)
    }

    @Test fun staleRevisionShowsAConflictAndOfflineIsNeverDone() = runTest {
        val repo = DemoTrainerRepository(0)
        val vm = model(repo)
        advanceUntilIdle()
        val session = repo.load().sessions.first { it.id == "s1" }
        repo.cancelSession(session.id, session.revision) // changed elsewhere
        vm.markPresent()
        advanceUntilIdle()
        assertEquals(Failure.Conflict, vm.state.value.actionFailure)
        assertNull(vm.state.value.recorded)

        val offline = DemoTrainerRepository(0).apply { scenario = DemoScenario.Offline }
        val offlineVm = model(offline)
        advanceUntilIdle()
        offlineVm.markPresent()
        advanceUntilIdle()
        assertEquals(Failure.Offline, offlineVm.state.value.actionFailure)
        assertNull(offlineVm.state.value.recorded)
    }
}
