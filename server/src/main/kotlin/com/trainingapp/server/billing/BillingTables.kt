package com.trainingapp.server.billing

import kotlinx.serialization.json.JsonObject
import com.trainingapp.server.common.ApiJson
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

// Exposed mappings of the `billing` schema (V1). The ledger tables are append-only (forbid_update triggers).

object FeeQuotes : Table("billing.fee_quote") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val sessionRevision = integer("session_revision")
    val action = text("action")
    val policyVersionId = uuid("policy_version_id")
    val receivedAt = timestampWithTimeZone("received_at")
    val tierHoursBefore = integer("tier_hours_before").nullable()
    val feePercent = integer("fee_percent")
    val amountMinor = long("amount_minor")
    val currency = char("currency", 3)
    val usesFreeCancellation = bool("uses_free_cancellation")
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object FeeDecisions : Table("billing.fee_decision") {
    val id = uuid("id")
    val sessionId = uuid("session_id").references(com.trainingapp.server.scheduling.Sessions.id)
    val quoteId = uuid("quote_id").nullable()
    val action = text("action")
    val receivedAt = timestampWithTimeZone("received_at")
    val policyVersionId = uuid("policy_version_id").nullable()
    val feePercent = integer("fee_percent")
    val quotedMinor = long("quoted_minor")
    val waivedMinor = long("waived_minor")
    val currency = char("currency", 3)
    val usedFreeCancellation = bool("used_free_cancellation")
    val exempt = bool("exempt")
    val reason = text("reason").nullable()
    val decidedBy = uuid("decided_by").nullable()
    val decidedAt = timestampWithTimeZone("decided_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object PackageAgreements : Table("billing.package_agreement") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val offerId = uuid("offer_id")
    val sessionsPerPeriod = integer("sessions_per_period")
    val priceMinor = long("price_minor")
    val currency = char("currency", 3)
    val status = text("status")
    val startedAt = timestampWithTimeZone("started_at")
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val withdrawalInfoAcceptedAt = timestampWithTimeZone("withdrawal_info_accepted_at").nullable()
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object BillingPeriods : Table("billing.billing_period") {
    val id = uuid("id")
    val agreementId = uuid("agreement_id")
    val periodStart = date("period_start")
    val periodEnd = date("period_end")
    val creditUnitsGranted = integer("credit_units_granted")
    val grantedAt = timestampWithTimeZone("granted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object CreditReservations : Table("billing.credit_reservation") {
    val id = uuid("id")
    val billingPeriodId = uuid("billing_period_id")
    val sessionId = uuid("session_id")
    val reservedUnits = integer("reserved_units")
    val consumedUnits = integer("consumed_units").nullable()
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val settledAt = timestampWithTimeZone("settled_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** View: credits per agreement (units; 100 = one session). Never recomputed in Kotlin (plan §3 rule 6). */
object PackageCreditBalance : Table("billing.package_credit_balance") {
    val agreementId = uuid("agreement_id").references(PackageAgreements.id)
    val relationshipId = uuid("relationship_id")
    val grantedUnits = long("granted_units")
    val usedUnits = long("used_units")
    val availableUnits = long("available_units")
}

object Charges : Table("billing.charge") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val kind = text("kind")
    val settlement = text("settlement")
    val sessionId = uuid("session_id").nullable()
    val feeDecisionId = uuid("fee_decision_id").nullable()
    val billingPeriodId = uuid("billing_period_id").nullable()
    val amountMinor = long("amount_minor")
    val currency = char("currency", 3)
    val dueAt = timestampWithTimeZone("due_at")
    val createdAt = timestampWithTimeZone("created_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object Payments : Table("billing.payment") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val method = text("method")
    val status = text("status")
    val amountMinor = long("amount_minor")
    val currency = char("currency", 3)
    val provider = text("provider").nullable()
    val providerRef = text("provider_ref").nullable()
    val recordedBy = uuid("recorded_by").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val settledAt = timestampWithTimeZone("settled_at").nullable()
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object Allocations : Table("billing.allocation") {
    val id = uuid("id")
    val paymentId = uuid("payment_id").references(Payments.id)
    val chargeId = uuid("charge_id")
    val amountMinor = long("amount_minor")
    val createdAt = timestampWithTimeZone("created_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object Refunds : Table("billing.refund") {
    val id = uuid("id")
    val paymentId = uuid("payment_id")
    val amountMinor = long("amount_minor")
    val status = text("status")
    val reason = text("reason")
    val providerRef = text("provider_ref").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val settledAt = timestampWithTimeZone("settled_at").nullable()
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object Adjustments : Table("billing.adjustment") {
    val id = uuid("id")
    val chargeId = uuid("charge_id")
    val kind = text("kind")
    val amountMinor = long("amount_minor")
    val reason = text("reason").nullable()
    val createdBy = uuid("created_by")
    val createdAt = timestampWithTimeZone("created_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object ProviderEvents : Table("billing.provider_event") {
    val provider = text("provider")
    val eventId = text("event_id")
    val type = text("type")
    val payload = jsonb("payload", ApiJson, JsonObject.serializer())
    val receivedAt = timestampWithTimeZone("received_at")
    val processedAt = timestampWithTimeZone("processed_at").nullable()
    override val primaryKey = PrimaryKey(provider, eventId)
}

/** View: the open amount per (non-credit) charge, counting only SUCCEEDED payments. */
object ChargeBalance : Table("billing.charge_balance") {
    val chargeId = uuid("charge_id")
    val relationshipId = uuid("relationship_id")
    val currency = char("currency", 3)
    val dueAt = timestampWithTimeZone("due_at")
    val openMinor = long("open_minor")
}

/** View: the only financial fact the trainer's client list may show. */
object RelationshipOverdue : Table("billing.relationship_overdue") {
    val relationshipId = uuid("relationship_id")
    val hasOverdue = bool("has_overdue")
}
