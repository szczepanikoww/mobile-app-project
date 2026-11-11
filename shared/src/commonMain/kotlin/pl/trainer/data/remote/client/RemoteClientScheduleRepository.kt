package pl.trainer.data.remote.client

import com.trainingapp.contract.schedule.BookingOptionsDto
import com.trainingapp.contract.schedule.BookingRequestDto
import com.trainingapp.contract.schedule.CancelRequestDto
import com.trainingapp.contract.schedule.CancellationQuoteDto
import com.trainingapp.contract.schedule.ClientScheduleDto
import com.trainingapp.contract.schedule.ProposalAnswerDto
import com.trainingapp.contract.schedule.RescheduleFeePreviewDto
import com.trainingapp.contract.schedule.RescheduleRequestDto
import com.trainingapp.contract.schedule.RevisionDto
import com.trainingapp.contract.schedule.SessionDto
import com.trainingapp.contract.schedule.SlotDto
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import pl.trainer.domain.AvailableSlot
import pl.trainer.domain.BookingOptions
import pl.trainer.domain.BookingRequest
import pl.trainer.domain.CancellationQuote
import pl.trainer.domain.ClientSchedule
import pl.trainer.domain.ClientScheduleRepository
import pl.trainer.domain.ClientSession
import pl.trainer.domain.Failure
import pl.trainer.domain.RescheduleFeePreview
import pl.trainer.domain.RescheduleRequest
import pl.trainer.domain.TrainerException
import pl.trainer.domain.TrainerRef
import com.trainingapp.contract.policy.CancellationPolicy

/**
 * The signed-in client's sessions and booking flows from the server (`/v1/client/...`).
 *
 * - [identity] names the signed-in account (e.g. the auth subject); when it changes, cached data is dropped so one
 *   account never sees another's schedule.
 * - [updates] is bumped after every successful change. Pass the same flow to [RemoteWalletRepository] so a
 *   cancellation fee shows up in the wallet.
 * - Reads fall back to the last loaded data when the server cannot be reached ([lastLoadWasCached] says so); with no
 *   cache they fail with [Failure.Offline]. Changes are never queued or applied offline: they fail with
 *   [Failure.Offline] and the screen keeps the input.
 * - A booking or reschedule retried with the same content reuses its operation id, so a lost answer cannot create
 *   the request twice.
 */
