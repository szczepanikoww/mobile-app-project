package com.trainingapp.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.trainingapp.server.http.DevTokenVerifier
import com.trainingapp.server.http.FirebaseTokenVerifier
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Token checks without a network: Firebase tokens are signed with a local key the verifier is given. */
class TokenVerifierTest {
    private val project = "demo-trainer"
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val otherKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val verifier = FirebaseTokenVerifier(project) { keyId ->
        require(keyId == "k1") { "unknown key" }
        keys.public as RSAPublicKey
    }

    private fun firebaseToken(
        subject: String? = "uid-123",
        audience: String = project,
        issuer: String = "https://securetoken.google.com/$project",
        expiresInSeconds: Long = 3600,
        signingKey: RSAPrivateKey = keys.private as RSAPrivateKey,
        keyId: String = "k1",
    ): String = JWT.create()
        .withKeyId(keyId)
        .withIssuer(issuer)
        .withAudience(audience)
        .apply { if (subject != null) withSubject(subject) }
        .withClaim("email", "user@example.com")
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + expiresInSeconds * 1000))
        .sign(Algorithm.RSA256(null, signingKey))

    @Test
    fun `a valid Firebase token gives its subject and e-mail`() {
        val identity = verifier.verify(firebaseToken())
        assertEquals("uid-123", identity?.subject)
        assertEquals("user@example.com", identity?.email)
    }

    @Test
    fun `Firebase tokens for another project, issuer, key or after expiry are rejected`() {
        assertNull(verifier.verify(firebaseToken(audience = "other-project")))
        assertNull(verifier.verify(firebaseToken(issuer = "https://securetoken.google.com/other-project")))
        assertNull(verifier.verify(firebaseToken(signingKey = otherKeys.private as RSAPrivateKey)))
        assertNull(verifier.verify(firebaseToken(keyId = "unknown")))
        assertNull(verifier.verify(firebaseToken(expiresInSeconds = -120)))
        assertNull(verifier.verify(firebaseToken(subject = null)))
        assertNull(verifier.verify("dev.demo-trainer"))
        assertNull(verifier.verify("not-a-token"))
    }

    @Test
    fun `dev tokens are accepted only in their own shape`() {
        assertEquals("demo-trainer", DevTokenVerifier.verify("dev.demo-trainer")?.subject)
        assertNull(DevTokenVerifier.verify("demo-trainer"))
        assertNull(DevTokenVerifier.verify("dev.bad subject"))
        assertNull(DevTokenVerifier.verify(firebaseToken()))
    }
}
