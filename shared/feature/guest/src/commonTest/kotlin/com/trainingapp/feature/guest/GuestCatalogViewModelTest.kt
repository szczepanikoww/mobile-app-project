package com.trainingapp.feature.guest

import com.trainingapp.domain.common.TimeSource
import com.trainingapp.feature.guest.data.CatalogScenario
import com.trainingapp.feature.guest.data.DemoCatalog
import com.trainingapp.feature.guest.data.DemoGuestCatalogRepository
import com.trainingapp.feature.guest.gymdetail.GymDetailViewModel
import com.trainingapp.feature.guest.model.Goal
import com.trainingapp.feature.guest.model.TrainerSort
import com.trainingapp.feature.guest.model.ViewMode
import com.trainingapp.feature.guest.trainerprofile.DetailContent
import com.trainingapp.feature.guest.trainerprofile.TrainerProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GuestCatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = DemoGuestCatalogRepository(latencyMs = 50)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startSearchCarriesOverToTrainersTab() = runTest(dispatcher) {
        val model = GuestCatalogViewModel(repository, FakeNetwork())
        advanceUntilIdle()
        model.searchFromStart("  Wiśniewska ")
        advanceUntilIdle()
        val state = model.state.value
        assertEquals(GuestTab.Trainers, state.tab)
        assertEquals("Wiśniewska", state.trainerQuery.text)
        assertEquals(listOf("tr-anna"), state.trainers.dataOrNull?.map { it.id })
    }

    @Test
    fun goalTileSetsOnlyThatGoal() = runTest(dispatcher) {
        val model = GuestCatalogViewModel(repository, FakeNetwork())
        model.browseGoal(Goal.Mobility)
        advanceUntilIdle()
        assertEquals(setOf(Goal.Mobility), model.state.value.trainerQuery.goals)
        assertTrue(model.state.value.trainers.dataOrNull!!.all { Goal.Mobility in it.goals })
    }

    @Test
    fun tabSwitchKeepsQueryAndViewMode() = runTest(dispatcher) {
        val model = GuestCatalogViewModel(repository, FakeNetwork())
        model.selectTab(GuestTab.Trainers)
        model.onTrainerQueryChange(model.state.value.trainerQuery.copy(sort = TrainerSort.Rating, goals = setOf(Goal.Strength)))
        model.onViewModeChange(ViewMode.Grid)
        model.selectTab(GuestTab.Gyms)
        model.selectTab(GuestTab.Trainers)
        advanceUntilIdle()
        with(model.state.value) {
            assertEquals(TrainerSort.Rating, trainerQuery.sort)
            assertEquals(setOf(Goal.Strength), trainerQuery.goals)
            assertEquals(ViewMode.Grid, viewMode)
        }
    }

    @Test
    fun typingIsDebounced() = runTest(dispatcher) {
        val model = GuestCatalogViewModel(repository, FakeNetwork())
        advanceUntilIdle()
        val total = model.state.value.trainers.dataOrNull!!.size
        model.onTrainerTextChange("m")
        model.onTrainerTextChange("ma")
        model.onTrainerTextChange("mag")
        advanceTimeBy(200)
        assertEquals(total, model.state.value.trainers.dataOrNull!!.size, "results stay while typing")
        advanceUntilIdle()
        assertEquals(listOf("tr-magdalena"), model.state.value.trainers.dataOrNull!!.map { it.id })
    }

    @Test
    fun errorThenRetry() = runTest(dispatcher) {
        repository.scenario = CatalogScenario.Error
        val model = GuestCatalogViewModel(repository, FakeNetwork())
        advanceUntilIdle()
        assertIs<Load.Failed>(model.state.value.home)
        assertIs<Load.Failed>(model.state.value.trainers)
        repository.scenario = CatalogScenario.Normal
        model.retryHome()
        model.retryTrainers()
        advanceUntilIdle()
        assertIs<Load.Ready<*>>(model.state.value.home)
        assertIs<Load.Ready<*>>(model.state.value.trainers)
    }

    @Test
    fun offlineKeepsLastDataAndShowsTheBanner() = runTest(dispatcher) {
        val network = FakeNetwork()
        val model = GuestCatalogViewModel(repository, network)
        advanceUntilIdle()
        network.isOnline.value = false
        repository.scenario = CatalogScenario.Error
        model.retryTrainers()
        advanceUntilIdle()
        assertTrue(model.state.value.offline)
        assertIs<Load.Ready<*>>(model.state.value.trainers)
    }

    @Test
    fun trainerProfileNotFoundAndLoginSheet() = runTest(dispatcher) {
        val missing = TrainerProfileViewModel("nobody", repository, FakeNetwork())
        advanceUntilIdle()
        assertEquals(DetailContent.NotFound, missing.state.value.content)

        val profile = TrainerProfileViewModel("tr-michal", repository, FakeNetwork())
        advanceUntilIdle()
        assertIs<DetailContent.Ready<*>>(profile.state.value.content)
        profile.requestSession()
        assertTrue(profile.state.value.loginSheetVisible)
        profile.dismissLoginSheet()
        assertIs<DetailContent.Ready<*>>(profile.state.value.content, "the screen behind keeps its state")
    }

    @Test
    fun gymOpenNowUsesTheInjectedClock() = runTest(dispatcher) {
        val warsaw = TimeZone.of("Europe/Warsaw")
        val wednesdayNoon = LocalDateTime(2026, 11, 4, 12, 0).toInstant(warsaw).toEpochMilliseconds()
        val wednesdayLate = LocalDateTime(2026, 11, 4, 23, 30).toInstant(warsaw).toEpochMilliseconds()
        val open = GymDetailViewModel("gym-centrum", repository, FakeNetwork(), TimeSource { wednesdayNoon })
        val closed = GymDetailViewModel("gym-centrum", repository, FakeNetwork(), TimeSource { wednesdayLate })
        val allDay = GymDetailViewModel("gym-wola", repository, FakeNetwork(), TimeSource { wednesdayLate })
        advanceUntilIdle()
        assertEquals(true, open.state.value.openNow)
        assertEquals(false, closed.state.value.openNow)
        assertEquals(true, allDay.state.value.openNow)
    }
}
