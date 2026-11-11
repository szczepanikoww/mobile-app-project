package com.trainingapp.server.scheduling

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.greaterEq
import com.trainingapp.contract.api.BlockKind
import com.trainingapp.contract.api.CalendarBlockDto
import com.trainingapp.contract.api.ClientSummaryDto
import com.trainingapp.contract.api.PaymentKind
import com.trainingapp.contract.api.ProposalDto
import com.trainingapp.contract.api.ProposerRole
import com.trainingapp.contract.api.RelationshipStatus
import com.trainingapp.contract.api.RescheduleStatus
import com.trainingapp.contract.api.SessionDto
import com.trainingapp.contract.api.SessionStatus
import com.trainingapp.contract.money.Money
import com.trainingapp.server.billing.Ledger
import com.trainingapp.server.db.AppUsers
import com.trainingapp.server.db.AssignedPlans
import com.trainingapp.server.db.CalendarBlocks
import com.trainingapp.server.db.Gyms
import com.trainingapp.server.db.PlanVersions
import com.trainingapp.server.db.RelationshipOverdueView
import com.trainingapp.server.db.RescheduleRequests
import com.trainingapp.server.db.Sessions
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.platform.initialsOf
import com.trainingapp.server.platform.toInstantK
import com.trainingapp.server.platform.toOffset
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Instant

/** Read models of the scheduling module, shared by the trainer endpoints. Call inside a transaction. */
object SessionReads {
    val OPEN_RESCHEDULE = listOf(RescheduleStatus.PENDING.name, RescheduleStatus.COUNTER_PROPOSED.name)

    fun clients(trainerId: UUID, includeEnded: Boolean = true): List<ClientSummaryDto> {
        val overdue = RelationshipOverdueView.selectAll()
            .associate { it[RelationshipOverdueView.relationshipId] to (it[RelationshipOverdueView.hasOverdue] == true) }
        return TrainerClients.join(AppUsers, JoinType.INNER, TrainerClients.clientId, AppUsers.id)
            .selectAll()
            .where { TrainerClients.trainerId eq trainerId }
            .filter { includeEnded || it[TrainerClients.status] != "ENDED" }
            .map { it.toClient(overdue[it[TrainerClients.id]] ?: false) }
            .sortedWith(compareBy({ it.status == RelationshipStatus.ENDED }, { it.name.lowercase() }))
    }

    fun client(relationshipId: UUID): ClientSummaryDto {
        val overdue = RelationshipOverdueView.selectAll().where { RelationshipOverdueView.relationshipId eq relationshipId }
            .singleOrNull()?.get(RelationshipOverdueView.hasOverdue) == true
        return TrainerClients.join(AppUsers, JoinType.INNER, TrainerClients.clientId, AppUsers.id)
            .selectAll().where { TrainerClients.id eq relationshipId }.single().toClient(overdue)
    }

    private fun ResultRow.toClient(overdue: Boolean): ClientSummaryDto {
        // An anonymised (deleted) account keeps its ledger rows; its name is not shown.
        val name = if (this[AppUsers.deletedAt] != null) "—" else this[AppUsers.displayName]
        return ClientSummaryDto(
            id = this[TrainerClients.id].toString(),
            userId = this[TrainerClients.clientId].toString(),
            name = name,
            initials = initialsOf(name),
            status = RelationshipStatus.valueOf(this[TrainerClients.status]),
            hasOverduePayment = overdue,
        )
    }

    /** The trainer's sessions starting in [from, to), every status. */
    fun sessions(trainerId: UUID, from: Instant, to: Instant): List<SessionDto> =
        Sessions.selectAll()
            .where { (Sessions.trainerId eq trainerId) and (Sessions.startsAt greaterEq from.toOffset()) and (Sessions.startsAt less to.toOffset()) }
            .orderBy(Sessions.startsAt, SortOrder.ASC)
            .map { toDto(it) }

    /** Sessions with an open request in any range: a booking request or an open reschedule proposal. */
    fun openRequestSessions(trainerId: UUID): List<SessionDto> {
        val requested = Sessions.selectAll()
            .where { (Sessions.trainerId eq trainerId) and (Sessions.status eq SessionStatus.REQUESTED.name) }
            .map { toDto(it) }
        val rescheduled = Sessions.join(RescheduleRequests, JoinType.INNER, Sessions.id, RescheduleRequests.sessionId)
            .selectAll()
            .where { (Sessions.trainerId eq trainerId) and (RescheduleRequests.status inList OPEN_RESCHEDULE) }
            .map { toDto(it) }
        return (requested + rescheduled).distinctBy { it.id }
    }

    fun session(id: UUID): SessionDto? = Sessions.selectAll().where { Sessions.id eq id }.singleOrNull()?.let { toDto(it) }

