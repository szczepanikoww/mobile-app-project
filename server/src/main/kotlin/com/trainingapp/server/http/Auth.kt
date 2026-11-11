package com.trainingapp.server.http

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.trainingapp.server.config.AuthConfig
import com.trainingapp.server.config.AuthMode
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Who the identity provider says the caller is. The API never sees a password (plan §6). */
data class TokenIdentity(val subject: String, val email: String?)

/** Verifies a bearer token. Returns null for an invalid or expired token (→ `401`). */
fun interface TokenVerifier {
    fun verify(token: String): TokenIdentity?

    companion object {
        fun from(config: AuthConfig): TokenVerifier = when (config.mode) {
            AuthMode.DEV -> DevTokenVerifier
            AuthMode.FIREBASE -> FirebaseTokenVerifier(config.firebaseProjectId)
            AuthMode.FIREBASE_EMULATOR -> EmulatorTokenVerifier(config.firebaseProjectId)
        }
    }
}

/**
 * Local development and tests: `Bearer dev.<subject>` (token68 characters only, as the Bearer scheme requires).
 * The seeded demo accounts use their demo ids as subjects (`dev.demo-client`). Dev tokens carry no e-mail. Never
 * enabled outside the local profile.
 */
object DevTokenVerifier : TokenVerifier {
    private val subjectPattern = Regex("[A-Za-z0-9_-]{1,128}")

    override fun verify(token: String): TokenIdentity? {
        if (!token.startsWith("dev.")) return null
        val subject = token.removePrefix("dev.")
        return if (subjectPattern.matches(subject)) TokenIdentity(subject, null) else null
    }
}

/** Firebase Auth ID tokens: RS256, issuer `https://securetoken.google.com/<project>`, audience = project id. */
class FirebaseTokenVerifier(private val projectId: String) : TokenVerifier {
    private val jwks = JwkProviderBuilder(URI("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com").toURL())
        .cached(10, 6, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    override fun verify(token: String): TokenIdentity? = runCatching {
        val decoded = JWT.decode(token)
        val key = jwks.get(decoded.keyId).publicKey as RSAPublicKey
        val verified = JWT.require(Algorithm.RSA256(key, null))
            .withIssuer("https://securetoken.google.com/$projectId")
            .withAudience(projectId)
            .acceptLeeway(30)
            .build()
            .verify(token)
        TokenIdentity(verified.subject, verified.getClaim("email").asString())
    }.getOrNull()
}

/** The Firebase Auth emulator issues unsigned tokens; only the claims are checked. Local profile only. */
class EmulatorTokenVerifier(private val projectId: String) : TokenVerifier {
    override fun verify(token: String): TokenIdentity? = runCatching {
        val parts = token.split('.')
        val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))) as JsonObject
        val aud = claims["aud"]?.jsonPrimitive?.content
        val exp = claims["exp"]?.jsonPrimitive?.content?.toLong() ?: 0
        if (aud != projectId || exp * 1000 < System.currentTimeMillis()) return null
        TokenIdentity(claims["sub"]!!.jsonPrimitive.content, claims["email"]?.jsonPrimitive?.content)
    }.getOrNull()
}
