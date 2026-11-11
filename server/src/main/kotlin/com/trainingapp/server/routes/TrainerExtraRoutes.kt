package com.trainingapp.server.routes

import com.trainingapp.server.Services
import io.ktor.server.routing.Route

/** The remaining trainer modules; each builds its own services from the shared infrastructure. */
fun Route.trainerExtraRoutes(services: Services) {
    with(services) {
        clientsCatalogueRoutes(db, clock, callers, audit, config)
        trainingRoutes(db, clock, callers, audit)
        moneyChatMediaRoutes(db, clock, callers, audit, config)
    }
}
