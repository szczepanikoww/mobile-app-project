package com.trainingapp.data.auth

/**
 * How the app signs users in (`trainer.auth`, docs/plans/firebase-auth.md §8). It only matters with a server
 * ([com.trainingapp.data.remote.BackendConfig.isRemote]); the demo build always uses the demo sign-in.
 */
sealed interface IdentityConfig {
    /** The local server's development tokens (`Bearer dev.<subject>`); no password is checked. */
    data object Dev : IdentityConfig

    /** Firebase Authentication: the real Firebase project. */
    data class Firebase(val firebase: FirebaseConfig) : IdentityConfig

    companion object {
        /**
         * `dev` (default) or `firebase` (needs [apiKey]). An unusable combination falls back to [Dev], so a missing key never breaks
         * the build; [describeProblem] says why.
         */
        fun parse(mode: String?, apiKey: String?, googleWebClientId: String?): IdentityConfig {
            val clientId = googleWebClientId?.takeIf { it.isNotBlank() }
            return when (mode?.trim()?.lowercase()) {
                "firebase" -> apiKey?.takeIf { it.isNotBlank() }?.let { Firebase(FirebaseConfig(apiKey = it, googleWebClientId = clientId)) } ?: Dev
                else -> Dev
            }
        }

        fun describeProblem(mode: String?, apiKey: String?): String? =
            if (mode?.trim()?.lowercase() == "firebase" && apiKey.isNullOrBlank()) {
                "trainer.auth=firebase needs firebase.apiKey in local.properties; using dev sign-in"
            } else {
                null
            }
    }
}

/**
 * Where the Firebase Auth REST API lives. [apiKey] is the project's Web API key: it identifies the project and is not
 * a secret, but it is kept out of the repository (local.properties). [googleWebClientId] enables Google sign-in.
 */
data class FirebaseConfig(
    val apiKey: String,
    val identityToolkitUrl: String = "https://identitytoolkit.googleapis.com/v1",
    val secureTokenUrl: String = "https://securetoken.googleapis.com/v1",
    val googleWebClientId: String? = null,
) {
    override fun toString() = "FirebaseConfig(identityToolkitUrl=$identityToolkitUrl)"
}
