package pl.trainer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlin.time.Instant
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.domain.*

enum class Destination { Start, Calendar, Clients, Finance }
enum class ClientFilter { All, Overdue }
enum class ClientTab { Current, Archive }
enum class CalendarMode { Day, Week }
sealed interface Route {
    data object Main : Route
    data class SessionDetail(val id: String) : Route
    data class ClientDetail(val id: String) : Route
    data class SessionEditor(val sessionId: String? = null, val clientId: String? = null) : Route
    data object Availability : Route
    data class NoteEditor(val clientId: String) : Route
    data class ClientInformationEditor(val clientId: String) : Route
}

data class TrainerUiState(
    val data: TrainerDashboard? = null,
    val loading: Boolean = true,
    val failure: Failure? = null,
    val actionFailure: Failure? = null,
    val saved: Boolean = false,
    val submitting: Boolean = false,
    val scenario: DemoScenario = DemoScenario.Normal,
    val destination: Destination = Destination.Calendar,
    val routes: List<Route> = listOf(Route.Main),
    val selectedDate: LocalDate = DemoDate,
    val calendarMode: CalendarMode = CalendarMode.Day,
    val search: String = "",
    val clientFilter: ClientFilter = ClientFilter.All,
    val clientTab: ClientTab = ClientTab.Current,
    val informationClientId: String? = null,
    val clientInformation: ClientInformation? = null,
    val informationLoading: Boolean = false,
    val informationFailure: Failure? = null,
) {
    val route get() = routes.last()
    val offline get() = scenario == DemoScenario.Offline
    val visibleClients get() = data?.clients.orEmpty().filter {
        it.active == (clientTab == ClientTab.Current) && it.name.searchKey().contains(search.searchKey()) && when (clientFilter) {
            ClientFilter.All -> true
            ClientFilter.Overdue -> it.hasOverduePayment
        }
    }
}

