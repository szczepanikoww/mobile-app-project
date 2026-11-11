package com.trainingapp.server.billing

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import com.trainingapp.contract.api.ChargeKind
import com.trainingapp.contract.api.SessionPaymentDto
import com.trainingapp.contract.api.Settlement
import com.trainingapp.contract.api.PaymentKind
import com.trainingapp.server.db.Allocations
import com.trainingapp.server.db.BillingPeriods
import com.trainingapp.server.db.Charges
import com.trainingapp.server.db.CreditReservations
import com.trainingapp.server.db.FeeDecisions
import com.trainingapp.server.db.PackageAgreements
import com.trainingapp.server.db.PackageCreditBalanceView
import com.trainingapp.server.db.Payments
import com.trainingapp.server.db.Refunds
import com.trainingapp.server.platform.conflict
import com.trainingapp.server.platform.newId
import com.trainingapp.server.platform.toOffset
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Instant

/** Fee-bearing events of the session lifecycle (`billing.fee_decision.action`). */
enum class FeeAction { CLIENT_CANCEL, LATE_RESCHEDULE, NO_SHOW, TRAINER_CANCEL }

/**
 * The append-only billing ledger (architecture §8). Rows are only inserted; a waiver or correction is a new row.
 * Called inside the scheduling transaction so the session change and its money consequence commit together.
 */
object Ledger {
    const val UNITS_PER_SESSION = 100

    /**
     * Records what was decided for a fee-bearing event and, when money is still owed, the matching charge.
     * Returns the charge id, or null when nothing is owed (fee 0, exempt, free cancellation or full waiver).
     */
    fun decideFee(
        sessionId: UUID,
        relationshipId: UUID,
        action: FeeAction,
        receivedAt: Instant,
        policyVersionId: UUID?,
        feePercent: Int,
        quotedMinor: Long,
        currency: String,
        waived: Boolean,
        exempt: Boolean,
        usedFreeCancellation: Boolean,
        reason: String?,
        decidedBy: UUID?,
        settlement: Settlement,
        now: Instant,
    ): UUID? {
        val quoted = if (exempt || action == FeeAction.TRAINER_CANCEL) 0L else quotedMinor
        val waivedMinor = if (waived) quoted else 0L
        val decisionId = newId()
        FeeDecisions.insert {
            it[id] = decisionId
            it[FeeDecisions.sessionId] = sessionId
            it[FeeDecisions.action] = action.name
            it[FeeDecisions.receivedAt] = receivedAt.toOffset()
            it[FeeDecisions.policyVersionId] = policyVersionId
            it[FeeDecisions.feePercent] = if (action == FeeAction.TRAINER_CANCEL || exempt) 0 else feePercent
            it[FeeDecisions.quotedMinor] = quoted
            it[FeeDecisions.waivedMinor] = waivedMinor
            it[FeeDecisions.currency] = currency
            it[FeeDecisions.usedFreeCancellation] = usedFreeCancellation
            it[FeeDecisions.exempt] = exempt
            it[FeeDecisions.reason] = reason?.takeIf { r -> r.isNotBlank() }?.take(300)
            it[FeeDecisions.decidedBy] = decidedBy
            it[decidedAt] = now.toOffset()
            it[operationId] = newId()
        }
        val owed = quoted - waivedMinor
        if (owed <= 0) return null
        val kind = when (action) {
            FeeAction.CLIENT_CANCEL -> ChargeKind.LATE_CANCELLATION_FEE
            FeeAction.LATE_RESCHEDULE -> ChargeKind.LATE_RESCHEDULE_FEE
            FeeAction.NO_SHOW -> ChargeKind.NO_SHOW_FEE
            FeeAction.TRAINER_CANCEL -> return null
        }
        return charge(relationshipId, kind, settlement, sessionId, decisionId, owed, currency, dueAt = now, now = now)
    }

    fun charge(
        relationshipId: UUID,
        kind: ChargeKind,
        settlement: Settlement,
        sessionId: UUID?,
        feeDecisionId: UUID?,
        amountMinor: Long,
        currency: String,
        dueAt: Instant,
        now: Instant,
    ): UUID {
        val chargeId = newId()
        Charges.insert {
            it[id] = chargeId
            it[Charges.relationshipId] = relationshipId
            it[Charges.kind] = kind.name
            it[Charges.settlement] = settlement.name
            it[Charges.sessionId] = sessionId
            it[Charges.feeDecisionId] = feeDecisionId
            it[Charges.amountMinor] = amountMinor
            it[Charges.currency] = currency
            it[Charges.dueAt] = dueAt.toOffset()
            it[createdAt] = now.toOffset()
            it[operationId] = newId()
        }
        return chargeId
    }

    fun hasSessionCharge(sessionId: UUID): Boolean =
        Charges.selectAll().where { (Charges.sessionId eq sessionId) and (Charges.kind eq ChargeKind.SESSION.name) }.any()

