package com.trainingapp.app.remote

import com.trainingapp.data.remote.ApiClient
import com.trainingapp.data.remote.ApiHttpException
import com.trainingapp.data.remote.ApiOfflineException
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import pl.trainer.data.remote.client.ClientApi
import pl.trainer.data.remote.client.ClientApiHttpException
import pl.trainer.data.remote.client.ClientApiOfflineException

/** The client API (`/v1`) for the `:shared` client repositories, which cannot see `:shared:data`. */
class ApiClientAdapter(private val api: ApiClient) : ClientApi {
    override suspend fun <T> get(path: String, serializer: KSerializer<T>, query: List<Pair<String, String>>): T =
        wrap { api.get(path, serializer, query) }

    override suspend fun <B, T> send(
        method: HttpMethod,
        path: String,
        body: B?,
        bodySerializer: KSerializer<B>?,
        response: KSerializer<T>?,
        operationId: String?,
    ): T? = wrap { api.send(method, path, body, bodySerializer, response, operationId) }

    private suspend fun <R> wrap(block: suspend () -> R): R = try {
        block()
    } catch (e: ApiHttpException) {
        throw ClientApiHttpException(e.status, e.code)
    } catch (e: ApiOfflineException) {
        throw ClientApiOfflineException(e)
    }
}
