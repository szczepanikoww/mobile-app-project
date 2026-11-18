package com.trainingapp.data.auth

/**
 * What survives an app restart for a signed-in user (docs/plans/firebase-auth.md §6.3): the Firebase tokens and the
 * last known session (an opaque string the auth repository writes), so the app can open in the role shell, even
 * offline, before the server confirms the account again.
 */
data class StoredAuth(
    val uid: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val session: String,
) {
    override fun toString() = "StoredAuth(uid=***, idToken=***, refreshToken=***, expiresAtMillis=$expiresAtMillis)"

    companion object {
        fun of(tokens: FirebaseTokens, session: String) =
            StoredAuth(tokens.uid, tokens.idToken, tokens.refreshToken, tokens.expiresAtMillis, session)
    }
}

/**
 * Platform storage for [StoredAuth]. Android encrypts it with a key held in the Android Keystore; desktop keeps it in
 * memory. Calls are short and synchronous so the session can be restored before the first screen is chosen.
 * Implementations never log the content.
 */
interface TokenStore {
    fun read(): StoredAuth?
    fun write(auth: StoredAuth)
    fun clear()
}

/** Forgets everything when the process ends: desktop, tests and previews. */
class InMemoryTokenStore(private var value: StoredAuth? = null) : TokenStore {
    override fun read(): StoredAuth? = value
    override fun write(auth: StoredAuth) { value = auth }
    override fun clear() { value = null }
}

/**
 * The fields of [StoredAuth] as one string, for stores that keep a single value. Fields are base64-free and joined by
 * a newline, which none of them can contain (JWTs, refresh tokens and the session JSON are single-line).
 */
object StoredAuthCodec {
    private const val VERSION = "v1"

    fun encode(auth: StoredAuth): String =
        listOf(VERSION, auth.uid, auth.idToken, auth.refreshToken, auth.expiresAtMillis.toString(), auth.session).joinToString("\n")

    /** Null for anything unreadable (an older format, a corrupted value): the user simply signs in again. */
    fun decode(text: String): StoredAuth? {
        val parts = text.split('\n', limit = 6)
        if (parts.size != 6 || parts[0] != VERSION) return null
        val expires = parts[4].toLongOrNull() ?: return null
        if (parts[1].isBlank() || parts[3].isBlank()) return null
        return StoredAuth(parts[1], parts[2], parts[3], expires, parts[5])
    }
}