class RemoteClientScheduleRepository(
    private val api: ClientApi,
    private val identity: () -> String? = { null },
    private val updated: MutableStateFlow<Long> = MutableStateFlow(0L),
) : ClientScheduleRepository {
    override val updates: StateFlow<Long> get() = updated

    /** True when the last [load] returned data from memory because the server was unreachable. */
    var lastLoadWasCached: Boolean = false
        private set

    private val lock = Mutex()
    private var cacheOwner: String? = null
    private var schedule: ClientSchedule? = null
    private val options = mutableMapOf<String, BookingOptions>()
    private val policies = mutableMapOf<String, CancellationPolicy>()
    private val operations = mutableMapOf<String, String>()

    private fun owner(): String? = identity().also { who ->
        if (who != cacheOwner) {
            cacheOwner = who
            schedule = null
            options.clear()
            policies.clear()
            operations.clear()
        }
    }

    private suspend fun <T> reading(block: suspend () -> T): T =
        try { block() } catch (e: Throwable) { throw e.toTrainerException(Op.Read) }

    private suspend fun <T> writing(op: Op = Op.Write, block: suspend () -> T): T {
        val result = try { block() } catch (e: Throwable) { throw e.toTrainerException(op) }
        updated.update { it + 1 }
        return result
    }

    private suspend fun <T> operation(key: String, block: suspend (operationId: String) -> T): T {
        val id = lock.withLock { operations.getOrPut(key) { newOperationId() } }
        val result = block(id)
        lock.withLock { operations.remove(key) }
        return result
    }

    override suspend fun load(): ClientSchedule {
        lock.withLock { owner() }
        return try {
            val loaded = api.get("v1/client/schedule", ClientScheduleDto.serializer()).toModel()
            lock.withLock { schedule = loaded }
            lastLoadWasCached = false
            loaded
        } catch (e: ClientApiOfflineException) {
            lastLoadWasCached = true
            lock.withLock { schedule } ?: throw TrainerException(Failure.Offline)
        } catch (e: Throwable) {
            throw e.toTrainerException(Op.Read)
        }
    }

    override suspend fun bookingOptions(trainerId: String): BookingOptions {
        lock.withLock { owner() }
        return try {
            val loaded = api.get("v1/client/trainers/$trainerId/booking-options", BookingOptionsDto.serializer()).toModel()
            lock.withLock { options[trainerId] = loaded; policies[loaded.policy.versionId] = loaded.policy }
            loaded
        } catch (e: ClientApiOfflineException) {
            lock.withLock { options[trainerId] } ?: throw TrainerException(Failure.Offline)
        } catch (e: Throwable) {
            throw e.toTrainerException(Op.Read)
        }
    }

    /** Slots are time-critical, so they are never served from memory: offline fails with [Failure.Offline]. */
    override suspend fun availableSlots(trainerId: String, date: LocalDate, excludeSessionId: String?): List<AvailableSlot> = reading {
        val query = buildList {
            add("date" to date.toString())
            excludeSessionId?.let { add("excludeSessionId" to it) }
        }
        api.get("v1/client/trainers/$trainerId/slots", ListSerializer(SlotDto.serializer()), query).map { it.toModel() }
    }

    /** The answer is a booking *request*: the session stays [pl.trainer.domain.ClientSessionState.AwaitingTrainer]. */
    override suspend fun requestBooking(request: BookingRequest): ClientSession = writing {
        val key = "book|${request.trainerId}|${request.start}|${request.end}|${request.place}|${request.acceptedPolicyVersionId}"
        operation(key) { operationId ->
            val body = BookingRequestDto(
                trainerId = request.trainerId,
                start = request.start,
                end = request.end,
                place = request.place,
                acceptedPolicyVersionId = request.acceptedPolicyVersionId,
                operationId = operationId,
            )
            post("v1/client/booking-requests", body, BookingRequestDto.serializer(), operationId)
        }
    }

    override suspend fun withdrawRequest(sessionId: String, revision: Int): ClientSession = writing {
        post("v1/client/sessions/$sessionId/withdraw", RevisionDto(revision), RevisionDto.serializer(), null)
    }

    /** Null when the change is not late (the server answers `204`). Nothing is charged by asking. */
    override suspend fun previewRescheduleFee(sessionId: String, revision: Int): RescheduleFeePreview? = reading {
        api.send<Unit, RescheduleFeePreviewDto>(
            HttpMethod.Get,
            "v1/client/sessions/$sessionId/reschedule-fee?revision=$revision",
            response = RescheduleFeePreviewDto.serializer(),
        )?.toModel()
    }

    override suspend fun requestReschedule(request: RescheduleRequest): ClientSession = writing {
        val key = "reschedule|${request.sessionId}|${request.revision}|${request.start}|${request.end}"
        operation(key) { operationId ->
            val body = RescheduleRequestDto(request.revision, request.start, request.end, operationId)
            post("v1/client/sessions/${request.sessionId}/reschedule-requests", body, RescheduleRequestDto.serializer(), operationId)
        }
    }

    /** The session moves only when the server confirms the new slot is still free (`SLOT_TAKEN` -> [Failure.Conflict]). */
    override suspend fun answerProposal(sessionId: String, revision: Int, accept: Boolean): ClientSession = writing {
        post("v1/client/sessions/$sessionId/proposal/answer", ProposalAnswerDto(revision, accept), ProposalAnswerDto.serializer(), null)
    }

    override suspend fun quoteCancellation(sessionId: String, revision: Int): CancellationQuote = try {
        // A quote is a server answer: it changes nothing and needs a connection (offline -> Failure.Offline).
        api.send<RevisionDto, CancellationQuoteDto>(
            HttpMethod.Post,
            "v1/client/sessions/$sessionId/cancellation-quotes",
            RevisionDto(revision),
            RevisionDto.serializer(),
            CancellationQuoteDto.serializer(),
        )?.toModel() ?: throw TrainerException(Failure.Save)
    } catch (e: Throwable) {
        throw e.toTrainerException(Op.Write)
    }

    /** Confirms with the quote the user saw. The session is cancelled only when the server answers. */
    override suspend fun cancel(sessionId: String, revision: Int, quoteId: String): ClientSession = writing(Op.Cancel) {
        val operationId = operationIdFor("cancel:$quoteId")
        post("v1/client/sessions/$sessionId/cancel", CancelRequestDto(revision, quoteId, operationId), CancelRequestDto.serializer(), operationId)
    }

    override suspend fun policy(versionId: String): CancellationPolicy {
        lock.withLock { owner(); policies[versionId] }?.let { return it }
        return try {
            api.get("v1/policies/$versionId", com.trainingapp.contract.schedule.PolicyVersionDto.serializer()).toModel()
                .also { loaded -> lock.withLock { policies[versionId] = loaded } }
        } catch (e: Throwable) {
            throw e.toTrainerException(Op.Read)
        }
    }

    private suspend fun <B> post(path: String, body: B, serializer: kotlinx.serialization.KSerializer<B>, operationId: String?): ClientSession {
        val dto = api.send(HttpMethod.Post, path, body, serializer, SessionDto.serializer(), operationId)
            ?: throw TrainerException(Failure.Save)
        val view = dto.toClientSession(trainersById(dto.trainerId))
        // Keep the cached agenda current so an offline reopen shows the change the server confirmed.
        lock.withLock {
            schedule = schedule?.let { current ->
                val others = current.sessions.filter { it.id != view.id }
                current.copy(sessions = others + view, trainers = (current.trainers + view.trainer).distinctBy { it.id })
            }
        }
        return view
    }

    /** The trainer's name and initials: known from the schedule or booking options, otherwise from a fresh schedule. */
    private suspend fun trainersById(trainerId: String): Map<String, TrainerRef> {
        val known = lock.withLock {
            buildMap {
                options.values.forEach { put(it.trainer.id, it.trainer) }
                schedule?.trainers?.forEach { put(it.id, it) }
            }
        }
        if (trainerId in known) return known
        return try {
            api.get("v1/client/schedule", ClientScheduleDto.serializer()).trainers.associate { it.id to it.toModel() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            known
        }
    }
}
