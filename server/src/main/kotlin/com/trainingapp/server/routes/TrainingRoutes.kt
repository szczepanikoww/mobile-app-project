package com.trainingapp.server.routes

import com.trainingapp.contract.api.AssignPlanRequest
import com.trainingapp.contract.exercise.UpsertExerciseRequest
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.server.auth.AuditLog
import com.trainingapp.server.auth.CallerResolver
import com.trainingapp.server.auth.trainer
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.training.TrainerTraining
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.util.UUID

/**
 * Exercise library, plans, assignment and client progress. Registered relative to `/api/v1/trainer`, inside
 * `authenticate(AUTH)`.
 */
fun Route.trainingRoutes(db: Db, clock: ServerClock, callers: CallerResolver, audit: AuditLog) {
    val training = TrainerTraining(db, clock, audit)
    suspend fun ApplicationCall.trainerId(): UUID = trainer(callers).userId

    get("/exercises") { call.respond(training.exercises(call.trainerId(), call.instantQuery("updatedSince"))) }
    put("/exercises/{id}") {
        call.respond(training.upsertExercise(call.trainerId(), call.uuidParam("id"), call.receive<UpsertExerciseRequest>()))
    }
    post("/exercises/{id}/hide") { call.respond(training.setExerciseHidden(call.trainerId(), call.uuidParam("id"), true)) }
    post("/exercises/{id}/unhide") { call.respond(training.setExerciseHidden(call.trainerId(), call.uuidParam("id"), false)) }

    get("/plans") { call.respond(training.plans(call.trainerId())) }
    get("/plans/{templateId}/versions/latest") {
        call.respond(training.version(call.trainerId(), call.uuidParam("templateId"), null))
    }
    get("/plans/{templateId}/versions/{number}") {
        val number = call.parameters["number"]?.toIntOrNull()?.takeIf { it > 0 } ?: invalid("Invalid version number")
        call.respond(training.version(call.trainerId(), call.uuidParam("templateId"), number))
    }
    post("/plans") {
        call.respond(training.publish(call.trainerId(), null, call.receive<PublishPlanRequest>()))
    }
    post("/plans/{templateId}/versions") {
        call.respond(training.publish(call.trainerId(), call.uuidParam("templateId"), call.receive<PublishPlanRequest>()))
    }
    post("/plans/{templateId}/archive") { call.respond(training.archive(call.trainerId(), call.uuidParam("templateId"))) }

    post("/clients/{clientId}/assigned-plan") {
        call.respond(HttpStatusCode.Created, training.assign(call.trainerId(), call.uuidParam("clientId"), call.receive<AssignPlanRequest>()))
    }
    get("/clients/{clientId}/assigned-plan") { call.respond(training.activeAssignment(call.trainerId(), call.uuidParam("clientId"))) }
    get("/clients/{clientId}/workouts") {
        val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: invalid("Invalid limit") } ?: 20
        call.respond(training.workouts(call.trainerId(), call.uuidParam("clientId"), limit))
    }
    get("/clients/{clientId}/records") { call.respond(training.records(call.trainerId(), call.uuidParam("clientId"))) }
    get("/clients/{clientId}/measurements") { call.respond(training.measurements(call.trainerId(), call.uuidParam("clientId"))) }
}
