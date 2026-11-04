package pl.trainer

import androidx.lifecycle.ViewModelStore
import com.trainingapp.contract.policy.PolicyPreset
import com.trainingapp.contract.policy.PolicyPresets
import com.trainingapp.contract.policy.PolicyWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pl.trainer.data.DemoPolicyRepository
import pl.trainer.data.DemoSchedulingStore
import pl.trainer.data.DemoTrainerId
import pl.trainer.domain.Failure
import pl.trainer.presentation.CancellationPolicyViewModel
import kotlin.test.*

/** B5: presets, custom values, the non-blocking warning and versioning (plan §9, policy versions). */
@OptIn(ExperimentalCoroutinesApi::class)
class CancellationPolicyViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @BeforeTest fun before() { Dispatchers.setMain(StandardTestDispatcher()) }
    @AfterTest fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    private fun model(repository: DemoPolicyRepository) = CancellationPolicyViewModel(repository, DemoTrainerId).also { model ->
        stores += ViewModelStore().also { it.put("m", model) }
    }

    @Test fun presetsAndCustomValues() = runTest {
        val model = model(DemoPolicyRepository(DemoSchedulingStore(), latencyMillis = 0))
        advanceUntilIdle()
        // The demo trainer's first version (free until 24 h, then 50%) is a custom configuration.
        assertNull(model.state.value.preset)
        assertFalse(model.state.value.dirty)
        model.applyPreset(PolicyPreset.Standard)
        assertEquals(PolicyPreset.Standard, model.state.value.preset)
        assertTrue(model.state.value.canSave)
        model.setGraceMinutes(20)
        assertNull(model.state.value.preset)
        model.discard()
        assertFalse(model.state.value.dirty)
    }

    @Test fun aggressiveSettingsWarnButStillSaveANewVersion() = runTest {
        val repository = DemoPolicyRepository(DemoSchedulingStore(), latencyMillis = 0)
        val model = model(repository)
        advanceUntilIdle()
        model.applyPreset(PolicyPreset.Strict)
        model.setTierPercent(0, 100)
        model.setFreeLateCancellations(0)
        assertEquals(setOf(PolicyWarning.FullFeeEarly, PolicyWarning.FullFeeWithoutLeniency), model.state.value.warnings)
        assertTrue(model.state.value.canSave)
        model.save()
        model.save()
        advanceUntilIdle()
        assertEquals(2, model.state.value.savedVersion)
        assertEquals(2, repository.current(DemoTrainerId).version)
        assertFalse(model.state.value.dirty)
    }

    @Test fun invalidTiersCannotBeSaved() = runTest {
        val model = model(DemoPolicyRepository(DemoSchedulingStore(), latencyMillis = 0))
        advanceUntilIdle()
        model.applyPreset(PolicyPreset.Standard)
        model.setTierPercent(0, 100)
        model.setTierPercent(1, 50)
        assertFalse(model.state.value.canSave)
        assertTrue(model.state.value.errors.isNotEmpty())
    }

    @Test fun aConcurrentSaveIsAConflictAndKeepsTheDraft() = runTest {
        val store = DemoSchedulingStore()
        val repository = DemoPolicyRepository(store, latencyMillis = 0)
        val model = model(repository)
        advanceUntilIdle()
        model.applyPreset(PolicyPreset.Flexible)
        val v1 = repository.current(DemoTrainerId)
        repository.save(DemoTrainerId, PolicyPresets.rules(PolicyPreset.Strict), v1.versionId)
        model.save()
        advanceUntilIdle()
        assertEquals(Failure.Conflict, model.state.value.saveFailure)
        assertEquals(PolicyPreset.Flexible, model.state.value.preset)
        assertEquals(2, model.state.value.current?.version)
        model.save()
        advanceUntilIdle()
        assertEquals(3, repository.current(DemoTrainerId).version)
    }
}
