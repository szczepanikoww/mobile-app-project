package com.trainingapp.server.auth

import org.jetbrains.exposed.v1.core.and
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.trainingapp.server.platform.ApiException
import com.trainingapp.server.platform.AuthConfig
import com.trainingapp.server.platform.ErrorCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

const val AUTH = "user-jwt"

/**
 * Bearer JWT verification. In dev mode the server signs its own HS256 tokens; production verifies the chosen
 * identity provider's tokens instead (only this file changes). The token's `sub` is `identity.app_user.auth_subject`;
 * roles and relationships are never taken from the token, always from our database.
 */
class TokenService(private val config: AuthConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    val verifier = JWT.require(algorithm).withIssuer(config.jwtIssuer).withAudience(config.jwtAudience).build()

    fun issue(subject: String, validFor: Duration = 12.hours): String = JWT.create()
        .withIssuer(config.jwtIssuer)
        .withAudience(config.jwtAudience)
        .withSubject(subject)
        .withExpiresAt(Date(System.currentTimeMillis() + validFor.inWholeMilliseconds))
        .sign(algorithm)
}

fun AuthenticationConfig.trainerJwt(tokens: TokenService) {
    jwt(AUTH) {
        verifier(tokens.verifier)
        validate { credential -> credential.payload.subject?.let { JWTPrincipal(credential.payload) } }
        challenge { _, _ -> throw ApiException(ErrorCode.UNAUTHENTICATED, "Sign in required") }
    }
}

/** The IdP subject of the signed-in user. */
fun ApplicationCall.subject(): String =
    principal<JWTPrincipal>()?.payload?.subject ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Sign in required")
