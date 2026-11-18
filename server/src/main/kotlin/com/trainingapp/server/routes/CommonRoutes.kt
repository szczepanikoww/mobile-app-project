package com.trainingapp.server.routes

import com.trainingapp.contract.api.DevTokenRequest
import com.trainingapp.contract.api.MeDto
import com.trainingapp.contract.api.Role
import com.trainingapp.contract.api.TokenDto
import com.trainingapp.server.TrainerServices
import com.trainingapp.server.auth.AUTH
import com.trainingapp.server.auth.caller
import com.trainingapp.server.config.AuthMode
import com.trainingapp.server.platform.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.commonRoutes(services: TrainerServices) {
    get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }

    route("/api/v1") {
        // Local development only: the `dev.<subject>` token of a seeded demo account. Not routed outside AUTH_MODE=dev.
        if (services.config.auth.mode == AuthMode.DEV) {
            post("/dev/token") {
                val request = call.receive<DevTokenRequest>()
                val caller = runCatching { services.callers.resolve(request.subject) }.getOrElse { notFound("Unknown demo account") }
                call.respond(TokenDto("dev.${request.subject}", caller.userId.toString()))
            }
        }

        authenticate(AUTH) {
            get("/me") {
                val caller = call.caller(services.callers)
                call.respond(
                    MeDto(
                        userId = caller.userId.toString(),
                        displayName = caller.displayName,
                        email = caller.email,
                        locale = caller.locale,
                        timeZone = caller.timeZone,
                        roles = buildSet {
                            if (caller.isTrainer) add(Role.TRAINER)
                            if (caller.isClient) add(Role.CLIENT)
                        },
                    ),
                )
            }
        }
    }
}
