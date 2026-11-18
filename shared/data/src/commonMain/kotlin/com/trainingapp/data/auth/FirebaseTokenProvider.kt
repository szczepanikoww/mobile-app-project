package com.trainingapp.data.auth

import com.trainingapp.domain.auth.TokenProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Firebase session behind both API clients (docs/plans/firebase-auth.md §5–§6). It hands out the current ID token
 * and refreshes it shortly before it expires or after the server rejected it. Concurrent callers share one refresh.
 *
 * Tokens are written to [store] only once the account exists on our server ([persist]); a half-finished
 * registration lives in memory only, so a restart never opens a shell for an account that does not exist.
 * A refresh token Firebase no longer accepts ends the session: the store is cleared and [onSessionEnded] runs.
 */
class FirebaseTokenProvider(
    private val api: FirebaseAuthApi,
    private val store: TokenStore,
    private val nowMillis: () -> Long,
) : TokenProvider {
    private val lock = Mutex()
    private var tokens: FirebaseTokens? = null
    private var session: String? = null

    /** Called when Firebase ends the session (revoked or expired refresh token, deleted user). */
    var onSessionEnded: (() -> Unit)? = null

    /** The signed-in Firebase user, or null. */
    val uid: String? get() = tokens?.uid

    /** The Firebase account's e-mail and name from the last sign-in (account completion prefill). */
    val email: String? get() = tokens?.email
    val displayName: String? get() = tokens?.displayName

    /** Loads a stored session. Returns the stored session string, or null when there is none. */
    fun restore(): String? {
        val stored = runCatching { store.read() }.getOrNull() ?: return null
        tokens = FirebaseTokens(stored.uid, stored.idToken, stored.refreshToken, stored.expiresAtMillis)
        session = stored.session
        return stored.session
    }

    /** A fresh Firebase sign-in. Not persisted until [persist]. */
    fun begin(signedIn: FirebaseTokens) {
        tokens = signedIn
        session = null
    }

    /** The account exists on our server: keep the tokens and [sessionValue] across restarts. */
    fun persist(sessionValue: String) {
        val current = tokens ?: return
        session = sessionValue
        store.write(StoredAuth.of(current, sessionValue))
    }

    /** Signs out locally: Firebase's REST API has no server-side sign-out; the refresh token is forgotten. */
    fun clear() {
        tokens = null
        session = null
        store.clear()
    }

    override suspend fun token(forceRefresh: Boolean): String? = lock.withLock {
        val current = tokens ?: return@withLock null
        if (!forceRefresh && current.expiresAtMillis - nowMillis() > REFRESH_MARGIN_MILLIS) return@withLock current.idToken
        val fresh = try {
            api.refresh(current.refreshToken)
        } catch (e: FirebaseAuthException) {
            if (!e.endsSession) throw e
            clear()
            onSessionEnded?.invoke()
            return@withLock null
        }
        // Keep the profile fields; a refresh only returns tokens. A different user would be a Firebase bug: end it.
        if (fresh.uid != current.uid) {
            clear()
            onSessionEnded?.invoke()
            return@withLock null
        }
        val next = fresh.copy(email = current.email, displayName = current.displayName)
        // Only if nobody signed out or in while the refresh was running.
        if (tokens === current) {
            tokens = next
            session?.let { store.write(StoredAuth.of(next, it)) }
        }
        next.idToken
    }

    companion object {
        /** Refresh this long before expiry, so a request never starts with a token about to expire. */
        const val REFRESH_MARGIN_MILLIS = 5 * 60 * 1000L
    }
}
