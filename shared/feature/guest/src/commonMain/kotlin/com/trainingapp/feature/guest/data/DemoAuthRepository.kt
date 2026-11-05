package com.trainingapp.feature.guest.data

import com.trainingapp.contract.text.SearchText
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthResult
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.Registration
import com.trainingapp.feature.guest.model.Role
import com.trainingapp.feature.guest.model.StartingRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory demo sign-in until an identity provider is chosen. It stores no passwords: the reserved
 * `@example.com` demo accounts (listed in the README, never in the UI) accept any password of valid length, and
 * registrations only remember the e-mail so "account already exists" can be shown.
 */
class DemoAuthRepository(
    private val network: NetworkMonitor,
    initialSession: AuthSession? = null,
    private val latencyMs: Long = 600,
) : AuthRepository {

    private val state = MutableStateFlow(initialSession)
    override val session: StateFlow<AuthSession?> = state.asStateFlow()

    private val registeredEmails = mutableSetOf<String>()

    /** Lets tests and previews simulate an unavailable service. */
    var unavailable: Boolean = false

    var requestCount: Int = 0
        private set

    private suspend fun guard(): AuthResult? {
        requestCount++
        if (latencyMs > 0) delay(latencyMs)
        if (!network.isOnline.value) return AuthResult.Offline
        if (unavailable) return AuthResult.Unavailable
        return null
    }

    override suspend fun logIn(email: String, password: String): AuthResult {
        guard()?.let { return it }
        val key = email.trim().lowercase()
        val demo = DemoAccounts.byEmail[key]
        val session = when {
            demo != null -> demo
            key in registeredEmails -> AuthSession("user-${key.hashCode()}", key.substringBefore('@'), setOf(Role.Client), Role.Client)
            else -> return AuthResult.InvalidCredentials
        }
        state.value = session
        return AuthResult.Success(session)
    }

    override suspend fun register(request: Registration): AuthResult {
        guard()?.let { return it }
        val key = request.email.trim().lowercase()
        if (key in registeredEmails || key in DemoAccounts.byEmail) return AuthResult.AccountExists
        registeredEmails += key
        val role = if (request.role == StartingRole.Trainer) Role.Trainer else Role.Client
        val session = AuthSession(
            userId = if (role == Role.Trainer) DemoAccounts.DEMO_TRAINER_ID else "user-${SearchText.normalize(key).hashCode()}",
            displayName = request.name.trim(),
            roles = setOf(role),
            activeRole = role,
        )
        state.value = session
        return AuthResult.Success(session)
    }

    override suspend fun continueWithGoogle(): AuthResult {
        guard()?.let { return it }
        return AuthResult.NotSupported
    }

    override suspend fun logOut() {
        state.value = null
    }

    override suspend fun switchRole(role: Role): AuthSession? {
        val current = state.value ?: return null
        if (role !in current.roles) return null
        return current.copy(activeRole = role).also { state.value = it }
    }
}

/** Reserved demo accounts. The trainer one maps to the existing demo trainer data. */
object DemoAccounts {
    const val DEMO_TRAINER_ID = "demo-trainer"
    const val TRAINER_EMAIL = "trener@example.com"
    const val CLIENT_EMAIL = "klient@example.com"

    /** Holds both roles, so the client shell offers "Przełącz na trenera". */
    const val BOTH_ROLES_EMAIL = "trener-klient@example.com"

    val trainer = AuthSession(DEMO_TRAINER_ID, "Trener demo", setOf(Role.Trainer), Role.Trainer)

    /** The demo client is Katarzyna ("Kasia") Nowak, relationship "c1" of the demo trainer's calendar. */
    val client = AuthSession("demo-client", "Katarzyna Nowak", setOf(Role.Client), Role.Client)
    val bothRoles = AuthSession(DEMO_TRAINER_ID, "Michał Kowalczyk", setOf(Role.Trainer, Role.Client), Role.Client)

    val byEmail = mapOf(TRAINER_EMAIL to trainer, CLIENT_EMAIL to client, BOTH_ROLES_EMAIL to bothRoles)
}
