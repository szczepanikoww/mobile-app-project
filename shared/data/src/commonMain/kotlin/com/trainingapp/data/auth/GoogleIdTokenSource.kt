package com.trainingapp.data.auth

/** The outcome of asking the platform for a Google ID token (docs/plans/firebase-auth.md §9). */
sealed interface GoogleSignInResult {
    data class Token(val idToken: String) : GoogleSignInResult {
        override fun toString() = "Token(***)"
    }

    /** The user closed the account picker. */
    data object Cancelled : GoogleSignInResult

    /** No Google account, no Play services, or Google sign-in is not configured for this build. */
    data object Unavailable : GoogleSignInResult

    /** The picker could not reach Google. */
    data object Offline : GoogleSignInResult
}

/**
 * Gets a Google ID token for the Firebase project's web client id. Android implements it with Credential Manager;
 * platforms without it have none, and the app answers "not available".
 */
fun interface GoogleIdTokenSource {
    suspend fun requestIdToken(webClientId: String): GoogleSignInResult
}
