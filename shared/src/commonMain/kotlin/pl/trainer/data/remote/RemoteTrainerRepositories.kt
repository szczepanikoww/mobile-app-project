package pl.trainer.data.remote

import com.trainingapp.contract.api.AcceptRequest
import com.trainingapp.contract.api.CalendarBlockDto
import com.trainingapp.contract.api.CheckInDto
import com.trainingapp.contract.api.ClientInformationDto
import com.trainingapp.contract.api.CreateBlockRequest
import com.trainingapp.contract.api.CreateSessionRequest
import com.trainingapp.contract.api.FeeWaiverDto
import com.trainingapp.contract.api.PolicyDto
import com.trainingapp.contract.api.PolicyEditorDto
import com.trainingapp.contract.api.ProposeTimeRequest
import com.trainingapp.contract.api.RequestReviewDto
import com.trainingapp.contract.api.RevisionRequest
import com.trainingapp.contract.api.SaveNoteRequest
import com.trainingapp.contract.api.SessionResultDto
import com.trainingapp.contract.api.TrainerDashboardDto
import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.PolicyRules
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import pl.trainer.domain.CheckInInfo
import pl.trainer.domain.ClientInformation
import pl.trainer.domain.Failure
import pl.trainer.domain.FeeWaiver
import pl.trainer.domain.PolicyRepository
import pl.trainer.domain.RequestReview
import pl.trainer.domain.RescheduleProposal
import pl.trainer.domain.TrainerDashboard
import pl.trainer.domain.TrainerException
import pl.trainer.domain.TrainerRepository

@OptIn(ExperimentalUuidApi::class)
private fun operationId() = Uuid.random().toString()

/**
 * The trainer calendar, requests and clients from the server (`/api/v1/trainer`). Each command sends the revision the
 * trainer saw; the answer is the fresh dashboard. Client information is read-only here: the client enters it (TB1).
 */
class RemoteTrainerRepository(private val api: TrainerApiClient) : TrainerRepository {
    override val canEditClientInformation: Boolean get() = false

    override suspend fun load(): TrainerDashboard = api.get<TrainerDashboardDto>("trainer/dashboard").toModel()

    private suspend fun sessionCommand(id: String, action: String, revision: Int): TrainerDashboard {
        api.send<RevisionRequest, SessionResultDto>(HttpMethod.Post, "trainer/sessions/$id/$action", RevisionRequest(revision, operationId()))
        return load()
    }

    override suspend fun acceptRequest(id: String, revision: Int) = acceptRequest(id, revision, waiver = null)

    override suspend fun acceptRequest(id: String, revision: Int, waiver: FeeWaiver?): TrainerDashboard {
        val body = AcceptRequest(revision, waiver?.let { FeeWaiverDto(it.reason) }, operationId())
        api.send<AcceptRequest, SessionResultDto>(HttpMethod.Post, "trainer/sessions/$id/accept", body)
        return load()
    }

    override suspend fun rejectRequest(id: String, revision: Int) = sessionCommand(id, "reject", revision)

    override suspend fun proposeTime(id: String, revision: Int, proposal: RescheduleProposal): TrainerDashboard {
        val body = ProposeTimeRequest(revision, proposal.start, proposal.end, operationId())
        api.send<ProposeTimeRequest, SessionResultDto>(HttpMethod.Post, "trainer/sessions/$id/propose", body)
        return load()
    }

    override suspend fun cancelSession(id: String, revision: Int) = sessionCommand(id, "cancel", revision)

    override suspend fun completeSession(id: String, revision: Int) = sessionCommand(id, "complete", revision)

    override suspend fun markNoShow(id: String, revision: Int) = sessionCommand(id, "no-show", revision)

    override suspend fun checkIn(sessionId: String): CheckInInfo = try {
        api.get<CheckInDto>("trainer/sessions/$sessionId/check-in").toModel()
    } catch (e: TrainerException) {
        // The check-in screen treats an unknown session as out of date.
        throw if (e.failure == Failure.Load) TrainerException(Failure.Conflict) else e
    }

    override suspend fun requestReview(id: String): RequestReview = api.get<RequestReviewDto>("trainer/sessions/$id/review").toModel()

    override suspend fun createSession(clientId: String, start: Instant, end: Instant, location: String): TrainerDashboard {
        if (location.isBlank()) throw TrainerException(Failure.InvalidInput)
        val body = CreateSessionRequest(clientId = clientId, start = start, end = end, location = location.trim(), operationId = operationId())
        api.send<CreateSessionRequest, SessionResultDto>(HttpMethod.Post, "trainer/sessions", body)
        return load()
    }

    override suspend fun addBlock(start: Instant, end: Instant): TrainerDashboard {
        api.send<CreateBlockRequest, CalendarBlockDto>(HttpMethod.Post, "trainer/blocks", CreateBlockRequest(start, end))
        return load()
    }

    override suspend fun removeBlock(id: String): TrainerDashboard {
        api.command(HttpMethod.Delete, "trainer/blocks/$id")
        return load()
    }

    override suspend fun saveNote(clientId: String, note: String): TrainerDashboard {
        api.command(HttpMethod.Put, "trainer/clients/$clientId/note", SaveNoteRequest(note))
        return load()
    }

    override suspend fun loadClientInformation(clientId: String): ClientInformation =
        api.get<ClientInformationDto>("trainer/clients/$clientId/information").toModel()

    /** The client enters this information; the trainer only reads it (TB1). */
    override suspend fun saveClientInformation(clientId: String, information: ClientInformation): ClientInformation =
        throw TrainerException(Failure.Denied)
}

/**
 * The signed-in trainer's cancellation policy versions from the server. Policies of other trainers (the client views,
 * which still use the demo server) go to [others].
 */
class RemotePolicyRepository(
    private val api: TrainerApiClient,
    private val isOwnTrainer: (String) -> Boolean,
    private val others: PolicyRepository,
) : PolicyRepository {
    private val saved = MutableStateFlow(0L)
    override val updates: StateFlow<Long> get() = saved

    private suspend fun editor() = api.get<PolicyEditorDto>("trainer/policy")

    override suspend fun current(trainerId: String): CancellationPolicy {
        if (!isOwnTrainer(trainerId)) return others.current(trainerId)
        return editor().current?.toModel() ?: throw TrainerException(Failure.Load)
    }

    override suspend fun version(versionId: String): CancellationPolicy {
        if (!versionId.isUuid()) return others.version(versionId)
        return api.get<PolicyDto>("trainer/policy/versions/$versionId").toModel()
    }

    override suspend fun sessionPrice(trainerId: String): Money {
        if (!isOwnTrainer(trainerId)) return others.sessionPrice(trainerId)
        return editor().sessionPrice ?: throw TrainerException(Failure.Load)
    }

    override suspend fun save(trainerId: String, rules: PolicyRules, basedOnVersionId: String): CancellationPolicy {
        if (!isOwnTrainer(trainerId)) return others.save(trainerId, rules, basedOnVersionId)
        // Settings the editor does not show (fee modes, the note for clients) carry over from the version it edited.
        val base = api.get<PolicyDto>("trainer/policy/versions/$basedOnVersionId")
        val body = rules.toSaveRequest(basedOnVersionId).copy(
            lateRescheduleIsCancel = base.lateRescheduleIsCancel,
            cashFeeMode = base.cashFeeMode,
            packageFeeMode = base.packageFeeMode,
            clientNote = base.clientNote,
        )
        return api.send<_, PolicyDto>(HttpMethod.Post, "trainer/policy", body).toModel().also { saved.update { it + 1 } }
    }
}

private val UuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private fun String.isUuid() = UuidPattern.matches(this)
