package com.trainingapp.feature.guest.data

import com.trainingapp.contract.account.CreateAccountRequest
import com.trainingapp.contract.account.MeDto
import com.trainingapp.contract.account.RoleDto
import com.trainingapp.contract.api.ApiErrorCodes
import com.trainingapp.contract.text.SearchText
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.data.remote.ApiHttpException
import com.trainingapp.data.remote.ApiOfflineException
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthResult
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.Registration
import com.trainingapp.feature.guest.model.Role
import com.trainingapp.feature.guest.model.StartingRole
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Sign-in against the backend while no identity provider exists: the server accepts `Bearer dev.<subject>`. The
 * session's [AuthSession.userId] is that subject (not the server's UUID), because the rest of the app derives its
 * tokens from it. No password is checked or stored (the dev token has none); it is only validated by the screens.
 *
 * [apiFor] builds a client whose token is read from the lambda, so sign-in can use the subject being signed in before
 * a session exists.
 */
class RemoteAuthRepository(
    private val apiFor: (token: () -> String?) -> ApiClient,
    initialSession: AuthSession? = null,
    private val termsVersion: String = DEFAULT_DOCUMENT_VERSION,
    private val privacyVersion: String = DEFAULT_DOCUMENT_VERSION,
) : AuthRepository {

    private val state = MutableStateFlow(initialSession)
    override val session: StateFlow<AuthSession?> = state.asStateFlow()

    private var subject: String? = initialSession?.userId
    private val api: ApiClient = apiFor { subject?.let { "dev.$it" } }

    override suspend fun logIn(email: String, password: String): AuthResult {
        val identity = identityFor(normalize(email))
        val me = try {
            fetchMe(identity.subject) ?: return AuthResult.InvalidCredentials
        } catch (e: ApiOfflineException) {
            return AuthResult.Offline
        } catch (e: ApiHttpException) {
            return AuthResult.Unavailable
        }
        return AuthResult.Success(open(identity.subject, me, identity.activeRole))
    }

    override suspend fun register(request: Registration): AuthResult {
        val identity = identityFor(normalize(request.email))
        val role = if (request.role == StartingRole.Trainer) RoleDto.TRAINER else RoleDto.CLIENT
        val me = try {
            // The server's account creation is idempotent (it returns the existing account), so existence is checked first.
            if (fetchMe(identity.subject) != null) return AuthResult.AccountExists
            val body = CreateAccountRequest(
                operationId = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
                name = request.name.trim(),
                startingRole = role,
                acceptedTermsVersion = request.acceptedTermsVersion.ifBlank { termsVersion },
                acceptedPrivacyVersion = privacyVersion,
            )
            val created = withSubject(identity.subject) {
                api.send(
                    method = HttpMethod.Post,
                    path = "/v1/accounts",
                    body = body,
                    bodySerializer = CreateAccountRequest.serializer(),
                    response = MeDto.serializer(),
                )
            }
            created ?: return AuthResult.Unavailable
        } catch (e: ApiOfflineException) {
            return AuthResult.Offline
        } catch (e: ApiHttpException) {
            return if (e.status == 409) AuthResult.AccountExists else AuthResult.Unavailable
        }
        val active = if (role == RoleDto.TRAINER) Role.Trainer else Role.Client
        return AuthResult.Success(open(identity.subject, me, active))
    }

    override suspend fun continueWithGoogle(): AuthResult = AuthResult.NotSupported

    override suspend fun logOut() {
        subject = null
        state.value = null
    }

    override suspend fun switchRole(role: Role): AuthSession? {
        val current = state.value ?: return null
        if (role !in current.roles) return null
        return current.copy(activeRole = role).also { state.value = it }
    }

    /** `GET /v1/me` for [forSubject]; null when no account exists for it. */
    private suspend fun fetchMe(forSubject: String): MeDto? = try {
        withSubject(forSubject) { api.get("/v1/me", MeDto.serializer()) }
    } catch (e: ApiHttpException) {
        if (e.status == 404 || e.code == ApiErrorCodes.NO_ACCOUNT) null else throw e
    }

    /** Runs [block] with the token of [forSubject]; afterwards only an open session keeps its own subject. */
    private suspend fun <T> withSubject(forSubject: String, block: suspend () -> T): T {
        subject = forSubject
        return try {
            block()
        } finally {
            subject = state.value?.userId
        }
    }

    private fun open(forSubject: String, me: MeDto, preferred: Role?): AuthSession {
        val roles = me.roles.map { if (it == RoleDto.TRAINER) Role.Trainer else Role.Client }.toSet()
        val active = preferred?.takeIf { it in roles } ?: roles.firstOrNull() ?: Role.Client
        val session = AuthSession(forSubject, me.displayName, roles, active)
        subject = forSubject
        state.value = session
        return session
    }

    private class Identity(val subject: String, val activeRole: Role?)

    private fun identityFor(email: String): Identity = when (email) {
        DemoAccounts.TRAINER_EMAIL -> Identity(DemoAccounts.DEMO_TRAINER_ID, Role.Trainer)
        DemoAccounts.CLIENT_EMAIL -> Identity("demo-client", Role.Client)
        DemoAccounts.BOTH_ROLES_EMAIL -> Identity(DemoAccounts.DEMO_TRAINER_ID, Role.Client)
        else -> Identity(subjectFor(email), null)
    }

    private fun normalize(email: String) = email.trim().lowercase()

    companion object {
        const val DEFAULT_DOCUMENT_VERSION = "2026-01"

        /** `u-` + 32 hex characters of two FNV-1a hashes of the normalized e-mail: stable and token68-safe. */
        fun subjectFor(normalizedEmail: String): String {
            val text = SearchText.normalize(normalizedEmail)
            fun fnv(seed: ULong): ULong {
                var h = seed
                for (b in text.encodeToByteArray()) {
                    h = (h xor (b.toULong() and 0xffUL)) * 0x100000001b3UL
                }
                return h
            }
            val a = fnv(0xcbf29ce484222325UL).toString(16).padStart(16, '0')
            val b = fnv(0x84222325cbf29ce4UL).toString(16).padStart(16, '0')
            return "u-$a$b"
        }
    }
}
