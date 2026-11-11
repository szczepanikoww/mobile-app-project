package com.trainingapp.data.remote

import com.trainingapp.contract.api.ApiErrorDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Which server the app talks to (`trainer.backend`, docs/plans/backend-client-guest.md §11). [Demo] keeps the
 * in-memory demo sources: previews, tests and the default run use it, so nothing needs a server.
 */
data class BackendConfig(val baseUrl: String?) {
    val isRemote: Boolean get() = baseUrl != null

    companion object {
        val Demo = BackendConfig(null)

        /**
         * `demo` (default), `local` (a server on this machine; [localHost] is `10.0.2.2` on the Android emulator) or a
         * full `http(s)://` base URL.
         */
        fun parse(value: String?, localHost: String = "localhost"): BackendConfig = when {
            value.isNullOrBlank() || value == "demo" -> Demo
            value == "local" -> BackendConfig("http://$localHost:8080")
            value.startsWith("http://") || value.startsWith("https://") -> BackendConfig(value.trimEnd('/'))
            else -> Demo
        }
    }
}

/** The server could not be reached (no connection, timeout). Never treated as success. */
class ApiOfflineException(cause: Throwable) : Exception("offline", cause)

/** The server answered with an error body (`{ code, message }`). Screens map [code], never [message]. */
class ApiHttpException(val status: Int, val code: String, message: String) : Exception("$status $code: $message")

/**
 * A thin HTTP client for the backend: JSON with the `:contract` DTOs, timeouts, and the error mapping of plan §4.
 * Authentication is added by the repositories that need it.
 */
class ApiClient(baseUrl: String, engine: HttpClientEngine = CIO.create(), private val language: () -> String = { "pl" }) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val http = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
        defaultRequest { url(baseUrl.trimEnd('/') + "/") }
    }

    /** `GET` [path] and decode with [serializer]. [query] names may repeat (`district=A&district=B`). */
    suspend fun <T> get(path: String, serializer: KSerializer<T>, query: List<Pair<String, String>> = emptyList()): T {
        val response = call {
            http.get(path.trimStart('/')) {
                header(HttpHeaders.AcceptLanguage, language())
                query.forEach { (name, value) -> url.parameters.append(name, value) }
            }
        }
        return json.decodeFromString(serializer, response.bodyAsText())
    }

    /** Like [get], but a `404` is `null` (a resource that is not public or no longer exists). */
    suspend fun <T> getOrNull(path: String, serializer: KSerializer<T>): T? = try {
        get(path, serializer)
    } catch (e: ApiHttpException) {
        if (e.status == 404) null else throw e
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse {
        val response = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Connection refused, DNS, timeouts: the device is offline or the server is down.
            throw ApiOfflineException(e)
        }
        if (response.status.isSuccess()) return response
        val error = runCatching { json.decodeFromString(ApiErrorDto.serializer(), response.bodyAsText()) }.getOrNull()
        throw ApiHttpException(response.status.value, error?.code ?: "HTTP_${response.status.value}", error?.message ?: "")
    }
}