class TrainerViewModel(private val repository: TrainerRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(TrainerUiState())
    val state = mutableState.asStateFlow()
    private var loadingJob: Job? = null
    private var informationJob: Job? = null

    init { reload() }

    fun reload() {
        loadingJob?.cancel()
        mutableState.update { it.copy(loading = true, failure = null, actionFailure = null) }
        if (state.value.scenario == DemoScenario.Loading) return
        loadingJob = viewModelScope.launch {
            try {
                val data = repository.load()
                mutableState.update { it.copy(data = data, loading = false) }
            } catch (e: TrainerException) {
                mutableState.update { it.copy(data = null, loading = false, failure = e.failure) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(data = null, loading = false, failure = Failure.Load) }
            }
        }
    }

    fun scenario(value: DemoScenario) {
        if (state.value.submitting) return
        (repository as? DemoTrainerRepository)?.scenario = value
        informationJob?.cancel()
        mutableState.update { it.copy(scenario = value, routes = listOf(Route.Main), saved = false,
            informationClientId = null, clientInformation = null, informationFailure = null, informationLoading = false) }
        reload()
    }
    fun destination(value: Destination) = mutableState.update { it.copy(destination = value, routes = listOf(Route.Main), actionFailure = null) }
    fun navigate(route: Route) {
        mutableState.update { it.copy(routes = it.routes + route, actionFailure = null, saved = false) }
        when (route) {
            is Route.ClientDetail -> loadClientInformation(route.id)
            is Route.ClientInformationEditor -> if (state.value.informationClientId != route.clientId || state.value.clientInformation == null) loadClientInformation(route.clientId)
            else -> Unit
        }
    }
    fun back() {
        if (state.value.submitting) return
        mutableState.update { it.copy(routes = it.routes.dropLast(1).ifEmpty { listOf(Route.Main) }, actionFailure = null) }
        (state.value.route as? Route.ClientDetail)?.let {
            if (state.value.informationClientId != it.id) loadClientInformation(it.id)
        }
    }
    fun date(date: LocalDate) = mutableState.update { it.copy(selectedDate = date) }
    fun mode(mode: CalendarMode) = mutableState.update { it.copy(calendarMode = mode) }
    fun search(query: String) = mutableState.update { it.copy(search = query) }
    fun filter(filter: ClientFilter) = mutableState.update { it.copy(clientFilter = filter) }
    fun clientTab(tab: ClientTab) = mutableState.update { it.copy(clientTab = tab, clientFilter = ClientFilter.All) }
    fun dismissFeedback() = mutableState.update { it.copy(saved = false) }
    fun clearActionFailure() = mutableState.update { it.copy(actionFailure = null) }

    private fun mutate(pop: Boolean = false, action: suspend () -> TrainerDashboard) {
        if (state.value.submitting) return
        mutableState.update { it.copy(submitting = true, actionFailure = null, saved = false) }
        viewModelScope.launch {
            try {
                val data = action()
                mutableState.update {
                    it.copy(data = data, submitting = false, saved = true,
                        routes = if (pop) it.routes.dropLast(1).ifEmpty { listOf(Route.Main) } else it.routes)
                }
            } catch (e: TrainerException) {
                mutableState.update { it.copy(submitting = false, actionFailure = e.failure) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(submitting = false, actionFailure = Failure.Save) }
            }
        }
    }

    fun accept(session: Session) = mutate { repository.acceptRequest(session.id, session.revision) }
    fun reject(session: Session) = mutate { repository.rejectRequest(session.id, session.revision) }
    fun cancel(session: Session) = mutate { repository.cancelSession(session.id, session.revision) }
    fun complete(session: Session) = mutate { repository.completeSession(session.id, session.revision) }
    fun removeBlock(id: String) = mutate { repository.removeBlock(id) }
    fun saveNote(clientId: String, note: String) = mutate(pop = true) { repository.saveNote(clientId, note) }

    fun loadClientInformation(clientId: String) {
        informationJob?.cancel()
        mutableState.update { it.copy(informationClientId = clientId, clientInformation = null, informationLoading = true, informationFailure = null) }
        informationJob = viewModelScope.launch {
            try {
                val information = repository.loadClientInformation(clientId)
                mutableState.update { if (it.informationClientId == clientId) it.copy(clientInformation = information, informationLoading = false) else it }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                val failure = (e as? TrainerException)?.failure ?: Failure.Load
                mutableState.update { if (it.informationClientId == clientId) it.copy(informationLoading = false, informationFailure = failure) else it }
            }
        }
    }

    fun saveClientInformation(clientId: String, birthDate: LocalDate?, weight: String, height: String, goal: String, injuries: String, limitations: String) {
        if (state.value.submitting) return
        fun number(text: String) = text.trim().replace(',', '.').toDoubleOrNull()
        val information = ClientInformation(birthDate, number(weight), number(height), goal.trim(), injuries.trim(), limitations.trim())
        if ((weight.isNotBlank() && information.weightKg == null) || (height.isNotBlank() && information.heightCm == null) || !information.isValidAsOf(DemoDate)) {
            mutableState.update { it.copy(actionFailure = Failure.InvalidClientInfo) }
            return
        }
        mutableState.update { it.copy(submitting = true, actionFailure = null, saved = false) }
        viewModelScope.launch {
            try {
                val saved = repository.saveClientInformation(clientId, information)
                mutableState.update { it.copy(clientInformation = saved, informationClientId = clientId, submitting = false, saved = true,
                    routes = it.routes.dropLast(1).ifEmpty { listOf(Route.Main) }) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(submitting = false, actionFailure = (e as? TrainerException)?.failure ?: Failure.Save) }
            }
        }
    }

    fun saveTime(date: LocalDate, start: String, end: String, clientId: String?, location: String, session: Session? = null, block: Boolean = false) {
        val zone = TimeZone.of("Europe/Warsaw")
        val range = runCatching {
            val from = LocalDateTime(date, LocalTime.parse(start)).toInstant(zone)
            val until = LocalDateTime(date, LocalTime.parse(end)).toInstant(zone)
            require(until > from)
            // Reject spring DST-gap times instead of silently shifting the entered hour.
            require(from.toLocalDateTime(zone).time == LocalTime.parse(start))
            require(until.toLocalDateTime(zone).time == LocalTime.parse(end))
            from to until
        }.getOrNull()
        if (range == null || (!block && session == null && (clientId == null || location.isBlank()))) {
            mutableState.update { it.copy(actionFailure = Failure.InvalidInput) }
            return
        }
        mutate(pop = !block) {
            when {
                block -> repository.addBlock(range.first, range.second)
                session != null -> repository.proposeTime(session.id, session.revision, RescheduleProposal(range.first, range.second, ProposalAuthor.Trainer))
                else -> repository.createSession(clientId!!, range.first, range.second, location)
            }
        }
    }
}
