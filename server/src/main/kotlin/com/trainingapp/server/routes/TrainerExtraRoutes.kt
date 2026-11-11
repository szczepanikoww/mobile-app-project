package com.trainingapp.server.routes

import com.trainingapp.server.Services
import io.ktor.server.routing.Route

/** Registration point for the remaining trainer modules; each adds its routes here. */
fun Route.trainerExtraRoutes(services: Services) {
    @Suppress("UNUSED_VARIABLE") val unused = services
}
