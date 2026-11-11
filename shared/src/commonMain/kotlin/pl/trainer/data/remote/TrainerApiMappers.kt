package pl.trainer.data.remote

import com.trainingapp.contract.api.CalendarBlockDto
import com.trainingapp.contract.api.CheckInDto
import com.trainingapp.contract.api.ClientInformationDto
import com.trainingapp.contract.api.ClientSummaryDto
import com.trainingapp.contract.api.FeeQuoteDto
import com.trainingapp.contract.api.PolicyDto
import com.trainingapp.contract.api.PolicyTierDto
import com.trainingapp.contract.api.ProposerRole
import com.trainingapp.contract.api.RelationshipStatus
import com.trainingapp.contract.api.RequestReviewDto
import com.trainingapp.contract.api.SavePolicyRequest
import com.trainingapp.contract.api.SessionDto
import com.trainingapp.contract.api.TrainerDashboardDto
import com.trainingapp.contract.api.PaymentKind as ApiPaymentKind
import com.trainingapp.contract.api.RequestKind as ApiRequestKind
import com.trainingapp.contract.api.SessionStatus as ApiSessionStatus
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.FeeQuote
import com.trainingapp.contract.policy.PolicyRules
import com.trainingapp.contract.policy.PolicyTier
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import pl.trainer.domain.AvailabilityBlock
import pl.trainer.domain.CheckInInfo
import pl.trainer.domain.Client
import pl.trainer.domain.ClientInformation
import pl.trainer.domain.PaymentKind
import pl.trainer.domain.ProposalAuthor
import pl.trainer.domain.RequestKind
import pl.trainer.domain.RequestReview
import pl.trainer.domain.RescheduleProposal
import pl.trainer.domain.Session
import pl.trainer.domain.SessionPayment
import pl.trainer.domain.SessionStatus
import pl.trainer.domain.TrainerDashboard

/* Wire DTOs (`:contract`) to the app's trainer models. The server's enum names equal the database values. */

fun ApiSessionStatus.toModel(): SessionStatus = when (this) {
    ApiSessionStatus.REQUESTED -> SessionStatus.Requested
    ApiSessionStatus.CONFIRMED -> SessionStatus.Confirmed
    ApiSessionStatus.REJECTED -> SessionStatus.Rejected
    ApiSessionStatus.EXPIRED -> SessionStatus.Expired
    ApiSessionStatus.COMPLETED -> SessionStatus.Completed
    ApiSessionStatus.NO_SHOW -> SessionStatus.NoShow
    ApiSessionStatus.CANCELLED_BY_CLIENT -> SessionStatus.CancelledByClient
    ApiSessionStatus.CANCELLED_BY_TRAINER -> SessionStatus.CancelledByTrainer
}

fun ApiPaymentKind.toModel(): PaymentKind = when (this) {
    ApiPaymentKind.ONLINE -> PaymentKind.Online
    ApiPaymentKind.CASH -> PaymentKind.Cash
    ApiPaymentKind.PACKAGE -> PaymentKind.Package
}

/** The repository is scoped to the signed-in trainer, so [Session.trainerId] stays null ("own"). */
fun SessionDto.toModel(): Session = Session(
    id = id,
    clientId = clientId,
    start = start,
    end = end,
    timeZone = timeZone,
    location = location,
    status = status.toModel(),
    revision = revision,
    proposal = proposal?.let {
        RescheduleProposal(
            start = it.start,
            end = it.end,
            author = if (it.author == ProposerRole.CLIENT) ProposalAuthor.Client else ProposalAuthor.Trainer,
            requestedAt = it.requestedAt,
        )
    },
    planTitle = planTitle,
    payment = SessionPayment(payment.kind.toModel(), payment.packageUsed, payment.packageTotal),
    policyVersionId = policyVersionId,
    price = price,
    requestedAt = requestedAt,
)

fun ClientSummaryDto.toModel(): Client = Client(
    id = id,
    name = name,
    initials = initials,
    active = status == RelationshipStatus.ACTIVE,
    hasOverduePayment = hasOverduePayment,
)

fun CalendarBlockDto.toModel(): AvailabilityBlock = AvailabilityBlock(id, start, end)

fun TrainerDashboardDto.toModel(): TrainerDashboard = TrainerDashboard(
    clients = clients.map { it.toModel() },
    sessions = sessions.map { it.toModel() },
    blocks = blocks.map { it.toModel() },
    notes = notes,
    today = serverNow.toLocalDateTime(TimeZone.of("Europe/Warsaw")).date,
)

fun PolicyDto.toModel(): CancellationPolicy = CancellationPolicy(
    versionId = versionId,
    trainerId = trainerId,
    version = version,
    createdAt = createdAt,
    tiers = tiers.map { PolicyTier(it.hoursBefore, it.feePercent) },
    noShowPercent = noShowPercent,
    graceMinutes = graceMinutes,
    freeLateCancellationsPerMonth = freeLateCancellationsPerMonth,
)

/** The editor's rules as a new version request. Fields the editor does not show keep the server defaults. */
fun PolicyRules.toSaveRequest(basedOnVersionId: String) = SavePolicyRequest(
    basedOnVersionId = basedOnVersionId,
    tiers = sortedTiers.map { PolicyTierDto(it.hoursBefore, it.feePercent) },
    noShowPercent = noShowPercent,
    graceMinutes = graceMinutes,
    freeLateCancellationsPerMonth = freeLateCancellationsPerMonth,
)

fun FeeQuoteDto.toModel(): FeeQuote = FeeQuote(
    tier = tierHoursBefore?.let { PolicyTier(it, policyPercent) },
    policyPercent = policyPercent,
    amount = amount,
    usesFreeCancellation = usesFreeCancellation,
)

fun CheckInDto.toModel(): CheckInInfo = CheckInInfo(
    session = session.toModel(),
    client = client.toModel(),
    started = started,
    noShowAvailableAt = noShowAvailableAt,
    noShowAllowed = noShowAllowed,
    graceMinutes = graceMinutes,
)

fun RequestReviewDto.toModel(): RequestReview = RequestReview(
    session = session.toModel(),
    client = client.toModel(),
    kind = if (kind == ApiRequestKind.RESCHEDULE) RequestKind.Reschedule else RequestKind.Booking,
    requestedAt = requestedAt,
    requestedStart = requestedStart,
    requestedEnd = requestedEnd,
    slotAvailable = slotAvailable,
    policy = policy.toModel(),
    lateFee = lateFee?.toModel(),
    serverNow = serverNow,
)

fun ClientInformationDto.toModel(): ClientInformation = ClientInformation(
    birthDate = birthDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    weightKg = weightKg,
    heightCm = heightCm,
    trainingGoal = trainingGoal,
    injuries = injuries,
    movementLimitations = movementLimitations,
)
