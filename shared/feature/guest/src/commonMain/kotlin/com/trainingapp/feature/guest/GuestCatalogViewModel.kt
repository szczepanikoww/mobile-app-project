package com.trainingapp.feature.guest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.feature.guest.model.FilterOptions
import com.trainingapp.feature.guest.model.Goal
import com.trainingapp.feature.guest.model.GuestCatalogRepository
import com.trainingapp.feature.guest.model.GuestHome
import com.trainingapp.feature.guest.model.GymQuery
import com.trainingapp.feature.guest.model.GymSummary
import com.trainingapp.feature.guest.model.TrainerQuery
import com.trainingapp.feature.guest.model.TrainerSummary
import com.trainingapp.feature.guest.model.ViewMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A loaded value, keeping the last data while reloading so offline and refresh states never blank the screen. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object Failed : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
}

val <T> Load<T>.dataOrNull: T? get() = (this as? Load.Ready<T>)?.data

enum class GuestTab { Start, Trainers, Gyms }

data class GuestCatalogUiState(
    val tab: GuestTab = GuestTab.Start,
    val home: Load<GuestHome> = Load.Loading,
    val trainerQuery: TrainerQuery = TrainerQuery(),
    val trainers: Load<List<TrainerSummary>> = Load.Loading,
    val viewMode: ViewMode = ViewMode.List,
    val filterOptions: FilterOptions = FilterOptions(emptyList(), emptyList()),
    val gymQuery: GymQuery = GymQuery(),
    val gyms: Load<List<GymSummary>> = Load.Loading,
    /** Set when Start asks the Trainers tab to open a filter sheet (filter icon, quiz prompt). */
    val pendingFilterSheet: TrainerFilter? = null,
    val offline: Boolean = false,
)

/** The filter chips of the Trainers tab, each opening its own sheet. */
enum class TrainerFilter { Goal, Gym, Price, District, Availability }

/**
 * Shell-scoped state shared by the Start, Trainers and Gyms tabs, so a Start search or goal tile updates the
 * Trainers tab, and switching tabs keeps the search and filters.
 */
class GuestCatalogViewModel(
    private val repository: GuestCatalogRepository,
    network: NetworkMonitor,
    private val searchDebounceMs: Long = 300,
) : ViewModel() {

    private val mutableState = MutableStateFlow(GuestCatalogUiState(offline = !network.isOnline.value))
    val state: StateFlow<GuestCatalogUiState> = mutableState.asStateFlow()

    private val trainerReload = MutableStateFlow(0)
    private val gymReload = MutableStateFlow(0)
    private val trainerQuery = MutableStateFlow(TrainerQuery())
    private val gymQuery = MutableStateFlow(GymQuery())

    init {
        viewModelScope.launch { network.isOnline.collect { online -> mutableState.update { it.copy(offline = !online) } } }
        loadHome()
        viewModelScope.launch {
            runCatching { repository.filterOptions() }.onSuccess { options -> mutableState.update { it.copy(filterOptions = options) } }
        }
        viewModelScope.launch {
            var lastText: String? = null
            combine(trainerQuery, trainerReload) { query, _ -> query }.collectLatest { query ->
                // Typing is debounced; filter, sort and retry changes apply at once.
                if (lastText != null && query.text != lastText) delay(searchDebounceMs)
                lastText = query.text
                mutableState.update { it.copy(trainers = it.trainers.keepOr(Load.Loading)) }
                val result = load { repository.trainers(query) }
                mutableState.update { it.copy(trainers = result ?: it.trainers.keepOr(Load.Failed)) }
            }
        }
        viewModelScope.launch {
            var lastText: String? = null
            combine(gymQuery, gymReload) { query, _ -> query }.collectLatest { query ->
                if (lastText != null && query.text != lastText) delay(searchDebounceMs)
                lastText = query.text
                mutableState.update { it.copy(gyms = it.gyms.keepOr(Load.Loading)) }
                val result = load { repository.gyms(query) }
                mutableState.update { it.copy(gyms = result ?: it.gyms.keepOr(Load.Failed)) }
            }
        }
    }

    private fun <T> Load<T>.keepOr(fallback: Load<T>): Load<T> = if (this is Load.Ready) this else fallback

    private suspend fun <T> load(block: suspend () -> T): Load.Ready<T>? = try {
        Load.Ready(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun loadHome() {
        mutableState.update { it.copy(home = it.home.keepOr(Load.Loading)) }
        viewModelScope.launch {
            val result = load { repository.home() }
            mutableState.update { it.copy(home = result ?: it.home.keepOr(Load.Failed)) }
        }
    }

    fun selectTab(tab: GuestTab) = mutableState.update { it.copy(tab = tab) }

    fun retryHome() = loadHome()

    fun retryTrainers() {
        trainerReload.value++
    }

    fun retryGyms() {
        gymReload.value++
    }

    private fun setTrainerQuery(query: TrainerQuery) {
        trainerQuery.value = query
        mutableState.update { it.copy(trainerQuery = query) }
    }

    fun onTrainerQueryChange(query: TrainerQuery) = setTrainerQuery(query)

    fun onTrainerTextChange(text: String) = setTrainerQuery(trainerQuery.value.copy(text = text))

    fun clearTrainerFilters() = setTrainerQuery(trainerQuery.value.cleared())

    fun onViewModeChange(mode: ViewMode) = mutableState.update { it.copy(viewMode = mode) }

    fun onGymQueryChange(query: GymQuery) {
        gymQuery.value = query
        mutableState.update { it.copy(gymQuery = query) }
    }

    /** Start's search: applies the text to the Trainers tab and opens it. */
    fun searchFromStart(text: String) {
        setTrainerQuery(trainerQuery.value.copy(text = text.trim()))
        selectTab(GuestTab.Trainers)
    }

    /** A goal tile on Start: the Trainers tab with only that goal selected. */
    fun browseGoal(goal: Goal) {
        setTrainerQuery(trainerQuery.value.copy(goals = setOf(goal)))
        selectTab(GuestTab.Trainers)
    }

    /** Opens the Trainers tab with a filter sheet already open (filter icon on Start, quiz prompt, D4). */
    fun openTrainerFilter(filter: TrainerFilter) = mutableState.update { it.copy(tab = GuestTab.Trainers, pendingFilterSheet = filter) }

    fun consumePendingFilterSheet() = mutableState.update { it.copy(pendingFilterSheet = null) }
}
