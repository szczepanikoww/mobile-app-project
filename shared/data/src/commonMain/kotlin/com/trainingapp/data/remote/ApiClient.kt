package com.trainingapp.data.remote

import com.trainingapp.contract.api.ApiErrorDto
import com.trainingapp.contract.api.IDEMPOTENCY_KEY_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
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
 * [token] is the signed-in account's bearer token (until an identity provider exists: `dev.<subject>`, decision
 * TB2); null for a guest. It is attached to every call, and public endpoints ignore it.
 */
class ApiClient(
    baseUrl: String,
    engine: HttpClientEngine = CIO.create(),
    private val language: () -> String = { "pl" },
    private val token: () -> String? = { null },
) {
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
                token()?.let { bearerAuth(it) }
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

    /**
     * A changing call: [method] [path] with an optional JSON [body]; the answer is decoded with [response] (null when
     * the answer has no body). `Idempotency-Key` is sent when [operationId] is given.
     */
    suspend fun <B, T> send(
        method: HttpMethod,
        path: String,
        body: B? = null,
        bodySerializer: KSerializer<B>? = null,
        response: KSerializer<T>? = null,
        operationId: String? = null,
    ): T? {
        val answer = call {
            http.request(path.trimStart('/')) {
                this.method = method
                header(HttpHeaders.AcceptLanguage, language())
                token()?.let { bearerAuth(it) }
                operationId?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
                if (body != null && bodySerializer != null) {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(bodySerializer, body))
                }
            }
        }
        if (response == null) return null
        val text = answer.bodyAsText()
        return if (text.isBlank()) null else json.decodeFromString(response, text)
    }

    /** A call with full control of the request (media uploads); errors are mapped as for [get]. */
    suspend fun raw(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse = call {
        http.request(path.trimStart('/')) {
            this.method = method
            token()?.let { bearerAuth(it) }
            configure(this)
        }
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
