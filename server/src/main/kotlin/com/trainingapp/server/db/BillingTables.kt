package com.trainingapp.server.db

import com.trainingapp.server.platform.jsonb
import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

/** Server fee quote shown before a fee-bearing action. */
object FeeQuotes : Table("billing.fee_quote") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val sessionRevision = integer("session_revision")
    val action = text("action")
    val policyVersionId = uuid("policy_version_id")
    val receivedAt = tstz("received_at")
    val tierHoursBefore = integer("tier_hours_before").nullable()
    val feePercent = integer("fee_percent")
    val amountMinor = long("amount_minor")
    val currency = char("currency", 3)
    val usesFreeCancellation = bool("uses_free_cancellation")
    val expiresAt = tstz("expires_at")
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Append-only record of what was decided for a fee-bearing event. */
object FeeDecisions : Table("billing.fee_decision") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val quoteId = uuid("quote_id").nullable()
    val action = text("action")
    val receivedAt = tstz("received_at")
    val policyVersionId = uuid("policy_version_id").nullable()
    val feePercent = integer("fee_percent")
    val quotedMinor = long("quoted_minor")
    val waivedMinor = long("waived_minor")
    val currency = char("currency", 3)
    val usedFreeCancellation = bool("used_free_cancellation")
    val exempt = bool("exempt")
    val reason = text("reason").nullable()
    val decidedBy = uuid("decided_by").nullable()
    val decidedAt = tstz("decided_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** Package subscription between a trainer and a client. */
object PackageAgreements : Table("billing.package_agreement") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val offerId = uuid("offer_id")
    val sessionsPerPeriod = integer("sessions_per_period")
    val priceMinor = long("price_minor")
    val currency = char("currency", 3)
    val status = text("status")
    val startedAt = tstz("started_at")
    val endedAt = tstz("ended_at").nullable()
    val withdrawalInfoAcceptedAt = tstz("withdrawal_info_accepted_at").nullable()
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** One paid period of a package; credits exist once granted_at is set. */
object BillingPeriods : Table("billing.billing_period") {
    val id = uuid("id")
    val agreementId = uuid("agreement_id")
    val periodStart = date("period_start")
    val periodEnd = date("period_end")
    val creditUnitsGranted = integer("credit_units_granted")
    val grantedAt = tstz("granted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Credit units reserved for a package session, consumed or released by the outcome. */
object CreditReservations : Table("billing.credit_reservation") {
    val id = uuid("id")
    val billingPeriodId = uuid("billing_period_id")
    val sessionId = uuid("session_id")
    val reservedUnits = integer("reserved_units")
    val consumedUnits = integer("consumed_units").nullable()
    val status = text("status")
    val createdAt = tstz("created_at")
    val settledAt = tstz("settled_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Reference to an invoice issued by an external provider. */
object InvoiceRefs : Table("billing.invoice_ref") {
    val id = uuid("id")
    val provider = text("provider")
    val externalId = text("external_id")
    val number = text("number").nullable()
    val status = text("status")
    val issuedAt = tstz("issued_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** What is owed; append-only. */
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
    val dueAt = tstz("due_at")
    val createdAt = tstz("created_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** Money received; status only moves PENDING to SUCCEEDED or FAILED. */
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
    val createdAt = tstz("created_at")
    val settledAt = tstz("settled_at").nullable()
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** Which charges a payment settles; append-only, negative amount = reversal. */
object Allocations : Table("billing.allocation") {
    val id = uuid("id")
    val paymentId = uuid("payment_id")
    val chargeId = uuid("charge_id")
    val amountMinor = long("amount_minor")
    val createdAt = tstz("created_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** Refund of a payment. */
object Refunds : Table("billing.refund") {
    val id = uuid("id")
    val paymentId = uuid("payment_id")
    val amountMinor = long("amount_minor")
    val status = text("status")
    val reason = text("reason")
    val providerRef = text("provider_ref").nullable()
    val createdAt = tstz("created_at")
    val settledAt = tstz("settled_at").nullable()
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** Waivers and corrections that reduce a charge; append-only. */
object Adjustments : Table("billing.adjustment") {
    val id = uuid("id")
    val chargeId = uuid("charge_id")
    val kind = text("kind")
    val amountMinor = long("amount_minor")
    val reason = text("reason").nullable()
    val createdBy = uuid("created_by")
    val createdAt = tstz("created_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** Link from a charge to the invoice issued for it. */
object ChargeInvoices : Table("billing.charge_invoice") {
    val chargeId = uuid("charge_id")
    val invoiceRefId = uuid("invoice_ref_id")
    override val primaryKey = PrimaryKey(chargeId)
}

/** Webhook events stored once by provider event id. */
object ProviderEvents : Table("billing.provider_event") {
    val provider = text("provider")
    val eventId = text("event_id")
    val type = text("type")
    val payload = jsonb("payload")
    val receivedAt = tstz("received_at")
    val processedAt = tstz("processed_at").nullable()
    override val primaryKey = PrimaryKey(provider, eventId)
}

/** Read-only view: credit units granted, used and available per package agreement. */
object PackageCreditBalanceView : Table("billing.package_credit_balance") {
    val agreementId = uuid("agreement_id")
    val relationshipId = uuid("relationship_id")
    val grantedUnits = long("granted_units")
    val usedUnits = long("used_units")
    val availableUnits = long("available_units")
}

/** Read-only view: open amount per non-package-credit charge. */
object ChargeBalanceView : Table("billing.charge_balance") {
    val chargeId = uuid("charge_id")
    val relationshipId = uuid("relationship_id")
    val currency = char("currency", 3)
    val dueAt = tstz("due_at")
    val openMinor = long("open_minor")
}

/** Read-only view: yes/no overdue flag per relationship. */
object RelationshipOverdueView : Table("billing.relationship_overdue") {
    val relationshipId = uuid("relationship_id")
    val hasOverdue = bool("has_overdue").nullable()
}
