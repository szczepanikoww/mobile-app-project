package com.trainingapp.data.auth

import com.trainingapp.data.remote.ApiOfflineException
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Tokens of one Firebase sign-in. [expiresAtMillis] is when [idToken] expires (epoch milliseconds). */
data class FirebaseTokens(
    val uid: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val email: String? = null,
    /** From Google sign-in; prefills the account completion form. */
    val displayName: String? = null,
) {
    override fun toString() = "FirebaseTokens(uid=***, idToken=***, refreshToken=***, expiresAtMillis=$expiresAtMillis)"
}

/**
 * Firebase rejected the request. [code] is Firebase's error code (`EMAIL_EXISTS`, `INVALID_LOGIN_CREDENTIALS`,
 * `TOKEN_EXPIRED`, ...), never shown to the user.
 */
class FirebaseAuthException(val code: String) : Exception(code) {
    /** The refresh token can no longer be used: the session is over (docs/plans/firebase-auth.md §6.1). */
    val endsSession: Boolean get() = code in SessionEndingCodes

    companion object {
        val SessionEndingCodes = setOf("TOKEN_EXPIRED", "USER_NOT_FOUND", "USER_DISABLED", "INVALID_REFRESH_TOKEN", "INVALID_ID_TOKEN")
    }
}

/**
 * The Firebase Auth REST API (Identity Toolkit + Secure Token), docs/plans/firebase-auth.md §6.1. Works the same on
 * Android and desktop. Passwords and tokens are never logged.
 *
 * Throws [FirebaseAuthException] for Firebase's errors and [ApiOfflineException] when Firebase cannot be reached.
 */
class FirebaseAuthApi(
    private val config: FirebaseConfig,
    engine: HttpClientEngine = CIO.create(),
    private val nowMillis: () -> Long,
) {
    // encodeDefaults: `returnSecureToken` and `requestUri` are defaults and must be sent. Without
    // `returnSecureToken` the real Firebase answers a password sign-in without a refresh token.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val http = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
    }

    val googleWebClientId: String? get() = config.googleWebClientId

    suspend fun signUp(email: String, password: String): FirebaseTokens =
        identity("accounts:signUp", PasswordRequest(email, password)).tokens()

    suspend fun signIn(email: String, password: String): FirebaseTokens =
        identity("accounts:signInWithPassword", PasswordRequest(email, password)).tokens()

    /** Exchanges a Google ID token (from Credential Manager) for a Firebase session. */
    suspend fun signInWithGoogle(googleIdToken: String): FirebaseTokens =
        identity("accounts:signInWithIdp", IdpRequest(postBody = "id_token=$googleIdToken&providerId=google.com")).tokens()

    /** A new ID token for [refreshToken]. Firebase may rotate the refresh token too. */
    suspend fun refresh(refreshToken: String): FirebaseTokens {
        val response = send {
            http.post("${config.secureTokenUrl}/token?key=${config.apiKey}") {
                setBody(FormDataContent(Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                }))
            }
        }
        val body = json.decodeFromString(RefreshResponse.serializer(), response.bodyAsText())
        return FirebaseTokens(
            uid = body.userId,
            idToken = body.idToken,
            refreshToken = body.refreshToken,
            expiresAtMillis = expiresAt(body.expiresIn),
        )
    }

    private suspend fun identity(method: String, body: Any): SignInResponse {
        val text = when (body) {
            is PasswordRequest -> json.encodeToString(PasswordRequest.serializer(), body)
            is IdpRequest -> json.encodeToString(IdpRequest.serializer(), body)
            else -> error("unsupported request")
        }
        val response = send {
            http.post("${config.identityToolkitUrl}/$method?key=${config.apiKey}") {
                contentType(ContentType.Application.Json)
                setBody(text)
            }
        }
        return json.decodeFromString(SignInResponse.serializer(), response.bodyAsText())
    }

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse {
        val response = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiOfflineException(e)
        }
        if (response.status.isSuccess()) return response
        throw FirebaseAuthException(errorCode(response.bodyAsText()) ?: "HTTP_${response.status.value}")
    }

    /** `{"error":{"message":"WEAK_PASSWORD : Password should be ..."}}` → `WEAK_PASSWORD`. */
    private fun errorCode(text: String): String? = runCatching {
        val error = json.parseToJsonElement(text).jsonObject["error"]
        val message = when (error) {
            is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
            else -> error?.jsonPrimitive?.contentOrNull // the Secure Token API: {"error":"invalid_grant"}
        }
        message?.substringBefore(' ')?.substringBefore(':')?.trim()?.uppercase()
    }.getOrNull()

    private fun expiresAt(expiresIn: String?): Long = nowMillis() + (expiresIn?.toLongOrNull() ?: 3600L) * 1000

    private fun SignInResponse.tokens() = FirebaseTokens(
        uid = localId,
        idToken = idToken,
        refreshToken = refreshToken,
        expiresAtMillis = expiresAt(expiresIn),
        email = email,
        displayName = displayName ?: fullName,
    )

    @Serializable
    private data class PasswordRequest(val email: String, val password: String, val returnSecureToken: Boolean = true)

    @Serializable
    private data class IdpRequest(
        val postBody: String,
        val requestUri: String = "http://localhost",
        val returnSecureToken: Boolean = true,
        val returnIdpCredential: Boolean = true,
    )

    @Serializable
    private data class SignInResponse(
        val localId: String,
        val idToken: String,
        val refreshToken: String,
        val expiresIn: String? = null,
        val email: String? = null,
        val displayName: String? = null,
        val fullName: String? = null,
    )

    @Serializable
    private data class RefreshResponse(
        @SerialName("user_id") val userId: String,
        @SerialName("id_token") val idToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: String? = null,
    )
}
