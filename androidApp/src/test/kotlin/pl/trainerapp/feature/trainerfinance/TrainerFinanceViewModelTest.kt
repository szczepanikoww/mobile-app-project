package pl.trainerapp.feature.trainerfinance

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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import pl.trainerapp.core.format.formatPln
import pl.trainerapp.feature.trainerfinance.data.TrainerFinanceFixtures
import pl.trainerapp.feature.trainerfinance.model.FinancePeriod
import pl.trainerapp.feature.trainerfinance.model.FinanceSnapshot
import pl.trainerapp.feature.trainerfinance.model.Pln

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerFinanceViewModelTest {

    private class FakeRepository(
        var offline: Boolean = false,
        var fail: Boolean = false,
    ) : TrainerFinanceRepository {
        val requested = mutableListOf<FinancePeriod>()

        override suspend fun load(period: FinancePeriod): FinanceSnapshot {
            requested += period
            if (fail) error("boom")
            return FinanceSnapshot(TrainerFinanceFixtures.forPeriod(period), offline, TrainerFinanceFixtures.now)
        }
    }

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads the weekly summary into content state`() = runTest {
        val vm = TrainerFinanceViewModel(FakeRepository())
        advanceUntilIdle()
        assertEquals(FinanceLoadStatus.Content, vm.state.value.status)
        assertEquals(TrainerFinanceFixtures.week, vm.state.value.summary)
    }

    @Test
    fun `selecting a period reloads for that period`() = runTest {
        val repo = FakeRepository()
        val vm = TrainerFinanceViewModel(repo)
        advanceUntilIdle()
        vm.onAction(TrainerFinanceAction.SelectPeriod(FinancePeriod.Month))
        advanceUntilIdle()
        assertEquals(FinancePeriod.Month, vm.state.value.period)
        assertEquals(TrainerFinanceFixtures.month, vm.state.value.summary)
        assertEquals(listOf(FinancePeriod.Week, FinancePeriod.Month), repo.requested)
    }

    @Test
    fun `selecting the current period does not reload`() = runTest {
        val repo = FakeRepository()
        val vm = TrainerFinanceViewModel(repo)
        advanceUntilIdle()
        vm.onAction(TrainerFinanceAction.SelectPeriod(FinancePeriod.Week))
        advanceUntilIdle()
        assertEquals(listOf(FinancePeriod.Week), repo.requested)
    }

    @Test
    fun `failure without cache shows error`() = runTest {
        val vm = TrainerFinanceViewModel(FakeRepository(fail = true))
        advanceUntilIdle()
        assertEquals(FinanceLoadStatus.Error, vm.state.value.status)
    }

    @Test
    fun `failed refresh keeps cached content and flags offline`() = runTest {
        val repo = FakeRepository()
        val vm = TrainerFinanceViewModel(repo)
        advanceUntilIdle()
        repo.fail = true
        vm.onAction(TrainerFinanceAction.Refresh)
        advanceUntilIdle()
        assertEquals(FinanceLoadStatus.Content, vm.state.value.status)
        assertEquals(true, vm.state.value.isOffline)
    }

    @Test
    fun `payout is ignored while offline`() = runTest {
        val vm = TrainerFinanceViewModel(FakeRepository(offline = true))
        advanceUntilIdle()
        vm.onAction(TrainerFinanceAction.RequestPayout)
        vm.onAction(TrainerFinanceAction.OpenAllPayments)
        advanceUntilIdle()
        assertEquals(TrainerFinanceEvent.Navigate(TrainerFinanceDestination.AllPayments), vm.events.first())
    }

    @Test
    fun `week summary derives change and goal progress`() {
        val week = TrainerFinanceFixtures.week
        assertEquals(18, week.changePercent)
        assertEquals(0.87f, week.goalProgress!!, 0.001f)
        assertNull(TrainerFinanceFixtures.year.goalProgress)
        assertNull(TrainerFinanceFixtures.empty.changePercent)
    }

    @Test
    fun `formats amounts in złoty with grouping`() {
        assertEquals("3 480 zł", formatPln(Pln.zl(3480)))
        assertEquals("530,00 zł", formatPln(Pln.zl(530), alwaysDecimals = true))
        assertEquals("12,50 zł", formatPln(Pln(1250)))
    }
}
