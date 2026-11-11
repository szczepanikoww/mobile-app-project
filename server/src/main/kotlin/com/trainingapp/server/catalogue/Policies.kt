package com.trainingapp.server.catalogue

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import com.trainingapp.contract.api.CashFeeMode
import com.trainingapp.contract.api.ClientPolicyMode
import com.trainingapp.contract.api.ClientPolicyOverrideDto
import com.trainingapp.contract.api.PackageFeeMode
import com.trainingapp.contract.api.PolicyDto
import com.trainingapp.contract.api.PolicyTierDto
import com.trainingapp.contract.api.SavePolicyRequest
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.PolicyRules
import com.trainingapp.contract.policy.PolicyTier
import com.trainingapp.contract.policy.PolicyValidator
import com.trainingapp.server.db.ClientPolicyOverrides
import com.trainingapp.server.db.PolicyTiers
import com.trainingapp.server.db.PolicyVersions
import com.trainingapp.server.db.TrainerProfiles
import com.trainingapp.server.platform.conflict
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.newId
import com.trainingapp.server.platform.notFound
import com.trainingapp.server.platform.toInstantK
import com.trainingapp.server.platform.toOffset
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Instant

/**
 * Versioned cancellation policies (architecture §3, B5). A version is immutable (DB trigger); saving inserts the next
 * version. Sessions keep the version the client accepted, so a new version only affects later bookings.
 */
object Policies {
    fun byId(versionId: UUID): PolicyDto {
        val row = PolicyVersions.selectAll().where { PolicyVersions.id eq versionId }.singleOrNull() ?: notFound("Policy not found")
        return row.toDto()
    }

    /** The trainer's newest version, or null before the first save. */
    fun current(trainerId: UUID): PolicyDto? =
        PolicyVersions.selectAll().where { PolicyVersions.trainerId eq trainerId }
            .orderBy(PolicyVersions.version, SortOrder.DESC).limit(1).singleOrNull()?.toDto()

    /**
     * The version a new session for [relationshipId] snapshots: the client's OTHER_POLICY override, else the
     * trainer's current version. EXEMPT is applied at fee decision time, not here.
     */
    fun forNewSession(trainerId: UUID, relationshipId: UUID): PolicyDto {
        val override = ClientPolicyOverrides.selectAll().where { ClientPolicyOverrides.relationshipId eq relationshipId }.singleOrNull()
        override?.get(ClientPolicyOverrides.policyVersionId)?.let { return byId(it) }
        return current(trainerId) ?: conflict("Set a cancellation policy first")
    }

    fun override(relationshipId: UUID): ClientPolicyOverrideDto? =
        ClientPolicyOverrides.selectAll().where { ClientPolicyOverrides.relationshipId eq relationshipId }.singleOrNull()?.let {
            ClientPolicyOverrideDto(ClientPolicyMode.valueOf(it[ClientPolicyOverrides.mode]), it[ClientPolicyOverrides.policyVersionId]?.toString())
        }

    fun isExempt(relationshipId: UUID): Boolean = override(relationshipId)?.mode == ClientPolicyMode.EXEMPT

    /**
     * Saves a new version. CONFLICT when [SavePolicyRequest.basedOnVersionId] is no longer the current version
     * (another device saved first). The trainer row is locked so two saves cannot both take the same number.
     */
    fun save(trainerId: UUID, request: SavePolicyRequest, now: Instant): PolicyDto {
        val rules = PolicyRules(request.tiers.map { PolicyTier(it.hoursBefore, it.feePercent) }, request.noShowPercent,
            request.graceMinutes, request.freeLateCancellationsPerMonth)
        val errors = PolicyValidator.errors(rules)
        if (errors.isNotEmpty()) invalid("Invalid policy", errors.map { it.name })
        if (request.tiers.size > 4) invalid("Invalid policy", listOf("TooManyTiers"))
        if ((request.clientNote?.length ?: 0) > 300) invalid("Invalid policy", listOf("ClientNote"))

        TrainerProfiles.selectAll().where { TrainerProfiles.userId eq trainerId }.forUpdate().single()
        val current = current(trainerId)
        if (current?.versionId != request.basedOnVersionId) conflict("Policy changed since it was opened")

        val id = newId()
        PolicyVersions.insert {
            it[PolicyVersions.id] = id
            it[PolicyVersions.trainerId] = trainerId
            it[version] = (current?.version ?: 0) + 1
            it[noShowPercent] = request.noShowPercent
            it[graceMinutes] = request.graceMinutes
            it[freeLateCancellations] = request.freeLateCancellationsPerMonth
            it[lateRescheduleIsCancel] = request.lateRescheduleIsCancel
            it[cashFeeMode] = request.cashFeeMode.name
            it[packageFeeMode] = request.packageFeeMode.name
            it[clientNote] = request.clientNote?.takeIf { note -> note.isNotBlank() }
            it[createdAt] = now.toOffset()
        }
        rules.sortedTiers.forEach { tier ->
            PolicyTiers.insert {
                it[policyVersionId] = id
                it[hoursBefore] = tier.hoursBefore
                it[feePercent] = tier.feePercent
            }
        }
        return byId(id)
    }

    private fun ResultRow.toDto(): PolicyDto {
        val id = this[PolicyVersions.id]
        val tiers = PolicyTiers.selectAll().where { PolicyTiers.policyVersionId eq id }
            .map { PolicyTierDto(it[PolicyTiers.hoursBefore], it[PolicyTiers.feePercent]) }
            .sortedByDescending { it.hoursBefore }
        return PolicyDto(
            versionId = id.toString(),
            trainerId = this[PolicyVersions.trainerId].toString(),
            version = this[PolicyVersions.version],
            createdAt = this[PolicyVersions.createdAt].toInstantK(),
            tiers = tiers,
            noShowPercent = this[PolicyVersions.noShowPercent],
            graceMinutes = this[PolicyVersions.graceMinutes],
            freeLateCancellationsPerMonth = this[PolicyVersions.freeLateCancellations],
            lateRescheduleIsCancel = this[PolicyVersions.lateRescheduleIsCancel],
            cashFeeMode = CashFeeMode.valueOf(this[PolicyVersions.cashFeeMode]),
            packageFeeMode = PackageFeeMode.valueOf(this[PolicyVersions.packageFeeMode]),
            clientNote = this[PolicyVersions.clientNote],
        )
    }
}

/** The contract policy used by [com.trainingapp.contract.policy.FeeCalculator] — the same calculation as the app. */
fun PolicyDto.toContract() = CancellationPolicy(
    versionId = versionId,
    trainerId = trainerId,
    version = version,
    createdAt = createdAt,
    tiers = tiers.map { PolicyTier(it.hoursBefore, it.feePercent) },
    noShowPercent = noShowPercent,
    graceMinutes = graceMinutes,
    freeLateCancellationsPerMonth = freeLateCancellationsPerMonth,
)
