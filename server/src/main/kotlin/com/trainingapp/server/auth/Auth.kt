package com.trainingapp.server.auth

import com.trainingapp.server.http.TokenIdentity
import com.trainingapp.server.http.TokenVerifier
import com.trainingapp.server.platform.ApiException
import com.trainingapp.server.platform.ErrorCode
import com.trainingapp.server.platform.respondTrainerError
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal

const val AUTH = "trainer-bearer"

/**
 * Bearer token verification for the trainer API (`/api/v1`). It uses the same [TokenVerifier] as the client API
 * (`AUTH_MODE`: `dev.<subject>` locally, Firebase ID tokens otherwise; docs/plans/firebase-auth.md §4). The token's
 * `sub` is `identity.app_user.auth_subject`; roles and relationships are never taken from the token, always from our
 * database. A missing or rejected token answers with the trainer API's error body (`401 UNAUTHENTICATED`).
 */
fun AuthenticationConfig.trainerBearer(verifier: TokenVerifier) {
    provider(AUTH) {
        authenticate { context ->
            val header = runCatching { context.call.request.parseAuthorizationHeader() }.getOrNull()
            val token = (header as? HttpAuthHeader.Single)?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }?.blob
            val identity = token?.let(verifier::verify)
            if (identity != null) {
                context.principal(identity)
            } else {
                val cause = if (token == null) AuthenticationFailedCause.NoCredentials else AuthenticationFailedCause.InvalidCredentials
                context.challenge(AUTH, cause) { challenge, call ->
                    call.respondTrainerError(ApiException(ErrorCode.UNAUTHENTICATED, "Sign in required"))
                    challenge.complete()
                }
            }
        }
    }
}

/** The IdP subject of the signed-in user. */
fun ApplicationCall.subject(): String =
    principal<TokenIdentity>()?.subject ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Sign in required")
