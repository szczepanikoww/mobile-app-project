package com.trainingapp.feature.guest.model

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

enum class Role { Trainer, Client }

/** The profile chosen at registration ("Chcę trenować" / "Jestem trenerem"). A user may later hold both roles. */
enum class StartingRole { Client, Trainer }

data class AuthSession(val userId: String, val displayName: String, val roles: Set<Role>, val activeRole: Role)

/** Registration request. The password is redacted in [toString], so it never reaches logs. */
data class Registration(
    val name: String,
    val email: String,
    val password: String,
    val role: StartingRole,
    val acceptedTermsVersion: String,
) {
    override fun toString() = "Registration(name=***, email=***, password=***, role=$role, acceptedTermsVersion=$acceptedTermsVersion)"
}

sealed interface AuthResult {
    data class Success(val session: AuthSession) : AuthResult
    /** Wrong e-mail or password. The message never reveals which one. */
    data object InvalidCredentials : AuthResult
    data object AccountExists : AuthResult
    /** Not sent: the device is offline. Never counts as success. */
    data object Offline : AuthResult
    data object Unavailable : AuthResult
    /** The sign-in method is not connected yet (Google sign-in, D10). */
    data object NotSupported : AuthResult
}

/**
 * What a guest was trying to do when sign-in was required. It travels through sign-in to the role shell, where the
 * booking request screen will consume it. It never creates a request by itself: a request needs an explicit slot.
 */
@Serializable
sealed interface GuestIntent {
    @Serializable
    data class RequestSession(val trainerId: String? = null, val gymId: String? = null) : GuestIntent
}

interface GuestCatalogRepository {
    suspend fun home(): GuestHome
    suspend fun trainers(query: TrainerQuery): List<TrainerSummary>
    suspend fun filterOptions(): FilterOptions
    /** Null when the profile is not public (or no longer exists). */
    suspend fun trainer(id: String): TrainerProfile?
    suspend fun gyms(query: GymQuery): List<GymSummary>
    suspend fun gym(id: String): GymDetail?
}

interface AuthRepository {
    val session: StateFlow<AuthSession?>
    suspend fun logIn(email: String, password: String): AuthResult
    suspend fun register(request: Registration): AuthResult
    suspend fun continueWithGoogle(): AuthResult
    suspend fun logOut()

    /**
     * Makes [role] the session's active role when the session holds it. Returns the updated session, or null when
     * the role is not held (or switching is not supported).
     */
    suspend fun switchRole(role: Role): AuthSession? = null
}

/** Raised by the catalogue when the server cannot be reached; the screens keep the last loaded data. */
class CatalogOfflineException : Exception("offline")
