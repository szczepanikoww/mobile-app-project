package com.trainingapp.server.billing

import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.server.common.Ids
import com.trainingapp.server.common.toOffset
import com.trainingapp.server.platform.Outbox.emit
import com.trainingapp.server.scheduling.Sessions
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Online sessions paid when they are booked (decision P1, docs/features/stripe-payments.md). Used from both the client
 * flow and the trainer API, always inside the scheduling transaction.
 *
 * The session's own `SESSION` charge is created with the booking (the same operation id the check-in would use, so
 * R10 never charges it twice). The request reaches the trainer only after the payment succeeds. When the session ends
 * without being held, [settle] moves what was paid to the session's fees and refunds the rest. Refunds are PENDING
 * rows that the worker sends to the provider ([BillingService.processRefunds]).
 */
object Prepaid {
    /** Refund reasons (`billing.refund.reason`, V4). */
    const val NOT_ACCEPTED = "NOT_ACCEPTED"
    const val TRAINER_CANCELLED = "TRAINER_CANCELLED"
    const val CLIENT_CANCELLED = "CLIENT_CANCELLED"
    const val NO_SHOW = "NO_SHOW"

    /** The booking's own charge, due at once. Returns its id. */
    fun JdbcTransaction.chargeBooking(sessionId: Uuid, relationshipId: Uuid, price: MoneyDto, now: Instant): Uuid {
        val id = Ids.v7()
        Charges.insertIgnore {
            it[Charges.id] = id
            it[Charges.relationshipId] = relationshipId
            it[kind] = "SESSION"
            it[settlement] = "ONLINE"
            it[Charges.sessionId] = sessionId
            it[amountMinor] = price.minor
            it[currency] = price.currency
            it[dueAt] = now.toOffset()
            it[operationId] = Ids.derived(sessionId, "session-charge")
        }
        return sessionCharge(sessionId)!!
    }

    /** The online `SESSION` charge of [sessionId], if there is one. */
    fun JdbcTransaction.sessionCharge(sessionId: Uuid): Uuid? =
        Charges.selectAll().where { (Charges.sessionId eq sessionId) and (Charges.kind eq "SESSION") and (Charges.settlement eq "ONLINE") }
            .singleOrNull()?.get(Charges.id)

    /** True when the charge's whole amount was paid (succeeded payments, nothing adjusted away). */
    fun JdbcTransaction.isPaid(chargeId: Uuid): Boolean {
        val amount = Charges.selectAll().where { Charges.id eq chargeId }.single()[Charges.amountMinor]
        val paid = (Allocations innerJoin Payments).selectAll()
            .where { (Allocations.chargeId eq chargeId) and (Payments.status eq "SUCCEEDED") }.sumOf { it[Allocations.amountMinor] }
        return paid >= amount
    }

    /**
     * After [paymentId] succeeded: a prepaid request it paid for now reaches the trainer. When the request was closed
     * while the payment was still being processed (expired, withdrawn), the money goes straight back.
     */
    fun JdbcTransaction.onPaymentSucceeded(paymentId: Uuid, now: Instant) {
        val chargeIds = Allocations.selectAll().where { Allocations.paymentId eq paymentId }.map { it[Allocations.chargeId] }
        if (chargeIds.isEmpty()) return
        val sessionIds = Charges.selectAll().where { (Charges.id inList chargeIds) and (Charges.kind eq "SESSION") }
            .mapNotNull { it[Charges.sessionId] }
        for (sessionId in sessionIds) {
            val session = Sessions.selectAll().where { Sessions.id eq sessionId }.forUpdate(ForUpdateOption.ForUpdate).single()
            val status = session[Sessions.status]
            if (session[Sessions.awaitingPayment] && status == "REQUESTED") {
                Sessions.update({ Sessions.id eq sessionId }) { it[awaitingPayment] = false }
                emit("session", sessionId, "session.requested", mapOf("trainerId" to session[Sessions.trainerId], "clientId" to session[Sessions.clientId]))
            } else if (status !in setOf("REQUESTED", "CONFIRMED", "COMPLETED")) {
                settle(sessionId, NOT_ACCEPTED, session[Sessions.createdBy], now)
            }
        }
    }

    /**
     * The prepaid session ended without being held as booked ([reason]). Its price is no longer owed (an adjustment),
     * what was paid for it pays the session's open online fees (late cancellation, no-show), and the rest is refunded.
     * Idempotent: a second call finds nothing left to move. Nothing happens for sessions that were not prepaid.
     */
    fun JdbcTransaction.settle(sessionId: Uuid, reason: String, actor: Uuid, now: Instant) {
        val charge = Charges.selectAll().where { (Charges.sessionId eq sessionId) and (Charges.kind eq "SESSION") and (Charges.settlement eq "ONLINE") }
            .forUpdate(ForUpdateOption.ForUpdate).singleOrNull() ?: return
        val chargeId = charge[Charges.id]
        Sessions.update({ Sessions.id eq sessionId }) { it[awaitingPayment] = false }

        val adjusted = Adjustments.selectAll().where { Adjustments.chargeId eq chargeId }.sumOf { it[Adjustments.amountMinor] }
        val stillOwed = charge[Charges.amountMinor] - adjusted
        if (stillOwed > 0) {
            Adjustments.insert {
                it[id] = Ids.v7()
                it[Adjustments.chargeId] = chargeId
                it[kind] = "CORRECTION"
                it[amountMinor] = stillOwed
                it[Adjustments.reason] = "Session not held: $reason"
                it[createdBy] = actor
                it[operationId] = Ids.v7()
            }
        }

        val paid = (Allocations innerJoin Payments).selectAll()
            .where { (Allocations.chargeId eq chargeId) and (Payments.status eq "SUCCEEDED") }
            .groupBy { it[Payments.id] }
            .mapValues { (_, rows) -> rows.sumOf { it[Allocations.amountMinor] } }
        for ((paymentId, net) in paid) {
            if (net <= 0) continue
            allocate(paymentId, chargeId, -net)
            var left = net
            val fees = Charges.selectAll()
                .where { (Charges.sessionId eq sessionId) and (Charges.kind like "%FEE") and (Charges.settlement eq "ONLINE") }
                .orderBy(Charges.createdAt, SortOrder.ASC).map { it[Charges.id] }
            for (fee in fees) {
                if (left <= 0) break
                val open = ChargeBalance.selectAll().where { ChargeBalance.chargeId eq fee }.single()[ChargeBalance.openMinor]
                val part = minOf(open, left)
                if (part > 0) {
                    allocate(paymentId, fee, part)
                    left -= part
                }
            }
            if (left > 0) {
                Refunds.insert {
                    it[id] = Ids.v7()
                    it[Refunds.paymentId] = paymentId
                    it[amountMinor] = left
                    it[status] = "PENDING"
                    it[Refunds.reason] = reason
                    it[createdAt] = now.toOffset()
                    it[operationId] = Ids.v7()
                }
            }
        }
        emit("charge", sessionId, "charge.settled", mapOf("sessionId" to sessionId, "clientId" to clientOf(sessionId)))
    }

    private fun JdbcTransaction.clientOf(sessionId: Uuid): Uuid =
        Sessions.selectAll().where { Sessions.id eq sessionId }.single()[Sessions.clientId]

    private fun JdbcTransaction.allocate(paymentId: Uuid, chargeId: Uuid, amount: Long) {
        Allocations.insert {
            it[id] = Ids.v7()
            it[Allocations.paymentId] = paymentId
            it[Allocations.chargeId] = chargeId
            it[amountMinor] = amount
            it[operationId] = Ids.v7()
        }
    }
}
