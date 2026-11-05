package com.trainingapp.feature.client.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trainingapp.domain.common.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import pl.trainer.domain.AvailableSlot
import pl.trainer.domain.BookingOptions
import pl.trainer.domain.BookingRequest
import pl.trainer.domain.CancellationQuote
import pl.trainer.domain.ClientSchedule
import pl.trainer.domain.ClientScheduleRepository
import pl.trainer.domain.ClientSession
import pl.trainer.domain.ClientSessionState
import pl.trainer.domain.Failure
import pl.trainer.domain.RescheduleFeePreview
import pl.trainer.domain.RescheduleRequest
import pl.trainer.domain.TrainerException
import com.trainingapp.contract.policy.CancellationPolicy
import kotlin.time.Duration.Companion.days

/** The zone sessions are shown in; each session also carries its own zone. */
val ClientZone: TimeZone = TimeZone.of("Europe/Warsaw")

enum class ScheduleMessage { RequestSent, RescheduleSent, ProposalAccepted, ProposalDeclined, RequestWithdrawn, SessionCancelled }

data class BookingSheetState(
    val trainerId: String?,
    val options: BookingOptions? = null,
    val loadingOptions: Boolean = false,
    val dates: List<LocalDate> = emptyList(),
    val date: LocalDate? = null,
    val slots: List<AvailableSlot> = emptyList(),
    val loadingSlots: Boolean = false,
    val slot: AvailableSlot? = null,
    val place: String? = null,
    /** Never pre-ticked: the client explicitly accepts the trainer's current policy version (architecture 3.2). */
    val policyAccepted: Boolean = false,
    val failure: Failure? = null,
) {
    val canSend: Boolean get() = options != null && slot != null && !place.isNullOrBlank() && policyAccepted
}

data class RescheduleSheetState(
    val session: ClientSession,
    val dates: List<LocalDate>,
    val date: LocalDate,
    val slots: List<AvailableSlot> = emptyList(),
    val loadingSlots: Boolean = true,
    val slot: AvailableSlot? = null,
    /** The late-reschedule fee the policy proposes; the trainer decides whether to charge it. */
    val fee: RescheduleFeePreview? = null,
    val failure: Failure? = null,
)

data class CancellationSheetState(
    val session: ClientSession,
    val quote: CancellationQuote? = null,
    val loadingQuote: Boolean = true,
    /** Display countdown of the quote's validity. The server decides on confirmation. */
    val secondsLeft: Int = 0,
    /** The session changed after the quote was made (another revision): a fresh quote is needed. */
    val stale: Boolean = false,
    val failure: Failure? = null,
) {
    val quoteUsable: Boolean get() = quote != null && !stale && secondsLeft > 0
}

data class ScheduleUiState(
    val loading: Boolean = true,
    val schedule: ClientSchedule? = null,
    val loadFailure: Failure? = null,
    val offline: Boolean = false,
    val selectedDate: LocalDate? = null,
    val submitting: Boolean = false,
    val actionFailure: Failure? = null,
    val message: ScheduleMessage? = null,
    val booking: BookingSheetState? = null,
    val reschedule: RescheduleSheetState? = null,
    val cancellation: CancellationSheetState? = null,
    /** A read-only policy version opened from the details or the booking sheet. */
    val policy: CancellationPolicy? = null,
) {
    /** "Today" by the server's clock, in the client's zone. */
    val today: LocalDate? get() = schedule?.serverNow?.toLocalDateTime(ClientZone)?.date
    val visibleDate: LocalDate? get() = selectedDate ?: today
    val week: List<LocalDate> get() = visibleDate?.let(::weekOf).orEmpty()

    fun session(id: String): ClientSession? = schedule?.sessions?.find { it.id == id }

    /** The next confirmed session that has not started yet, by server time. */
    val nextSession: ClientSession?
        get() = schedule?.let { data ->
            data.sessions.filter {
                (it.state == ClientSessionState.Confirmed || it.state == ClientSessionState.ProposalFromTrainer ||
                    it.state == ClientSessionState.RescheduleRequested) && it.session.start > data.serverNow
            }.minByOrNull { it.session.start }
        }

    /** Sessions waiting for the client's answer, and requests the trainer rejected or let expire (upcoming week). */
    val attention: List<ClientSession>
        get() = schedule?.let { data ->
            data.sessions.filter {
                it.state == ClientSessionState.ProposalFromTrainer ||
                    ((it.state == ClientSessionState.Rejected || it.state == ClientSessionState.Expired) &&
                        it.session.start > data.serverNow - 7.days)
            }.sortedBy { it.session.start }
        }.orEmpty()

    val hasTrainer: Boolean get() = schedule?.trainers?.isNotEmpty() == true
}

