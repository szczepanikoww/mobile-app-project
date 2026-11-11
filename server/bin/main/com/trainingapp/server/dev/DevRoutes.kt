package com.trainingapp.server.dev

import com.trainingapp.contract.api.IsoInstant
import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.server.Services
import com.trainingapp.server.common.Ids
import com.trainingapp.server.common.dbNow
import com.trainingapp.server.common.invalid
import com.trainingapp.server.common.notFound
import com.trainingapp.server.http.pathUuid
import com.trainingapp.server.scheduling.Sessions
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

@Serializable
data class DevProposeRequest(val start: IsoInstant, val end: IsoInstant)

@Serializable
data class DevCashRequest(val relationshipId: String, val amount: MoneyDto)

@Serializable
data class DevAssignPlanRequest(val relationshipId: String, val planVersionId: String, val sessionId: String? = null)

@Serializable
data class DevPackageRequest(val relationshipId: String, val offerId: String)

@Serializable
data class DevAcceptRequest(val chargeFee: Boolean = false)

/**
 * `/v1/dev/...` — the trainer transitions the client flow needs (plan §8.7), until the trainer API exists. Local
 * profile only. Each route acts as the session's trainer and runs the same service code as the real flow, so the
 * constraints, events and jobs are exercised; nothing here writes SQL directly.
 */
fun Route.devRoutes(s: Services) = route("/v1/dev") {
    suspend fun ApplicationCall.trainerOf(sessionId: Uuid): Uuid =
        s.db.tx { Sessions.selectAll().where { Sessions.id eq sessionId }.singleOrNull()?.get(Sessions.trainerId) } ?: throw notFound("session")

    post("/sessions/{id}/confirm") { val id = call.pathUuid("id"); call.respond(s.scheduling.confirm(call.trainerOf(id), id)) }
    post("/sessions/{id}/reject") { val id = call.pathUuid("id"); call.respond(s.scheduling.reject(call.trainerOf(id), id)) }
    post("/sessions/{id}/propose") {
        val id = call.pathUuid("id")
        val body = call.receive<DevProposeRequest>()
        call.respond(s.scheduling.propose(call.trainerOf(id), id, body.start, body.end, Ids.v7()))
    }
    post("/sessions/{id}/accept-reschedule") {
        val id = call.pathUuid("id")
        call.respond(s.scheduling.acceptClientReschedule(call.trainerOf(id), id, call.receive<DevAcceptRequest>().chargeFee))
    }
    post("/sessions/{id}/trainer-cancel") { val id = call.pathUuid("id"); call.respond(s.scheduling.cancelByTrainer(call.trainerOf(id), id, Ids.v7())) }
    post("/sessions/{id}/check-in") { val id = call.pathUuid("id"); call.respond(s.scheduling.checkIn(call.trainerOf(id), id)) }
    post("/sessions/{id}/no-show") { val id = call.pathUuid("id"); call.respond(s.scheduling.noShow(call.trainerOf(id), id)) }

    post("/cash") {
        val body = call.receive<DevCashRequest>()
        val relId = runCatching { Uuid.parse(body.relationshipId) }.getOrNull() ?: throw invalid("relationshipId")
        val trainerId = s.db.tx { with(s.identity) { relationship(relId) }?.trainerId } ?: throw notFound("relationship")
        call.respond(HttpStatusCode.Created, mapOf("paymentId" to s.billing.recordCash(trainerId, relId, body.amount, Ids.v7()).toString()))
    }
    post("/assign-plan") {
        val body = call.receive<DevAssignPlanRequest>()
        val relId = runCatching { Uuid.parse(body.relationshipId) }.getOrNull() ?: throw invalid("relationshipId")
        val trainerId = s.db.tx { with(s.identity) { relationship(relId) }?.trainerId } ?: throw notFound("relationship")
        val id = s.training.assignPlan(trainerId, relId, Uuid.parse(body.planVersionId), body.sessionId?.let(Uuid::parse), Ids.v7())
        call.respond(HttpStatusCode.Created, mapOf("assignedPlanId" to id.toString()))
    }
    post("/packages") {
        val body = call.receive<DevPackageRequest>()
        val id = s.db.tx { with(s.billing) { grantPackage(Uuid.parse(body.relationshipId), Uuid.parse(body.offerId), Ids.v7(), dbNow()) } }
        call.respond(HttpStatusCode.Created, mapOf("agreementId" to id.toString()))
    }
    post("/worker/tick") {
        // Runs the outbox and due jobs once, for manual testing without waiting for the loop.
        call.respond(mapOf("outbox" to s.worker.drainOutbox(), "jobs" to s.worker.runDueJobs()))
    }
}