    fun openReschedule(sessionId: UUID): ResultRow? =
        RescheduleRequests.selectAll()
            .where { (RescheduleRequests.sessionId eq sessionId) and (RescheduleRequests.status inList OPEN_RESCHEDULE) }
            .singleOrNull()

    fun toDto(row: ResultRow): SessionDto {
        val id = row[Sessions.id]
        val open = openReschedule(id)
        val proposal = open?.let {
            // A counter-proposal by the trainer replaces the client's time as the open question.
            val countered = it[RescheduleRequests.status] == RescheduleStatus.COUNTER_PROPOSED.name
            val proposer = ProposerRole.valueOf(it[RescheduleRequests.proposerRole])
            ProposalDto(
                id = it[RescheduleRequests.id].toString(),
                start = (if (countered) it[RescheduleRequests.counterStartsAt]!! else it[RescheduleRequests.proposedStartsAt]).toInstantK(),
                end = (if (countered) it[RescheduleRequests.counterEndsAt]!! else it[RescheduleRequests.proposedEndsAt]).toInstantK(),
                author = if (countered) other(proposer) else proposer,
                status = RescheduleStatus.valueOf(it[RescheduleRequests.status]),
                requestedAt = it[RescheduleRequests.receivedAt].toInstantK(),
            )
        }
        val gymName = row[Sessions.gymId]?.let { gym -> Gyms.selectAll().where { Gyms.id eq gym }.singleOrNull()?.get(Gyms.name) }
        val planTitle = AssignedPlans.join(PlanVersions, JoinType.INNER, AssignedPlans.planVersionId, PlanVersions.id)
            .selectAll().where { AssignedPlans.sessionId eq id }
            .orderBy(AssignedPlans.assignedAt, SortOrder.DESC).limit(1).singleOrNull()?.get(PlanVersions.name)
        val kind = PaymentKind.valueOf(row[Sessions.paymentKind])
        return SessionDto(
            id = id.toString(),
            clientId = row[Sessions.relationshipId].toString(),
            trainerId = row[Sessions.trainerId].toString(),
            start = row[Sessions.startsAt].toInstantK(),
            end = row[Sessions.endsAt].toInstantK(),
            timeZone = row[Sessions.timeZone],
            location = row[Sessions.placeNote]?.takeIf { it.isNotBlank() } ?: gymName.orEmpty(),
            gymId = row[Sessions.gymId]?.toString(),
            status = SessionStatus.valueOf(row[Sessions.status]),
            revision = row[Sessions.revision],
            proposal = proposal,
            planTitle = planTitle,
            payment = Ledger.sessionPayment(kind, row[Sessions.relationshipId]),
            policyVersionId = row[Sessions.policyVersionId].toString(),
            price = Money(row[Sessions.priceMinor], row[Sessions.currency]),
            requestedAt = row[Sessions.requestedAt].toInstantK(),
        )
    }

    private fun other(role: ProposerRole) = if (role == ProposerRole.CLIENT) ProposerRole.TRAINER else ProposerRole.CLIENT

    fun blocks(trainerId: UUID, from: Instant, to: Instant): List<CalendarBlockDto> =
        CalendarBlocks.selectAll()
            .where {
                (CalendarBlocks.trainerId eq trainerId) and CalendarBlocks.deletedAt.isNull() and
                    (CalendarBlocks.endsAt greater from.toOffset()) and (CalendarBlocks.startsAt less to.toOffset())
            }
            .orderBy(CalendarBlocks.startsAt, SortOrder.ASC)
            .map {
                CalendarBlockDto(
                    id = it[CalendarBlocks.id].toString(),
                    kind = BlockKind.valueOf(it[CalendarBlocks.kind]),
                    title = it[CalendarBlocks.title],
                    start = it[CalendarBlocks.startsAt].toInstantK(),
                    end = it[CalendarBlocks.endsAt].toInstantK(),
                )
            }

    /**
     * Whether [start]–[end] (+ the trainer's buffer) fits: no confirmed session of the trainer (other than
     * [ignoreSessionId]) and no calendar block overlaps. The database still has the final word on sessions.
     */
    fun slotFree(trainerId: UUID, start: Instant, blockedUntil: Instant, ignoreSessionId: UUID?): Boolean {
        val busy = Sessions.selectAll().where {
            (Sessions.trainerId eq trainerId) and (Sessions.status inList listOf("CONFIRMED", "COMPLETED", "NO_SHOW")) and
                (Sessions.startsAt less blockedUntil.toOffset()) and (Sessions.blockedUntil greater start.toOffset())
        }.any { it[Sessions.id] != ignoreSessionId }
        if (busy) return false
        return blocks(trainerId, start, blockedUntil).isEmpty()
    }
}
