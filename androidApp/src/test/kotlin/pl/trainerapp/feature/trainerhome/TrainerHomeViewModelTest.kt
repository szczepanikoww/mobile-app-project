package pl.trainerapp.feature.trainerhome

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
import org.junit.Before
import org.junit.Test
import pl.trainerapp.feature.trainerhome.data.TrainerHomeFixtures
import pl.trainerapp.feature.trainerhome.model.HomeSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerHomeViewModelTest {

    private class FakeRepository(
        var offline: Boolean = false,
        var fail: Boolean = false,
    ) : TrainerHomeRepository {
        override suspend fun load(): HomeSnapshot {
            if (fail) error("boom")
            return HomeSnapshot(TrainerHomeFixtures.summary, offline, TrainerHomeFixtures.now)
        }
    }

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads summary into content state`() = runTest {
        val vm = TrainerHomeViewModel(FakeRepository())
        advanceUntilIdle()
        assertEquals(LoadStatus.Content, vm.state.value.status)
        assertEquals(TrainerHomeFixtures.summary, vm.state.value.summary)
    }

    @Test
    fun `carousel lists the next session first`() {
        val ids = TrainerHomeFixtures.summary.carouselSessions.map { it.id }
        assertEquals(listOf("s1", "s2", "s3", "s4"), ids)
    }

    @Test
    fun `failure without cache shows error`() = runTest {
        val vm = TrainerHomeViewModel(FakeRepository(fail = true))
        advanceUntilIdle()
        assertEquals(LoadStatus.Error, vm.state.value.status)
    }

    @Test
    fun `failed refresh keeps cached content and flags offline`() = runTest {
        val repo = FakeRepository()
        val vm = TrainerHomeViewModel(repo)
        advanceUntilIdle()
        repo.fail = true
        vm.onAction(TrainerHomeAction.Refresh)
        advanceUntilIdle()
        assertEquals(LoadStatus.Content, vm.state.value.status)
        assertEquals(true, vm.state.value.isOffline)
    }

    @Test
    fun `check-in is ignored while offline`() = runTest {
        val vm = TrainerHomeViewModel(FakeRepository(offline = true))
        advanceUntilIdle()
        vm.onAction(TrainerHomeAction.CheckIn(TrainerHomeFixtures.nextSession))
        vm.onAction(TrainerHomeAction.OpenCalendar)
        advanceUntilIdle()
        assertEquals(TrainerHomeEvent.Navigate(TrainerHomeDestination.Calendar), vm.events.first())
    }
}
