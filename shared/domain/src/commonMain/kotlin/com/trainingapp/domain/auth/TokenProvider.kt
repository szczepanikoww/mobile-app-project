package com.trainingapp.domain.auth

/**
 * The signed-in account's bearer token for our API (docs/plans/firebase-auth.md §5). Both HTTP clients (client/guest
 * API and trainer API) take their token from here, so there is one source of truth for who is signed in.
 *
 * - `dev` mode: `dev.<subject>` (the local server's development tokens).
 * - Firebase modes: the Firebase ID token, refreshed shortly before it expires.
 */
fun interface TokenProvider {
    /**
     * The current token, or null when nobody is signed in. [forceRefresh] asks for a new token after the server
     * rejected the current one (`401`). May throw when a needed refresh cannot reach the identity provider; callers
     * treat that as offline.
     */
    suspend fun token(forceRefresh: Boolean): String?

    companion object {
        /** A guest: no token. */
        val None = TokenProvider { null }

        /** The local server's development token for [subject] (`Bearer dev.<subject>`); null when [subject] is null. */
        fun dev(subject: () -> String?) = TokenProvider { subject()?.let { "dev.$it" } }
    }
}
