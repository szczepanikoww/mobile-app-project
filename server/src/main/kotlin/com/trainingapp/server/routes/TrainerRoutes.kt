package com.trainingapp.server.routes

import com.trainingapp.contract.api.AcceptRequest
import com.trainingapp.contract.api.AvailabilityDto
import com.trainingapp.contract.api.CreateBlockRequest
import com.trainingapp.contract.api.CreateSessionRequest
import com.trainingapp.contract.api.ProposeTimeRequest
import com.trainingapp.contract.api.RevisionRequest
import com.trainingapp.server.Services
import com.trainingapp.server.auth.AUTH
import com.trainingapp.server.auth.trainer
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.toUuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID
import kotlin.time.Instant

/** Path parameter as a UUID; a malformed id is INVALID_INPUT. */
fun ApplicationCall.uuidParam(name: String): UUID = (parameters[name] ?: invalid("Missing $name")).toUuid(name)

fun ApplicationCall.instantQuery(name: String): Instant? =
    request.queryParameters[name]?.let { runCatching { Instant.parse(it) }.getOrElse { invalid("Invalid $name") } }

/**
 * `/api/v1/trainer/...`: every route requires a signed-in user with the trainer role. Relationship checks happen in
 * the services, inside their transactions.
 */
fun Route.trainerRoutes(services: Services) {
    route("/api/v1/trainer") {
        authenticate(AUTH) {
            suspend fun ApplicationCall.trainerId(): UUID = trainer(services.callers).userId

            // ---------------- calendar
            get("/dashboard") {
                call.respond(services.calendar.dashboard(call.trainerId(), call.instantQuery("from"), call.instantQuery("to")))
            }
            post("/blocks") {
                call.respond(HttpStatusCode.Created, services.calendar.addBlock(call.trainerId(), call.receive<CreateBlockRequest>()))
            }
            delete("/blocks/{id}") {
                services.calendar.removeBlock(call.trainerId(), call.uuidParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/availability") { call.respond(services.calendar.availability(call.trainerId())) }
            put("/availability") {
                call.respond(services.calendar.saveAvailability(call.trainerId(), call.receive<AvailabilityDto>()))
            }

            // ---------------- sessions
            post("/sessions") {
                call.respond(HttpStatusCode.Created, services.sessions.create(call.trainerId(), call.receive<CreateSessionRequest>()))
            }
            route("/sessions/{id}") {
                get("/check-in") { call.respond(services.sessions.checkIn(call.trainerId(), call.uuidParam("id"))) }
                get("/review") { call.respond(services.sessions.requestReview(call.trainerId(), call.uuidParam("id"))) }
                post("/accept") {
                    call.respond(services.sessions.accept(call.trainerId(), call.uuidParam("id"), call.receive<AcceptRequest>()))
                }
                post("/reject") {
                    call.respond(services.sessions.reject(call.trainerId(), call.uuidParam("id"), call.receive<RevisionRequest>()))
                }
                post("/propose") {
                    call.respond(services.sessions.propose(call.trainerId(), call.uuidParam("id"), call.receive<ProposeTimeRequest>()))
                }
                post("/cancel") {
                    call.respond(services.sessions.cancel(call.trainerId(), call.uuidParam("id"), call.receive<RevisionRequest>()))
                }
                post("/complete") {
                    call.respond(services.sessions.complete(call.trainerId(), call.uuidParam("id"), call.receive<RevisionRequest>()))
                }
                post("/no-show") {
                    call.respond(services.sessions.noShow(call.trainerId(), call.uuidParam("id"), call.receive<RevisionRequest>()))
                }
            }

            // Further trainer modules register here (policy, clients, offers, plans, finance, chat, media).
            trainerExtraRoutes(services)
        }
    }
}
