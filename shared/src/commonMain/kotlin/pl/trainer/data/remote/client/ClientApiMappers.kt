package pl.trainer.data.remote.client

import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.FeeQuote
import com.trainingapp.contract.policy.PolicyTier
import com.trainingapp.contract.schedule.BookingOptionsDto
import com.trainingapp.contract.schedule.CancellationQuoteDto
import com.trainingapp.contract.schedule.ChargeKindDto
import com.trainingapp.contract.schedule.ClientScheduleDto
import com.trainingapp.contract.schedule.FeeDto
import com.trainingapp.contract.schedule.PaymentKindDto
import com.trainingapp.contract.schedule.PolicyVersionDto
import com.trainingapp.contract.schedule.ProposalAuthorDto
import com.trainingapp.contract.schedule.RescheduleFeePreviewDto
import com.trainingapp.contract.schedule.SessionDto
import com.trainingapp.contract.schedule.SlotDto
import com.trainingapp.contract.schedule.TrainerRefDto
import com.trainingapp.contract.wallet.ChargeDto
import com.trainingapp.contract.wallet.ChargeReasonDto
import com.trainingapp.contract.wallet.ChargeStateDto
import com.trainingapp.contract.wallet.PaymentDto
import com.trainingapp.contract.wallet.PaymentMethodDto
import com.trainingapp.contract.wallet.PaymentStateDto
import com.trainingapp.contract.wallet.SessionPackageDto
import com.trainingapp.contract.wallet.WalletDto
import kotlinx.datetime.LocalDate
import pl.trainer.domain.AvailableSlot
import pl.trainer.domain.BookingOptions
import pl.trainer.domain.CancellationQuote
import pl.trainer.domain.Charge
import pl.trainer.domain.ChargeKind
import pl.trainer.domain.ChargeReason
import pl.trainer.domain.ChargeState
import pl.trainer.domain.ClientSchedule
import pl.trainer.domain.ClientSession
import pl.trainer.domain.Payment
import pl.trainer.domain.PaymentKind
import pl.trainer.domain.PaymentMethod
import pl.trainer.domain.PaymentState
import pl.trainer.domain.ProposalAuthor
import pl.trainer.domain.RescheduleFeePreview
import pl.trainer.domain.RescheduleProposal
import pl.trainer.domain.Session
import pl.trainer.domain.SessionPackage
import pl.trainer.domain.SessionPayment
import pl.trainer.domain.TrainerRef
import pl.trainer.domain.Wallet
import com.trainingapp.contract.schedule.SessionStatus as ApiSessionStatus
import pl.trainer.domain.SessionStatus as AppSessionStatus

/* Wire DTOs (`:contract`) to the client models in pl.trainer.domain. Enum names equal the database values. */

internal fun MoneyDto.toMoney() = Money(minor, currency)
internal fun TrainerRefDto.toModel() = TrainerRef(id, name, initials)

internal fun ApiSessionStatus.toModel(): AppSessionStatus = when (this) {
    ApiSessionStatus.REQUESTED -> AppSessionStatus.Requested
    ApiSessionStatus.CONFIRMED -> AppSessionStatus.Confirmed
    ApiSessionStatus.REJECTED -> AppSessionStatus.Rejected
    ApiSessionStatus.EXPIRED -> AppSessionStatus.Expired
    ApiSessionStatus.COMPLETED -> AppSessionStatus.Completed
    ApiSessionStatus.NO_SHOW -> AppSessionStatus.NoShow
    ApiSessionStatus.CANCELLED_BY_CLIENT -> AppSessionStatus.CancelledByClient
    ApiSessionStatus.CANCELLED_BY_TRAINER -> AppSessionStatus.CancelledByTrainer
}

internal fun PaymentKindDto.toModel() = when (this) {
    PaymentKindDto.ONLINE -> PaymentKind.Online
    PaymentKindDto.CASH -> PaymentKind.Cash
    PaymentKindDto.PACKAGE -> PaymentKind.Package
}

internal fun ChargeKindDto.toModel() = when (this) {
    ChargeKindDto.ONLINE -> ChargeKind.Online
    ChargeKindDto.CASH -> ChargeKind.Cash
    ChargeKindDto.PACKAGE_CREDIT -> ChargeKind.PackageCredit
}

/** The plan title is not part of the client's session DTO, so [Session.planTitle] stays null. */
internal fun SessionDto.toModel() = Session(
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
            author = if (it.author == ProposalAuthorDto.TRAINER) ProposalAuthor.Trainer else ProposalAuthor.Client,
            requestedAt = it.requestedAt,
        )
    },
    payment = SessionPayment(payment.kind.toModel(), payment.packageUsed, payment.packageTotal),
    policyVersionId = policyVersionId,
    price = price.toMoney(),
    trainerId = trainerId,
    requestedAt = requestedAt,
)

/** When the trainer list lacks the session's trainer, the id is kept and the name is empty. */
internal fun SessionDto.toClientSession(trainers: Map<String, TrainerRef>) =
    ClientSession(toModel(), trainers[trainerId] ?: TrainerRef(trainerId, "", "?"))

