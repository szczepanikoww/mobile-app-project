package com.trainingapp.server.http

import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.ErrorCode
import com.trainingapp.server.identity.CurrentUser
import com.trainingapp.server.identity.IdentityService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.util.AttributeKey
import kotlin.uuid.Uuid

const val AUTH_API = "api"

private val CurrentUserKey = AttributeKey<CurrentUser>("CurrentUser")

/** Identity comes from the token, never from the path (plan §4). */
fun Application.installAuthentication(verifier: TokenVerifier) {
    install(Authentication) {
        bearer(AUTH_API) {
            authenticate { credential -> verifier.verify(credential.token) }
        }
    }
}

fun ApplicationCall.token(): TokenIdentity =
    principal<TokenIdentity>() ?: throw ApiException(401, ErrorCode.UNAUTHENTICATED, "missing token")

/** The signed-in user. `404 NO_ACCOUNT` when the IdP user has not finished registration; `401` when deleted. */
suspend fun ApplicationCall.currentUser(identity: IdentityService): CurrentUser {
    attributes.getOrNull(CurrentUserKey)?.let { return it }
    val user = identity.resolve(token()) ?: throw ApiException(404, ErrorCode.NO_ACCOUNT, "no account for this identity")
    attributes.put(CurrentUserKey, user)
    return user
}

/** `/v1/client/...` requires the caller's `client_profile` row. */
suspend fun ApplicationCall.currentClient(identity: IdentityService): CurrentUser {
    val user = currentUser(identity)
    if (!user.isClient) throw ApiException(403, ErrorCode.FORBIDDEN, "client role required")
    return user
}

fun ApplicationCall.callerUserIdOrNull(): Uuid? = attributes.getOrNull(CurrentUserKey)?.id