fun weekOf(date: LocalDate): List<LocalDate> {
    val monday = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return (0..6).map { monday.plus(it, DateTimeUnit.DAY) }
}

/** The next [count] working days from [from] (D9: Mon–Fri). */
fun workingDays(from: LocalDate, count: Int = 10): List<LocalDate> =
    generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }
        .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        .take(count).toList()

/**
 * The client's sessions and requests (B1 and B2): the calendar, the session details and the booking, reschedule
 * and cancellation sheets. Every change goes to the server first; nothing is shown as done before it answers.
 */
class ClientScheduleViewModel(
    private val repository: ClientScheduleRepository,
    network: NetworkMonitor,
    /** How often the quote countdown ticks; tests shorten it. */
    private val tickMillis: Long = 1_000,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ScheduleUiState(offline = !network.isOnline.value))
    val state: StateFlow<ScheduleUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var slotsJob: Job? = null

    init {
        viewModelScope.launch { network.isOnline.collect { online -> mutableState.update { it.copy(offline = !online) } } }
        // Changes made by the trainer (or another screen) refresh the data without a loading state.
        viewModelScope.launch { repository.updates.drop(1).collect { refresh(silent = true) } }
        refresh(silent = false)
    }

    fun reload() = refresh(silent = false)

    private fun refresh(silent: Boolean) {
        loadJob?.cancel()
        if (!silent) mutableState.update { it.copy(loading = it.schedule == null, loadFailure = null) }
        loadJob = viewModelScope.launch {
            try {
                val schedule = repository.load()
                mutableState.update { current ->
                    val cancellation = current.cancellation?.let { sheet ->
                        val latest = schedule.sessions.find { it.id == sheet.session.id }
                        val quote = sheet.quote
                        if (quote != null && latest != null && latest.revision != quote.revision) sheet.copy(stale = true, session = latest) else sheet
                    }
                    current.copy(schedule = schedule, loading = false, loadFailure = null, cancellation = cancellation)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the last data: an error only replaces an empty screen.
                mutableState.update { it.copy(loading = false, loadFailure = if (it.schedule == null) e.failure() else null) }
            }
        }
    }

    fun selectDate(date: LocalDate) = mutableState.update { it.copy(selectedDate = date) }

    fun shiftWeek(weeks: Int) = mutableState.update { state ->
        state.visibleDate?.let { state.copy(selectedDate = it.plus(weeks * 7, DateTimeUnit.DAY)) } ?: state
    }

    fun showToday() = mutableState.update { it.copy(selectedDate = null) }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun clearActionFailure() = mutableState.update { it.copy(actionFailure = null) }

    /** Runs one server change at a time; a second tap while it runs is ignored. */
    private fun submit(onFailure: (Failure) -> Unit = { failure -> mutableState.update { it.copy(actionFailure = failure) } }, action: suspend () -> Unit) {
        if (state.value.submitting) return
        mutableState.update { it.copy(submitting = true, actionFailure = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e.failure())
            } finally {
                mutableState.update { it.copy(submitting = false) }
            }
        }
    }

    fun answerProposal(session: ClientSession, accept: Boolean) = submit {
        repository.answerProposal(session.id, session.revision, accept)
        mutableState.update { it.copy(message = if (accept) ScheduleMessage.ProposalAccepted else ScheduleMessage.ProposalDeclined) }
        refresh(silent = true)
    }

    fun withdraw(session: ClientSession) = submit {
        repository.withdrawRequest(session.id, session.revision)
        mutableState.update { it.copy(message = ScheduleMessage.RequestWithdrawn) }
        refresh(silent = true)
    }

    /* Policy (read-only) */

    fun showPolicy(versionId: String) {
        viewModelScope.launch {
            try {
                val policy = repository.policy(versionId)
                mutableState.update { it.copy(policy = policy) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(actionFailure = e.failure()) }
            }
        }
    }

    fun showPolicy(policy: CancellationPolicy) = mutableState.update { it.copy(policy = policy) }

    fun closePolicy() = mutableState.update { it.copy(policy = null) }

    /* Booking request */

    /** Opens the sheet for [trainerId] (e.g. from a guest's pending intent) or the client's only/first trainer. */
    fun openBooking(trainerId: String? = null) {
        val schedule = state.value.schedule
        val id = trainerId ?: schedule?.trainers?.firstOrNull()?.id
        val today = state.value.today
        val dates = today?.let { workingDays(it) }.orEmpty()
        mutableState.update { it.copy(booking = BookingSheetState(id, loadingOptions = id != null, dates = dates, date = dates.firstOrNull()), actionFailure = null) }
        if (id != null) loadBookingOptions(id)
    }

    fun bookingTrainer(trainerId: String) {
        mutableState.update { state -> state.copy(booking = state.booking?.copy(trainerId = trainerId, options = null, loadingOptions = true, slot = null, place = null, policyAccepted = false)) }
        loadBookingOptions(trainerId)
    }

    private fun loadBookingOptions(trainerId: String) {
        viewModelScope.launch {
            try {
                val options = repository.bookingOptions(trainerId)
                mutableState.update { state ->
                    val sheet = state.booking?.takeIf { it.trainerId == trainerId } ?: return@update state
                    state.copy(booking = sheet.copy(options = options, loadingOptions = false, place = options.places.firstOrNull(), failure = null))
                }
                state.value.booking?.date?.let { loadBookingSlots(trainerId, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { state -> state.copy(booking = state.booking?.copy(loadingOptions = false, failure = e.failure())) }
            }
        }
    }

    fun bookingDate(date: LocalDate) {
        val sheet = state.value.booking ?: return
        mutableState.update { it.copy(booking = sheet.copy(date = date, slot = null)) }
        sheet.trainerId?.let { loadBookingSlots(it, date) }
    }

    private fun loadBookingSlots(trainerId: String, date: LocalDate) {
        slotsJob?.cancel()
        mutableState.update { state -> state.copy(booking = state.booking?.copy(loadingSlots = true, slots = emptyList())) }
        slotsJob = viewModelScope.launch {
            try {
                val slots = repository.availableSlots(trainerId, date)
                mutableState.update { state -> state.copy(booking = state.booking?.takeIf { it.date == date }?.copy(slots = slots, loadingSlots = false) ?: state.booking) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { state -> state.copy(booking = state.booking?.copy(loadingSlots = false, failure = e.failure())) }
            }
        }
    }

    fun bookingSlot(slot: AvailableSlot) = mutableState.update { it.copy(booking = it.booking?.copy(slot = slot, failure = null)) }

    fun bookingPlace(place: String) = mutableState.update { it.copy(booking = it.booking?.copy(place = place)) }

    fun bookingPolicyAccepted(accepted: Boolean) = mutableState.update { it.copy(booking = it.booking?.copy(policyAccepted = accepted)) }

    fun sendBooking() {
        val sheet = state.value.booking ?: return
        val options = sheet.options ?: return
        val slot = sheet.slot ?: return
        val place = sheet.place ?: return
        if (!sheet.canSend) return
        submit(onFailure = { failure ->
            // The sheet keeps every choice, so the client can retry or pick another slot.
            mutableState.update { it.copy(booking = it.booking?.copy(failure = failure)) }
            if (failure == Failure.Conflict) {
                // Another booking took the slot or the policy changed: reload both.
                loadBookingOptions(options.trainer.id)
            }
        }) {
            repository.requestBooking(BookingRequest(options.trainer.id, slot.start, slot.end, place, options.policy.versionId))
            mutableState.update { it.copy(booking = null, message = ScheduleMessage.RequestSent) }
            refresh(silent = true)
        }
    }

    fun closeBooking() {
        if (state.value.submitting) return
        slotsJob?.cancel()
        mutableState.update { it.copy(booking = null) }
    }

    /* Reschedule request */

    fun openReschedule(session: ClientSession) {
        val today = state.value.today ?: return
        val dates = workingDays(today)
        mutableState.update { it.copy(reschedule = RescheduleSheetState(session, dates, dates.first()), actionFailure = null) }
        loadRescheduleSlots(dates.first())
        viewModelScope.launch {
            val fee = runCatching { repository.previewRescheduleFee(session.id, session.revision) }.getOrNull()
            mutableState.update { state -> state.copy(reschedule = state.reschedule?.copy(fee = fee)) }
        }
    }

    fun rescheduleDate(date: LocalDate) {
        mutableState.update { it.copy(reschedule = it.reschedule?.copy(date = date, slot = null)) }
        loadRescheduleSlots(date)
    }

    private fun loadRescheduleSlots(date: LocalDate) {
        val sheet = state.value.reschedule ?: return
        slotsJob?.cancel()
        mutableState.update { it.copy(reschedule = it.reschedule?.copy(loadingSlots = true, slots = emptyList())) }
        slotsJob = viewModelScope.launch {
            try {
                val slots = repository.availableSlots(sheet.session.trainer.id, date, excludeSessionId = sheet.session.id)
                mutableState.update { state -> state.copy(reschedule = state.reschedule?.takeIf { it.date == date }?.copy(slots = slots, loadingSlots = false) ?: state.reschedule) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { state -> state.copy(reschedule = state.reschedule?.copy(loadingSlots = false, failure = e.failure())) }
            }
        }
    }

    fun rescheduleSlot(slot: AvailableSlot) = mutableState.update { it.copy(reschedule = it.reschedule?.copy(slot = slot, failure = null)) }

    fun sendReschedule() {
        val sheet = state.value.reschedule ?: return
        val slot = sheet.slot ?: return
        submit(onFailure = { failure -> mutableState.update { it.copy(reschedule = it.reschedule?.copy(failure = failure)) } }) {
            repository.requestReschedule(RescheduleRequest(sheet.session.id, sheet.session.revision, slot.start, slot.end))
            mutableState.update { it.copy(reschedule = null, message = ScheduleMessage.RescheduleSent) }
            refresh(silent = true)
        }
    }

    fun closeReschedule() {
        if (state.value.submitting) return
        slotsJob?.cancel()
        mutableState.update { it.copy(reschedule = null) }
    }

    /* Cancellation with a server quote */

    fun openCancellation(session: ClientSession) {
        mutableState.update { it.copy(cancellation = CancellationSheetState(session), actionFailure = null) }
        requestQuote(session)
    }

    /** Asks the server for a fresh quote, e.g. after the previous one expired or the session changed. */
    fun refreshQuote() {
        val sheet = state.value.cancellation ?: return
        val latest = state.value.session(sheet.session.id) ?: sheet.session
        mutableState.update { it.copy(cancellation = CancellationSheetState(latest)) }
        requestQuote(latest)
    }

    private fun requestQuote(session: ClientSession) {
        tickJob?.cancel()
        viewModelScope.launch {
            try {
                val quote = repository.quoteCancellation(session.id, session.revision)
                val seconds = (quote.expiresAt - quote.quotedAt).inWholeSeconds.toInt()
                mutableState.update { state ->
                    state.copy(cancellation = state.cancellation?.copy(quote = quote, loadingQuote = false, secondsLeft = seconds, stale = false, failure = null))
                }
                startCountdown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { state -> state.copy(cancellation = state.cancellation?.copy(loadingQuote = false, failure = e.failure())) }
            }
        }
    }

    private fun startCountdown() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while ((state.value.cancellation?.secondsLeft ?: 0) > 0) {
                delay(tickMillis)
                mutableState.update { state -> state.copy(cancellation = state.cancellation?.let { it.copy(secondsLeft = (it.secondsLeft - 1).coerceAtLeast(0)) }) }
            }
        }
    }

    fun confirmCancellation() {
        val sheet = state.value.cancellation ?: return
        val quote = sheet.quote ?: return
        if (!sheet.quoteUsable) return
        submit(onFailure = { failure ->
            mutableState.update { state ->
                state.copy(cancellation = state.cancellation?.let { sheet ->
                    // An expired or outdated quote is never confirmed: the sheet asks for a fresh one.
                    sheet.copy(failure = failure, stale = sheet.stale || failure == Failure.QuoteExpired || failure == Failure.Conflict)
                })
            }
        }) {
            repository.cancel(quote.sessionId, quote.revision, quote.quoteId)
            tickJob?.cancel()
            mutableState.update { it.copy(cancellation = null, message = ScheduleMessage.SessionCancelled) }
            refresh(silent = true)
        }
    }

    fun closeCancellation() {
        if (state.value.submitting) return
        tickJob?.cancel()
        mutableState.update { it.copy(cancellation = null) }
    }
}

internal fun Exception.failure(): Failure = (this as? TrainerException)?.failure ?: Failure.Load
