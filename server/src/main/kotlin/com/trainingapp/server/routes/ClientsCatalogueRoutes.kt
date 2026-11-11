package com.trainingapp.server.routes

import com.trainingapp.contract.api.ClientPolicyOverrideDto
import com.trainingapp.contract.api.CreateInviteRequest
import com.trainingapp.contract.api.SaveNoteRequest
import com.trainingapp.contract.api.SaveOfferRequest
import com.trainingapp.contract.api.SavePolicyRequest
import com.trainingapp.contract.api.SaveTrainerProfileRequest
import com.trainingapp.server.auth.AuditLog
import com.trainingapp.server.auth.CallerResolver
import com.trainingapp.server.auth.trainer
import com.trainingapp.server.catalogue.TrainerCatalogue
import com.trainingapp.server.identity.TrainerClientsService
import com.trainingapp.server.platform.AppConfig
import com.trainingapp.server.platform.DataCipher
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.util.UUID

/**
 * Policy editor, price list, profile, clients, notes, client information and invites. Registered relative to the
 * current route (`/api/v1/trainer`, already authenticated).
 */
fun Route.clientsCatalogueRoutes(db: Db, clock: ServerClock, callers: CallerResolver, audit: AuditLog, config: AppConfig) {
    val catalogue = TrainerCatalogue(db, clock)
    val clients = TrainerClientsService(db, clock, audit, DataCipher(config.dataKeyId, config.dataKey))

    suspend fun ApplicationCall.trainerId(): UUID = trainer(callers).userId

    // ---------------- policy
    get("/policy") { call.respond(catalogue.policyEditor(call.trainerId())) }
    get("/policy/versions/{id}") { call.respond(catalogue.policyVersion(call.trainerId(), call.uuidParam("id"))) }
    post("/policy") { call.respond(HttpStatusCode.Created, catalogue.savePolicy(call.trainerId(), call.receive<SavePolicyRequest>())) }
    put("/clients/{id}/policy-override") {
        clients.setPolicyOverride(call.trainerId(), call.uuidParam("id"), call.receive<ClientPolicyOverrideDto>())
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/clients/{id}/policy-override") {
        clients.clearPolicyOverride(call.trainerId(), call.uuidParam("id"))
        call.respond(HttpStatusCode.NoContent)
    }

    // ---------------- offers and profile
    get("/offers") { call.respond(catalogue.offers(call.trainerId())) }
    post("/offers") { call.respond(HttpStatusCode.Created, catalogue.createOffer(call.trainerId(), call.receive<SaveOfferRequest>())) }
    post("/offers/{id}/archive") {
        catalogue.archiveOffer(call.trainerId(), call.uuidParam("id"))
        call.respond(HttpStatusCode.NoContent)
    }
    get("/profile") { call.respond(catalogue.profile(call.trainerId())) }
    put("/profile") { call.respond(catalogue.saveProfile(call.trainerId(), call.receive<SaveTrainerProfileRequest>())) }
    get("/gyms") {
        call.trainerId()
        call.respond(catalogue.gyms())
    }

    // ---------------- clients
    get("/clients") { call.respond(clients.clients(call.trainerId())) }
    get("/clients/{id}") { call.respond(clients.detail(call.trainerId(), call.uuidParam("id"))) }
    post("/clients/{id}/end") {
        clients.endRelationship(call.trainerId(), call.uuidParam("id"))
        call.respond(HttpStatusCode.NoContent)
    }

    // ---------------- notes
    get("/clients/{id}/notes") { call.respond(clients.notes(call.trainerId(), call.uuidParam("id"))) }
    post("/clients/{id}/notes") {
        call.respond(HttpStatusCode.Created, clients.addNote(call.trainerId(), call.uuidParam("id"), call.receive<SaveNoteRequest>().body))
    }
    put("/clients/{id}/notes/{noteId}") {
        call.respond(clients.updateNote(call.trainerId(), call.uuidParam("id"), call.uuidParam("noteId"), call.receive<SaveNoteRequest>().body))
    }
    delete("/clients/{id}/notes/{noteId}") {
        clients.deleteNote(call.trainerId(), call.uuidParam("id"), call.uuidParam("noteId"))
        call.respond(HttpStatusCode.NoContent)
    }
    put("/clients/{id}/note") {
        val note = clients.saveSingleNote(call.trainerId(), call.uuidParam("id"), call.receive<SaveNoteRequest>().body)
        if (note == null) call.respond(HttpStatusCode.NoContent) else call.respond(note)
    }

    // ---------------- client information
    get("/clients/{id}/information") { call.respond(clients.information(call.trainerId(), call.uuidParam("id"))) }

    // ---------------- invites
    get("/invites") { call.respond(clients.invites(call.trainerId())) }
    post("/invites") { call.respond(HttpStatusCode.Created, clients.createInvite(call.trainerId(), call.receive<CreateInviteRequest>())) }
    delete("/invites/{code}") {
        val code = call.parameters["code"].orEmpty()
        clients.revokeInvite(call.trainerId(), code)
        call.respond(HttpStatusCode.NoContent)
    }
}