    /** How a fee for a session paid with [kind] is settled. Package fees with USE_CREDIT are taken from credits. */
    fun feeSettlement(kind: PaymentKind, packageUsesCredit: Boolean): Settlement = when (kind) {
        PaymentKind.ONLINE -> Settlement.ONLINE
        PaymentKind.CASH -> Settlement.CASH
        PaymentKind.PACKAGE -> if (packageUsesCredit) Settlement.PACKAGE_CREDIT else Settlement.CASH
    }

    // ------------------------------------------------------------------ package credits

    /** The relationship's active package agreement with its credit balance, if any. */
    private fun activeAgreement(relationshipId: UUID): UUID? =
        PackageAgreements.selectAll()
            .where { (PackageAgreements.relationshipId eq relationshipId) and (PackageAgreements.status eq "ACTIVE") }
            .orderBy(PackageAgreements.startedAt, SortOrder.ASC)
            .firstOrNull()?.get(PackageAgreements.id)

    /**
     * Confirming a package session reserves one session's credits from the oldest paid period that still has units
     * (credits never expire, DB6). CONFLICT when the client has no credits left.
     */
    fun reserveCredit(relationshipId: UUID, sessionId: UUID, now: Instant) {
        if (CreditReservations.selectAll().where { (CreditReservations.sessionId eq sessionId) and (CreditReservations.status eq "RESERVED") }.any()) return
        val agreement = activeAgreement(relationshipId) ?: conflict("Client has no active package")
        val periods = BillingPeriods.selectAll()
            .where { (BillingPeriods.agreementId eq agreement) and BillingPeriods.grantedAt.isNotNull() }
            .orderBy(BillingPeriods.periodStart, SortOrder.ASC)
            .forUpdate()
            .toList()
        val period = periods.firstOrNull { p ->
            val used = CreditReservations.selectAll().where { CreditReservations.billingPeriodId eq p[BillingPeriods.id] }
                .sumOf { r ->
                    when (r[CreditReservations.status]) {
                        "RESERVED" -> r[CreditReservations.reservedUnits]
                        "CONSUMED" -> r[CreditReservations.consumedUnits] ?: 0
                        else -> 0
                    }
                }
            p[BillingPeriods.creditUnitsGranted] - used >= UNITS_PER_SESSION
        } ?: conflict("No package credits left")
        CreditReservations.insert {
            it[id] = newId()
            it[billingPeriodId] = period[BillingPeriods.id]
            it[CreditReservations.sessionId] = sessionId
            it[reservedUnits] = UNITS_PER_SESSION
            it[status] = "RESERVED"
            it[createdAt] = now.toOffset()
        }
    }

    /** The session outcome uses [units] of the reservation (0–100); the rest returns to the balance. */
    fun consumeCredit(sessionId: UUID, units: Int, now: Instant) {
        CreditReservations.update({ (CreditReservations.sessionId eq sessionId) and (CreditReservations.status eq "RESERVED") }) {
            it[status] = if (units > 0) "CONSUMED" else "RELEASED"
            it[consumedUnits] = if (units > 0) units else null
            it[settledAt] = now.toOffset()
        }
    }

    fun releaseCredit(sessionId: UUID, now: Instant) = consumeCredit(sessionId, 0, now)

    /** "Pakiet: 6 z 8": credits used and granted, in sessions, for the relationship's active agreement. */
    fun sessionPayment(kind: PaymentKind, relationshipId: UUID): SessionPaymentDto {
        if (kind != PaymentKind.PACKAGE) return SessionPaymentDto(kind)
        val agreement = activeAgreement(relationshipId) ?: return SessionPaymentDto(kind)
        val balance = PackageCreditBalanceView.selectAll().where { PackageCreditBalanceView.agreementId eq agreement }.singleOrNull()
            ?: return SessionPaymentDto(kind)
        return SessionPaymentDto(
            kind,
            packageUsed = (balance[PackageCreditBalanceView.usedUnits] / UNITS_PER_SESSION).toInt(),
            packageTotal = (balance[PackageCreditBalanceView.grantedUnits] / UNITS_PER_SESSION).toInt(),
        )
    }

    // ------------------------------------------------------------------ refunds

    /**
     * A trainer cancellation never costs the client: money already received for the session is refunded. The refund
     * is PENDING until the payment provider confirms it (no external call inside the transaction).
     */
    fun refundSession(sessionId: UUID, now: Instant) {
        val charges = Charges.selectAll().where { (Charges.sessionId eq sessionId) and (Charges.kind eq ChargeKind.SESSION.name) }
            .map { it[Charges.id] }
        if (charges.isEmpty()) return
        Allocations.join(Payments, JoinType.INNER, Allocations.paymentId, Payments.id)
            .selectAll()
            .where { (Allocations.chargeId inList charges) and (Payments.status eq "SUCCEEDED") }
            .groupBy { it[Payments.id] }
            .forEach { (paymentId, rows) ->
                val amount = rows.sumOf { it[Allocations.amountMinor] }
                if (amount > 0) {
                    Refunds.insert {
                        it[id] = newId()
                        it[Refunds.paymentId] = paymentId
                        it[Refunds.amountMinor] = amount
                        it[status] = "PENDING"
                        it[reason] = "TRAINER_CANCELLED"
                        it[createdAt] = now.toOffset()
                        it[operationId] = newId()
                    }
                }
            }
    }

}
