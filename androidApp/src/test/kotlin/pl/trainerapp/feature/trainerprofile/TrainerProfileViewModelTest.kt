package pl.trainerapp.feature.trainerprofile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.trainerapp.feature.trainerfinance.model.Pln
import pl.trainerapp.feature.trainerprofile.data.DemoTrainerProfileRepository
import pl.trainerapp.feature.trainerprofile.model.AvailabilityDay
import pl.trainerapp.feature.trainerprofile.model.SettingKey
import pl.trainerapp.feature.trainerprofile.model.TrainerProfile
import pl.trainerapp.feature.trainerprofile.model.TrainerSettings

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerProfileViewModelTest {

    private class FailingSaves(private val delegate: TrainerProfileRepository) : TrainerProfileRepository by delegate {
        override suspend fun saveProfile(profile: TrainerProfile) = error("boom")
        override suspend fun saveSettings(settings: TrainerSettings) = error("boom")
        override suspend fun saveAvailability(days: List<AvailabilityDay>) = error("boom")
    }

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads the profile with a clean form`() = runTest {
        val vm = TrainerProfileViewModel(DemoTrainerProfileRepository(latencyMillis = 0))
        advanceUntilIdle()
        assertEquals(ProfileLoadStatus.Content, vm.state.value.status)
        assertFalse(vm.state.value.isDirty)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `editing makes the form dirty and saving stores it`() = runTest {
        val repo = DemoTrainerProfileRepository(latencyMillis = 0)
        val vm = TrainerProfileViewModel(repo)
        advanceUntilIdle()
        vm.onAction(TrainerProfileAction.EditField(ProfileField.Location, "  Warszawa "))
        vm.onAction(TrainerProfileAction.EditField(ProfileField.Rate, "1a5b0"))
        assertTrue(vm.state.value.canSave)
        vm.onAction(TrainerProfileAction.SaveProfile)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("Warszawa", state.profile!!.location)
        assertEquals(Pln.zl(150), state.profile!!.hourlyRate)
        assertFalse(state.isDirty)
        assertTrue(state.justSaved)
        assertEquals("Warszawa", repo.load().profile.location)
    }

    @Test
    fun `invalid rate or blank name blocks saving`() = runTest {
        val vm = TrainerProfileViewModel(DemoTrainerProfileRepository(latencyMillis = 0))
        advanceUntilIdle()
        vm.onAction(TrainerProfileAction.EditField(ProfileField.Rate, ""))
        assertFalse(vm.state.value.canSave)
        vm.onAction(TrainerProfileAction.EditField(ProfileField.Rate, "120"))
        vm.onAction(TrainerProfileAction.EditField(ProfileField.Name, "   "))
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `failed save keeps the edits and flags the error`() = runTest {
        val vm = TrainerProfileViewModel(FailingSaves(DemoTrainerProfileRepository(latencyMillis = 0)))
        advanceUntilIdle()
        vm.onAction(TrainerProfileAction.EditField(ProfileField.Location, "Gdańsk"))
        vm.onAction(TrainerProfileAction.SaveProfile)
        advanceUntilIdle()
        assertTrue(vm.state.value.saveFailed)
        assertTrue(vm.state.value.isDirty)
        assertEquals("Gdańsk", vm.state.value.draft!!.location)
    }

    @Test
    fun `settings toggles are stored and rolled back on failure`() = runTest {
        val ok = TrainerProfileViewModel(DemoTrainerProfileRepository(latencyMillis = 0))
        advanceUntilIdle()
        ok.onAction(TrainerProfileAction.SetSetting(SettingKey.EmailSummary, true))
        advanceUntilIdle()
        assertTrue(ok.state.value.settings!!.emailSummary)

        val failing = TrainerProfileViewModel(FailingSaves(DemoTrainerProfileRepository(latencyMillis = 0)))
        advanceUntilIdle()
        failing.onAction(TrainerProfileAction.SetSetting(SettingKey.EmailSummary, true))
        advanceUntilIdle()
        assertFalse(failing.state.value.settings!!.emailSummary)
    }

    @Test
    fun `availability days can be switched and rolled back on failure`() = runTest {
        val ok = TrainerProfileViewModel(DemoTrainerProfileRepository(latencyMillis = 0))
        advanceUntilIdle()
        ok.onAction(TrainerProfileAction.SetDayEnabled(5, true))
        advanceUntilIdle()
        assertTrue(ok.state.value.availability[5].enabled)

        val failing = TrainerProfileViewModel(FailingSaves(DemoTrainerProfileRepository(latencyMillis = 0)))
        advanceUntilIdle()
        failing.onAction(TrainerProfileAction.SetDayEnabled(5, true))
        advanceUntilIdle()
        assertFalse(failing.state.value.availability[5].enabled)
    }

    @Test
    fun `selecting a tab changes the visible tab`() = runTest {
        val vm = TrainerProfileViewModel(DemoTrainerProfileRepository(latencyMillis = 0))
        advanceUntilIdle()
        vm.onAction(TrainerProfileAction.SelectTab(ProfileTab.Payments))
        assertEquals(ProfileTab.Payments, vm.state.value.tab)
    }
}
