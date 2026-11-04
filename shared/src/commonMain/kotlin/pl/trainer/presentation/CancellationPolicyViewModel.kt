package pl.trainer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.PolicyError
import com.trainingapp.contract.policy.PolicyPreset
import com.trainingapp.contract.policy.PolicyPresets
import com.trainingapp.contract.policy.PolicyRules
import com.trainingapp.contract.policy.PolicyTier
import com.trainingapp.contract.policy.PolicyValidator
import com.trainingapp.contract.policy.PolicyWarning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.trainer.domain.Failure
import pl.trainer.domain.PolicyRepository
import pl.trainer.domain.TrainerException

data class PolicyEditorUiState(
    val loading: Boolean = true,
    val current: CancellationPolicy? = null,
    /** The trainer's demo session rate: amounts are shown next to percentages. */
    val price: Money? = null,
    val draft: PolicyRules? = null,
    val loadFailure: Failure? = null,
    val saving: Boolean = false,
    val saveFailure: Failure? = null,
    /** The version just saved, for the confirmation message. */
    val savedVersion: Int? = null,
) {
    /** The preset the draft matches, or null for "Własna konfiguracja". */
    val preset: PolicyPreset? get() = draft?.preset
    val dirty: Boolean get() = draft != null && current != null && draft.copy(tiers = draft.sortedTiers) != current.rules
    val errors: Set<PolicyError> get() = draft?.let(PolicyValidator::errors).orEmpty()
    val warnings: Set<PolicyWarning> get() = draft?.let(PolicyValidator::warnings).orEmpty()
    val canSave: Boolean get() = dirty && errors.isEmpty() && !saving
}

/**
 * B5 "Polityka odwołań": edits the trainer's rules as a draft; saving creates a new immutable version that applies
 * to bookings made afterwards. Aggressive settings show a warning but never block saving (architecture 3.2).
 */
class CancellationPolicyViewModel(
    private val repository: PolicyRepository,
    private val trainerId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PolicyEditorUiState())
    val state: StateFlow<PolicyEditorUiState> = mutableState.asStateFlow()

    init { reload() }

    fun reload() {
        mutableState.update { it.copy(loading = it.current == null, loadFailure = null) }
        viewModelScope.launch {
            try {
                val current = repository.current(trainerId)
                val price = repository.sessionPrice(trainerId)
                mutableState.update { state ->
                    // A reload keeps unsaved edits; only a first load starts from the current version.
                    state.copy(loading = false, current = current, price = price, draft = state.draft ?: current.rules)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, loadFailure = (e as? TrainerException)?.failure ?: Failure.Load) }
            }
        }
    }

    private fun edit(change: (PolicyRules) -> PolicyRules) =
        mutableState.update { state -> state.draft?.let { state.copy(draft = change(it), saveFailure = null, savedVersion = null) } ?: state }

    fun applyPreset(preset: PolicyPreset) = edit { PolicyPresets.rules(preset) }

    /** Tiers are edited in display order: from the earliest (largest hours) to the latest. */
    fun setTierHours(index: Int, hours: Int) = edit { rules ->
        rules.copy(tiers = rules.sortedTiers.mapIndexed { i, tier -> if (i == index) tier.copy(hoursBefore = hours) else tier })
    }

    fun setTierPercent(index: Int, percent: Int) = edit { rules ->
        rules.copy(tiers = rules.sortedTiers.mapIndexed { i, tier -> if (i == index) tier.copy(feePercent = percent) else tier })
    }

    /** Adds a later tier (fewer hours, higher fee) below the last one. At most [MAX_TIERS]. */
    fun addTier() = edit { rules ->
        val tiers = rules.sortedTiers
        if (tiers.size >= MAX_TIERS) return@edit rules
        val last = tiers.lastOrNull()
        val hours = HourSteps.lastOrNull { last == null || it < last.hoursBefore } ?: return@edit rules
        val percent = PolicyValidator.allowedPercents.firstOrNull { last == null || it > last.feePercent } ?: 100
        rules.copy(tiers = tiers + PolicyTier(hours, percent))
    }

    fun removeTier(index: Int) = edit { rules ->
        if (rules.sortedTiers.size <= 1) rules else rules.copy(tiers = rules.sortedTiers.filterIndexed { i, _ -> i != index })
    }

    fun setNoShowPercent(percent: Int) = edit { it.copy(noShowPercent = percent) }

    fun setGraceMinutes(minutes: Int) = edit { it.copy(graceMinutes = minutes.coerceIn(PolicyValidator.graceRange)) }

    fun setFreeLateCancellations(count: Int) = edit { it.copy(freeLateCancellationsPerMonth = count.coerceIn(0, PolicyValidator.MAX_FREE_CANCELLATIONS)) }

    fun discard() = mutableState.update { state -> state.copy(draft = state.current?.rules, saveFailure = null) }

    fun dismissSaved() = mutableState.update { it.copy(savedVersion = null) }

    fun save() {
        val state = state.value
        val current = state.current ?: return
        val draft = state.draft ?: return
        if (!state.canSave) return
        mutableState.update { it.copy(saving = true, saveFailure = null) }
        viewModelScope.launch {
            try {
                val saved = repository.save(trainerId, draft, current.versionId)
                mutableState.update { it.copy(saving = false, current = saved, draft = saved.rules, savedVersion = saved.version) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                // The draft stays, so nothing typed is lost; a conflict reloads the newer current version.
                val failure = (e as? TrainerException)?.failure ?: Failure.Save
                mutableState.update { it.copy(saving = false, saveFailure = failure) }
                if (failure == Failure.Conflict) reload()
            }
        }
    }

    companion object {
        const val MAX_TIERS = 3

        /** The hours a tier can start at; the stepper moves through them. */
        val HourSteps = listOf(2, 4, 6, 12, 24, 36, 48, 72)
    }
}