internal fun ClientScheduleDto.toModel(): ClientSchedule {
    val refs = trainers.map { it.toModel() }
    val byId = refs.associateBy { it.id }
    return ClientSchedule(refs, sessions.map { it.toClientSession(byId) }, serverNow)
}

internal fun PolicyVersionDto.toModel() = CancellationPolicy(
    versionId = versionId,
    trainerId = trainerId,
    version = version,
    createdAt = createdAt,
    tiers = tiers.map { PolicyTier(it.hoursBefore, it.feePercent) },
    noShowPercent = noShowPercent,
    graceMinutes = graceMinutes,
    freeLateCancellationsPerMonth = freeLateCancellationsPerMonth,
)

internal fun BookingOptionsDto.toModel() = BookingOptions(trainer.toModel(), policy.toModel(), price.toMoney(), places, timeZone)

internal fun SlotDto.toModel() = AvailableSlot(start, end)

internal fun FeeDto.toModel() = FeeQuote(
    tier = tierHoursBefore?.let { PolicyTier(it, policyPercent) },
    policyPercent = policyPercent,
    amount = amount.toMoney(),
    usesFreeCancellation = usesFreeCancellation,
)

internal fun RescheduleFeePreviewDto.toModel() = RescheduleFeePreview(fee.toModel(), policyVersionId)

internal fun CancellationQuoteDto.toModel() = CancellationQuote(
    quoteId = quoteId,
    sessionId = sessionId,
    revision = revision,
    fee = fee.toModel(),
    policyVersionId = policyVersionId,
    freeLateCancellationsLeft = freeLateCancellationsLeft,
    chargeKind = chargeKind.toModel(),
    quotedAt = quotedAt,
    expiresAt = expiresAt,
)

/* Wallet */

internal fun ChargeReasonDto.toModel() = when (this) {
    ChargeReasonDto.LATE_CANCELLATION -> ChargeReason.LateCancellation
    ChargeReasonDto.LATE_RESCHEDULE -> ChargeReason.LateReschedule
    ChargeReasonDto.NO_SHOW -> ChargeReason.NoShow
    ChargeReasonDto.SESSION -> ChargeReason.Session
    ChargeReasonDto.PACKAGE_PERIOD -> ChargeReason.PackagePeriod
}

internal fun ChargeStateDto.toModel() = when (this) {
    ChargeStateDto.UNPAID -> ChargeState.Unpaid
    ChargeStateDto.PAYMENT_PENDING -> ChargeState.PaymentPending
    ChargeStateDto.SETTLED -> ChargeState.Settled
}

/** The amount shown is what is still open; a settled charge shows its full amount. */
internal fun ChargeDto.toModel() =
    Charge(
        id = id,
        reason = reason.toModel(),
        sessionId = sessionId,
        sessionStart = sessionStart,
        trainer = trainer.toModel(),
        amount = (if (state == ChargeStateDto.SETTLED) amount else open).toMoney(),
        kind = kind.toModel(),
        state = state.toModel(),
        createdAt = createdAt,
        packagePercent = packagePercent,
        dueAt = dueAt,
    )

internal fun PaymentMethodDto.toModel() = when (this) {
    PaymentMethodDto.CARD -> PaymentMethod.Card
    PaymentMethodDto.BLIK -> PaymentMethod.Blik
    PaymentMethodDto.PRZELEWY24 -> PaymentMethod.Przelewy24
    PaymentMethodDto.TEST_MODE -> PaymentMethod.TestMode
    PaymentMethodDto.CASH -> PaymentMethod.Cash
}

internal fun PaymentMethod.toDto() = when (this) {
    PaymentMethod.Card -> PaymentMethodDto.CARD
    PaymentMethod.Blik -> PaymentMethodDto.BLIK
    PaymentMethod.Przelewy24 -> PaymentMethodDto.PRZELEWY24
    PaymentMethod.TestMode -> PaymentMethodDto.TEST_MODE
    PaymentMethod.Cash -> PaymentMethodDto.CASH
}

internal fun PaymentStateDto.toModel() = when (this) {
    PaymentStateDto.PENDING -> PaymentState.Pending
    PaymentStateDto.PAID -> PaymentState.Paid
    PaymentStateDto.FAILED -> PaymentState.Failed
    PaymentStateDto.REFUNDED -> PaymentState.Refunded
}

internal fun PaymentDto.toModel() =
    Payment(id, chargeId, reason?.toModel(), trainer.toModel(), amount.toMoney(), method.toModel(), state.toModel(), at)

internal fun SessionPackageDto.toModel() = SessionPackage(
    id, name, trainer.toModel(), used, total, validUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
)

internal fun WalletDto.toModel() = Wallet(
    packages = packages.map { it.toModel() },
    charges = charges.map { it.toModel() }.sortedByDescending { it.createdAt },
    payments = payments.map { it.toModel() }.sortedByDescending { it.at },
    serverNow = serverNow,
)
