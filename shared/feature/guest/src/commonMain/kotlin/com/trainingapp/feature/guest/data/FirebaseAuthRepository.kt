package com.trainingapp.feature.guest.data

import com.trainingapp.contract.account.CreateAccountRequest
import com.trainingapp.contract.account.MeDto
import com.trainingapp.contract.account.RoleDto
import com.trainingapp.contract.api.ApiErrorCodes
import com.trainingapp.data.auth.FirebaseAuthApi
import com.trainingapp.data.auth.FirebaseAuthException
import com.trainingapp.data.auth.FirebaseTokenProvider
import com.trainingapp.data.auth.FirebaseTokens
import com.trainingapp.data.auth.GoogleIdTokenSource
import com.trainingapp.data.auth.GoogleSignInResult
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Sign-in with Firebase Authentication (docs/plans/firebase-auth.md §6.2). Firebase checks the password or the Google
 * account; our server knows the account (`GET /v1/me`, `POST /v1/accounts`) and decides roles. [api] must take its
 * token from [tokens].
 *
 * The session's [AuthSession.userId] is the Firebase UID (the server's `auth_subject`), as the dev sign-in used the
 * subject. The session is restored from [tokens]' store on creation, so the app can open in the role shell directly.
 */
class FirebaseAuthRepository(
    private val firebase: FirebaseAuthApi,
    private val tokens: FirebaseTokenProvider,
    private val api: ApiClient,
    private val google: GoogleIdTokenSource? = null,
    private val privacyVersion: String = RemoteAuthRepository.DEFAULT_DOCUMENT_VERSION,
    private val newOperationId: () -> String = { Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } },
) : AuthRepository {

    private val state = MutableStateFlow(restore())
    override val session: StateFlow<AuthSession?> = state.asStateFlow()

    init {
        tokens.onSessionEnded = { state.value = null }
    }

    override suspend fun logIn(email: String, password: String): AuthResult {
        val signedIn = identity { firebase.signIn(normalize(email), password) }.getOrElse { return it.toResult() }
        return openAccount(signedIn, preferred = null)
    }

    override suspend fun register(request: Registration): AuthResult {
        val email = normalize(request.email)
        val signedIn = try {
            firebase.signUp(email, request.password)
        } catch (e: FirebaseAuthException) {
            if (e.code != "EMAIL_EXISTS") return e.toResult()
            // An earlier registration may have stopped after Firebase created the user: with the same password,
            // finish that one (one account, docs/plans/backend-client-guest.md S1). Otherwise the e-mail is taken.
            val existing = identity { firebase.signIn(email, request.password) }.getOrElse {
                return if (it is ApiOfflineException) AuthResult.Offline else AuthResult.AccountExists
            }
            tokens.begin(existing)
            val me = try {
                fetchMe()
            } catch (e: Exception) {
                tokens.clear()
                return e.toResult()
            }
            if (me != null) {
                tokens.clear()
                return AuthResult.AccountExists
            }
            existing
        } catch (e: ApiOfflineException) {
            return AuthResult.Offline
        }
        tokens.begin(signedIn)
        return createAccount(request.name.trim(), request.role, request.acceptedTermsVersion)
    }

    override suspend fun continueWithGoogle(): AuthResult {
        val source = google ?: return AuthResult.NotSupported
        val clientId = firebase.googleWebClientId ?: return AuthResult.NotSupported
        val googleToken = when (val result = source.requestIdToken(clientId)) {
            is GoogleSignInResult.Token -> result.idToken
            GoogleSignInResult.Cancelled -> return AuthResult.Cancelled
            GoogleSignInResult.Unavailable -> return AuthResult.NotSupported
            GoogleSignInResult.Offline -> return AuthResult.Offline
        }
        val signedIn = identity { firebase.signInWithGoogle(googleToken) }.getOrElse { return it.toResult() }
        return openAccount(signedIn, preferred = null)
    }

    override suspend fun completeAccount(name: String, role: StartingRole, acceptedTermsVersion: String): AuthResult {
        if (tokens.uid == null) return AuthResult.Unavailable
        return createAccount(name.trim(), role, acceptedTermsVersion)
    }

    override suspend fun cancelAccountCompletion() {
        if (state.value == null) tokens.clear()
    }

    override suspend fun logOut() {
        tokens.clear()
        state.value = null
    }

    override suspend fun switchRole(role: Role): AuthSession? {
        val current = state.value ?: return null
        if (role !in current.roles) return null
        return current.copy(activeRole = role).also { open(it) }
    }

    /**
     * After a restore: asks the server for the account again, so roles and name are current. A missing account or a
     * rejected identity signs out; no connection keeps the stored session (it works offline).
     */
    suspend fun revalidate() {
        val current = state.value ?: return
        val me = try {
            fetchMe()
        } catch (e: ApiOfflineException) {
            return
        } catch (e: ApiHttpException) {
            if (e.status == 401) logOut()
            return
        }
        if (me == null) {
            logOut()
            return
        }
        // The session may have ended or changed while the call was running.
        if (state.value?.userId == current.userId) open(sessionOf(me, current.activeRole))
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Signed in at Firebase: open the account on our server, or ask the user to finish it. */
    private suspend fun openAccount(signedIn: FirebaseTokens, preferred: Role?): AuthResult {
        tokens.begin(signedIn)
        val me = try {
            fetchMe()
        } catch (e: Exception) {
            tokens.clear()
            return e.toResult()
        }
        if (me == null) return AuthResult.NeedsAccount(signedIn.email, signedIn.displayName)
        return AuthResult.Success(open(sessionOf(me, preferred)))
    }

    private suspend fun createAccount(name: String, role: StartingRole, termsVersion: String): AuthResult {
        val roleDto = if (role == StartingRole.Trainer) RoleDto.TRAINER else RoleDto.CLIENT
        val body = CreateAccountRequest(
            operationId = newOperationId(),
            name = name,
            startingRole = roleDto,
            acceptedTermsVersion = termsVersion.ifBlank { RemoteAuthRepository.DEFAULT_DOCUMENT_VERSION },
            acceptedPrivacyVersion = privacyVersion,
        )
        val created = try {
            api.send(
                method = HttpMethod.Post,
                path = "/v1/accounts",
                body = body,
                bodySerializer = CreateAccountRequest.serializer(),
                response = MeDto.serializer(),
            )
        } catch (e: ApiOfflineException) {
            // Firebase has the user, our server does not: the next log in or registration finishes it. In memory only.
            return AuthResult.Offline
        } catch (e: ApiHttpException) {
            return if (e.status == 409) AuthResult.AccountExists else AuthResult.Unavailable
        } ?: return AuthResult.Unavailable
        val active = if (roleDto == RoleDto.TRAINER) Role.Trainer else Role.Client
        return AuthResult.Success(open(sessionOf(created, active)))
    }

    /** `GET /v1/me`; null when the Firebase user has no account here yet. */
    private suspend fun fetchMe(): MeDto? = try {
        api.get("/v1/me", MeDto.serializer())
    } catch (e: ApiHttpException) {
        if (e.status == 404 || e.code == ApiErrorCodes.NO_ACCOUNT) null else throw e
    }

    private fun sessionOf(me: MeDto, preferred: Role?): AuthSession {
        val roles = me.roles.map { if (it == RoleDto.TRAINER) Role.Trainer else Role.Client }.toSet()
        val active = preferred?.takeIf { it in roles } ?: roles.firstOrNull() ?: Role.Client
        return AuthSession(tokens.uid.orEmpty(), me.displayName, roles, active)
    }

    private fun open(session: AuthSession): AuthSession {
        tokens.persist(encode(session))
        state.value = session
        return session
    }

    private fun restore(): AuthSession? {
        val stored = tokens.restore() ?: return null
        val session = decode(stored)
        // Unreadable, or saved for another user: start signed out.
        if (session == null || session.userId != tokens.uid) {
            tokens.clear()
            return null
        }
        return session
    }

    private inline fun <T> identity(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun normalize(email: String) = email.trim().lowercase()

    @Serializable
    private data class StoredSession(val userId: String, val displayName: String, val roles: List<String>, val activeRole: String)

    private fun encode(session: AuthSession): String = SessionJson.encodeToString(
        StoredSession.serializer(),
        StoredSession(session.userId, session.displayName, session.roles.map { it.name }, session.activeRole.name),
    )

    private fun decode(text: String): AuthSession? = runCatching {
        val stored = SessionJson.decodeFromString(StoredSession.serializer(), text)
        val roles = stored.roles.mapNotNull { name -> Role.entries.firstOrNull { it.name == name } }.toSet()
        val active = Role.entries.firstOrNull { it.name == stored.activeRole }?.takeIf { it in roles } ?: return null
        AuthSession(stored.userId, stored.displayName, roles, active)
    }.getOrNull()

    companion object {
        private val SessionJson = Json { ignoreUnknownKeys = true }

        /**
         * Firebase's error codes as the screen's results (docs/plans/firebase-auth.md §6.1). Wrong e-mail and wrong
         * password are one result, so the message never reveals which one.
         */
        fun resultFor(code: String): AuthResult = when (code) {
            "EMAIL_EXISTS" -> AuthResult.AccountExists
            "INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "INVALID_PASSWORD", "USER_DISABLED", "INVALID_EMAIL",
            "INVALID_IDP_RESPONSE",
            -> AuthResult.InvalidCredentials
            "TOO_MANY_ATTEMPTS_TRY_LATER" -> AuthResult.TooManyAttempts
            else -> AuthResult.Unavailable
        }

        private fun Throwable.toResult(): AuthResult = when (this) {
            is FirebaseAuthException -> resultFor(code)
            is ApiOfflineException -> AuthResult.Offline
            else -> AuthResult.Unavailable
        }
    }
}
