package pl.trainer.data.remote

import com.trainingapp.contract.api.ApiError
import com.trainingapp.contract.api.ApiErrorCode
import com.trainingapp.contract.api.DevTokenRequest
import com.trainingapp.contract.api.TokenDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException

/** JSON settings matching the server: unknown fields are ignored so the server can add fields without breaking the app. */
val TrainerApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * The trainer API over HTTP (`/api/v1`). [subject] is the signed-in account; until an identity provider exists the
 * server's development endpoint issues its token (decision TB2). Errors become [TrainerException]s, so view models
 * keep their existing failure handling. Request and response bodies are never logged.
 */
class TrainerApiClient(
    baseUrl: String,
    private val subject: () -> String,
    engine: HttpClient? = null,
) {
    private val http: HttpClient = (engine ?: HttpClient()).config {
        expectSuccess = false
        install(ContentNegotiation) { json(TrainerApiJson) }
        defaultRequest {
            url(baseUrl.trimEnd('/') + "/api/v1/")
            contentType(ContentType.Application.Json)
        }
    }

    private val tokenLock = Mutex()
    private var token: Pair<String, String>? = null // subject to token

    private suspend fun token(forceNew: Boolean): String = tokenLock.withLock {
        val who = subject()
        token?.takeIf { !forceNew && it.first == who }?.let { return it.second }
        val response = send(HttpMethod.Post, "dev/token", write = true) { setBody(DevTokenRequest(who)) }
        if (!response.status.isSuccess()) throw response.failure(write = false)
        response.body<TokenDto>().token.also { token = who to it }
    }

    /** Sends one call with the account's token; a rejected token is renewed once. */
    suspend fun call(method: HttpMethod, path: String, write: Boolean, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val first = token(forceNew = false)
        var response = send(method, path, write) { bearerAuth(first); configure(this) }
        if (response.status == HttpStatusCode.Unauthorized) {
            val renewed = token(forceNew = true)
            response = send(method, path, write) { bearerAuth(renewed); configure(this) }
        }
        if (!response.status.isSuccess()) throw response.failure(write)
        return response
    }

    suspend inline fun <reified T> get(path: String): T = call(HttpMethod.Get, path, write = false).body()

    /** A changing call with a JSON [body]; returns the decoded answer. */
    suspend inline fun <reified B : Any, reified T> send(method: HttpMethod, path: String, body: B): T =
        call(method, path, write = true) { setBody(body) }.body()

    /** A changing call whose answer body is not needed. */
    suspend inline fun <reified B : Any> command(method: HttpMethod, path: String, body: B) {
        call(method, path, write = true) { setBody(body) }
    }

    suspend fun command(method: HttpMethod, path: String) {
        call(method, path, write = true)
    }

    private suspend fun send(method: HttpMethod, path: String, write: Boolean, configure: HttpRequestBuilder.() -> Unit): HttpResponse =
        try {
            http.request(path) { this.method = method; configure(this) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No connection or a timeout: the change was not confirmed by the server.
            throw TrainerException(Failure.Offline)
        }
}

/** Maps the server's error body (`{code, message, details}`) to the app's failures. */
private suspend fun HttpResponse.failure(write: Boolean): TrainerException {
    val code = runCatching { body<ApiError>().code }.getOrNull()?.let { name -> ApiErrorCode.entries.firstOrNull { it.name == name } }
    val failure = when (code) {
        ApiErrorCode.QUOTE_EXPIRED -> Failure.QuoteExpired
        ApiErrorCode.CONFLICT -> Failure.Conflict
        ApiErrorCode.INVALID_INPUT -> Failure.InvalidInput
        ApiErrorCode.UNAUTHENTICATED, ApiErrorCode.DENIED -> Failure.Denied
        // Another trainer's or a removed item: the screen's data is out of date.
        ApiErrorCode.NOT_FOUND -> if (write) Failure.Conflict else Failure.Load
        else -> when (status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> Failure.Denied
            HttpStatusCode.Conflict -> Failure.Conflict
            HttpStatusCode.UnprocessableEntity -> Failure.InvalidInput
            else -> if (write) Failure.Save else Failure.Load
        }
    }
    return TrainerException(failure)
}
