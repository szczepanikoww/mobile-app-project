package com.trainingapp.server.http

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.trainingapp.server.config.AuthConfig
import com.trainingapp.server.config.AuthMode
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** Who the identity provider says the caller is. The API never sees a password (plan §6). */
data class TokenIdentity(val subject: String, val email: String?, val picture: String? = null)

/** Verifies a bearer token. Returns null for an invalid or expired token (→ `401`). */
fun interface TokenVerifier {
    fun verify(token: String): TokenIdentity?

    companion object {
        fun from(config: AuthConfig): TokenVerifier = when (config.mode) {
            AuthMode.DEV -> DevTokenVerifier
            AuthMode.FIREBASE -> FirebaseTokenVerifier(config.firebaseProjectId)
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

/**
 * Firebase Auth ID tokens: RS256, issuer `https://securetoken.google.com/<project>`, audience = project id, signed by one
 * of Google's rotating keys. [publicKey] resolves a key id; the default fetches and caches Google's JWKS (tests pass a
 * local key).
 */
class FirebaseTokenVerifier(
    private val projectId: String,
    private val publicKey: (keyId: String?) -> RSAPublicKey = googleKeys(),
) : TokenVerifier {

    override fun verify(token: String): TokenIdentity? = runCatching {
        val decoded = JWT.decode(token)
        val verified = JWT.require(Algorithm.RSA256(publicKey(decoded.keyId), null))
            .withIssuer("https://securetoken.google.com/$projectId")
            .withAudience(projectId)
            .acceptLeeway(30)
            .build()
            .verify(token)
        // Firebase also guarantees a non-empty `sub` of at most 128 characters; reject anything else.
        val subject = verified.subject?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        // `picture`: the Google account photo, copied once as the profile photo (ProfilePhotoService).
        TokenIdentity(subject, verified.getClaim("email").asString(), verified.getClaim("picture").asString())
    }.getOrNull()

    companion object {
        private const val GOOGLE_JWKS = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"

        fun googleKeys(): (String?) -> RSAPublicKey {
            val jwks = JwkProviderBuilder(URI(GOOGLE_JWKS).toURL())
                .cached(10, 6, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build()
            return { keyId -> jwks.get(keyId).publicKey as RSAPublicKey }
        }
    }
}

