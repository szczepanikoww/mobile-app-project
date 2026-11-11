package pl.trainer.data.remote.client

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The HTTP calls the client repositories need. `:shared` does not depend on `:shared:data`, so this mirrors the
 * relevant part of `com.trainingapp.data.remote.ApiClient` (same parameters) and the app wires a small adapter:
 * `get` and `send` delegate to the ApiClient, `ApiHttpException(status, code)` becomes [ClientApiHttpException] and
 * `ApiOfflineException` becomes [ClientApiOfflineException].
 */
interface ClientApi {
    suspend fun <T> get(path: String, serializer: KSerializer<T>, query: List<Pair<String, String>> = emptyList()): T

    /** A call with an optional JSON [body]; null when the answer has no body (`204`). [operationId] is the `Idempotency-Key`. */
    suspend fun <B, T> send(
        method: HttpMethod,
        path: String,
        body: B? = null,
        bodySerializer: KSerializer<B>? = null,
        response: KSerializer<T>? = null,
        operationId: String? = null,
    ): T?
}

/** The server answered with an error body; [code] is the `ApiErrorCodes` value (or `HTTP_<status>`). */
class ClientApiHttpException(val status: Int, val code: String) : Exception("$status $code")

/** The server could not be reached. The call is never treated as successful. */
class ClientApiOfflineException(cause: Throwable? = null) : Exception("offline", cause)

internal enum class Op { Read, Write, Cancel }

/** Maps transport errors to the app's failures. Server codes are mapped, messages are never shown. */
internal fun Throwable.toTrainerException(op: Op): TrainerException {
    val write = op != Op.Read
    val failure = when (this) {
        is TrainerException -> return this
        is CancellationException -> throw this
        is ClientApiOfflineException -> Failure.Offline
        is ClientApiHttpException -> when (code) {
            "QUOTE_EXPIRED" -> Failure.QuoteExpired
            // A cancellation confirmed with a quote for another revision (or an unknown quote) needs a fresh quote.
            "REVISION_CONFLICT" -> if (op == Op.Cancel) Failure.QuoteExpired else Failure.Conflict
            "VALIDATION" -> if (op == Op.Cancel) Failure.QuoteExpired else Failure.InvalidInput
            "SLOT_TAKEN", "POLICY_OUTDATED", "CONFLICT", "IDEMPOTENCY_MISMATCH" -> Failure.Conflict
            "UNAUTHENTICATED", "NO_ACCOUNT", "FORBIDDEN" -> Failure.Denied
            // Not yours or removed: a write acts on stale data, a read cannot load it.
            "NOT_FOUND" -> if (write) Failure.Conflict else Failure.Load
            else -> when (status) {
                401, 403 -> Failure.Denied
                409 -> Failure.Conflict
                410 -> if (op == Op.Cancel) Failure.QuoteExpired else Failure.Conflict
                422 -> Failure.InvalidInput
                else -> if (write) Failure.Save else Failure.Load
            }
        }
        else -> if (write) Failure.Save else Failure.Load
    }
    return TrainerException(failure)
}

@OptIn(ExperimentalUuidApi::class)
internal fun newOperationId(): String = Uuid.random().toString()

/** A stable UUID for [key]: retrying the same user action sends the same operation id, so the server dedupes it. */
@OptIn(ExperimentalUuidApi::class)
internal fun operationIdFor(key: String): String {
    runCatching { Uuid.parse(key) }.getOrNull()?.let { return it.toString() }
    // Two 64-bit FNV-1a hashes with different seeds form 128 bits; no secrecy is needed, only stability.
    fun fnv(seed: Long): Long {
        var h = seed
        for (c in key) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h
    }
    return Uuid.fromLongs(fnv(-3750763034362895579L), fnv(7809847782465536322L)).toString()
}
