package com.trainingapp.data.remote

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/**
 * The HTTP engine for the backend and Firebase clients. CIO by default (desktop, tests). Android passes OkHttp
 * instead: CIO's own TLS calls the trust manager without the host name, which Android rejects whenever the app has a
 * domain-specific network security config (the debug build's `10.0.2.2` exception), so every HTTPS call failed.
 */
object HttpEngines {
    fun default(): HttpClientEngine = CIO.create()
}
