package com.trainingapp.feature.guest

import com.trainingapp.contract.account.CreateAccountRequest
import com.trainingapp.contract.account.MeDto
import com.trainingapp.contract.account.RoleDto
import com.trainingapp.contract.api.ApiErrorDto
import com.trainingapp.data.auth.FirebaseAuthApi
import com.trainingapp.data.auth.FirebaseConfig
import com.trainingapp.data.auth.FirebaseTokenProvider
import com.trainingapp.data.auth.GoogleIdTokenSource
import com.trainingapp.data.auth.GoogleSignInResult
import com.trainingapp.data.auth.IdentityConfig
import com.trainingapp.data.auth.InMemoryTokenStore
import com.trainingapp.data.auth.StoredAuthCodec
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.feature.guest.data.FirebaseAuthRepository
import com.trainingapp.feature.guest.model.AuthResult
import com.trainingapp.feature.guest.model.Registration
import com.trainingapp.feature.guest.model.Role
import com.trainingapp.feature.guest.model.StartingRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Firebase sign-in against a fake Firebase and a fake server in one [MockEngine] (docs/plans/firebase-auth.md §6).
 * Tokens are `id-<uid>-<version>`; the fake server accepts only a user's latest version.
 */
class FirebaseAuthRepositoryTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class User(val uid: String, val email: String, var password: String?, var version: Int = 1, var revoked: Boolean = false)

    private inner class World {
        var now = 1_000_000L
        val users = mutableListOf<User>()
        val accounts = mutableMapOf<String, MeDto>()
        val createdAccounts = mutableListOf<CreateAccountRequest>()
        val serverCalls = mutableListOf<String>()
        var refreshes = 0
        var serverOffline = false
        var firebaseOffline = false
        var throttled = false

        val engine = MockEngine { request -> handle(request) }
        val config = FirebaseConfig(apiKey = "test-key", identityToolkitUrl = "http://firebase.test/identitytoolkit.googleapis.com/v1", secureTokenUrl = "http://firebase.test/securetoken.googleapis.com/v1")
        val store = InMemoryTokenStore()

        fun build(google: GoogleIdTokenSource? = null): Triple<FirebaseAuthRepository, FirebaseTokenProvider, ApiClient> {
            val api = FirebaseAuthApi(config, engine, nowMillis = { now })
            val tokens = FirebaseTokenProvider(api, store, nowMillis = { now })
            val client = ApiClient("http://server.test", engine, tokens = tokens)
            return Triple(FirebaseAuthRepository(api, tokens, client, google, newOperationId = { "op-1" }), tokens, client)
        }

        private fun body(request: HttpRequestData): String = when (val content = request.body) {
            is TextContent -> content.text
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            else -> ""
        }

        private fun MockRequestHandleScope.firebaseError(code: String) =
            respond("""{"error":{"code":400,"message":"$code"}}""", HttpStatusCode.BadRequest, jsonHeaders)

        /** Like the real API: the refresh token only comes back when the request asked for it. */
        private var askedForTokens = true

        private fun MockRequestHandleScope.signedIn(user: User, displayName: String? = null) = respond(
            """{"localId":"${user.uid}","idToken":"id-${user.uid}-${user.version}",""" +
                (if (askedForTokens) """"refreshToken":"rt-${user.uid}",""" else "") +
                """"expiresIn":"3600",""" +
                """"email":"${user.email}"${displayName?.let { ",\"displayName\":\"$it\"" } ?: ""}}""",
            HttpStatusCode.OK, jsonHeaders,
        )

        private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
            val path = request.url.encodedPath
            if (request.url.host == "firebase.test") {
                if (firebaseOffline) throw IllegalStateException("no network")
                if (throttled) return firebaseError("TOO_MANY_ATTEMPTS_TRY_LATER : Access disabled")
                if (path.endsWith("/token")) {
                    refreshes++
                    val refreshToken = body(request).parseUrlEncodedParameters()["refresh_token"]
                    val user = users.firstOrNull { "rt-${it.uid}" == refreshToken } ?: return firebaseError("INVALID_REFRESH_TOKEN")
                    if (user.revoked) return firebaseError("TOKEN_EXPIRED")
                    user.version++
                    return respond(
                        """{"user_id":"${user.uid}","id_token":"id-${user.uid}-${user.version}","refresh_token":"rt-${user.uid}","expires_in":"3600"}""",
                        HttpStatusCode.OK, jsonHeaders,
                    )
                }
                val fields = json.parseToJsonElement(body(request)).jsonObject
                askedForTokens = fields["returnSecureToken"]?.jsonPrimitive?.content == "true"
                return when {
                    path.endsWith("accounts:signUp") -> {
                        val email = fields["email"]!!.jsonPrimitive.content
                        if (users.any { it.email == email }) return firebaseError("EMAIL_EXISTS")
                        val password = fields["password"]!!.jsonPrimitive.content
                        if (password.length < 6) return firebaseError("WEAK_PASSWORD : Password should be at least 6 characters")
                        signedIn(User("uid-${users.size + 1}", email, password).also { users += it })
                    }
                    path.endsWith("accounts:signInWithPassword") -> {
                        val email = fields["email"]!!.jsonPrimitive.content
                        val user = users.firstOrNull { it.email == email && it.password == fields["password"]!!.jsonPrimitive.content }
                            ?: return firebaseError("INVALID_LOGIN_CREDENTIALS")
                        signedIn(user)
                    }
                    path.endsWith("accounts:signInWithIdp") -> {
                        if (!fields["postBody"]!!.jsonPrimitive.content.contains("id_token=google-ok")) return firebaseError("INVALID_IDP_RESPONSE")
                        val user = users.firstOrNull { it.email == "ola@example.com" } ?: User("uid-google", "ola@example.com", null).also { users += it }
                        signedIn(user, displayName = "Ola Google")
                    }
                    else -> error("unexpected Firebase call $path")
                }
            }
            // Our server.
            if (serverOffline) throw IllegalStateException("server down")
            serverCalls += "${request.method.value} $path"
            val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
            val user = users.firstOrNull { bearer == "id-${it.uid}-${it.version}" }
                ?: return respond(json.encodeToString(ApiErrorDto.serializer(), ApiErrorDto("UNAUTHENTICATED", "x")), HttpStatusCode.Unauthorized, jsonHeaders)
            return when {
                request.method == HttpMethod.Get && path == "/v1/me" -> accounts[user.uid]
                    ?.let { respond(json.encodeToString(MeDto.serializer(), it), HttpStatusCode.OK, jsonHeaders) }
                    ?: respond(json.encodeToString(ApiErrorDto.serializer(), ApiErrorDto("NO_ACCOUNT", "x")), HttpStatusCode.NotFound, jsonHeaders)
                request.method == HttpMethod.Post && path == "/v1/accounts" -> {
                    val create = json.decodeFromString(CreateAccountRequest.serializer(), body(request))
                    createdAccounts += create
                    val me = accounts.getOrPut(user.uid) {
                        MeDto("db-${user.uid}", create.name, listOf(create.startingRole), "pl", "Europe/Warsaw")
                    }
                    respond(json.encodeToString(MeDto.serializer(), me), HttpStatusCode.Created, jsonHeaders)
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }

        fun existingAccount(email: String, password: String, vararg roles: RoleDto, name: String = "Anna Test"): User {
            val user = User("uid-${users.size + 1}", email, password).also { users += it }
            accounts[user.uid] = MeDto("db-${user.uid}", name, roles.toList(), "pl", "Europe/Warsaw")
            return user
        }
    }

    private fun registration(email: String = "nowy@example.com", password: String = "haslo1234", role: StartingRole = StartingRole.Client) =
        Registration("Nowa Osoba", email, password, role, "terms-1")

    @Test
    fun `log in opens the server account and stores the session`() = runTest {
        val world = World()
        val user = world.existingAccount("anna@example.com", "haslo1234", RoleDto.TRAINER, RoleDto.CLIENT)
        val (repo) = world.build()

        val session = assertIs<AuthResult.Success>(repo.logIn(" Anna@Example.com ", "haslo1234")).session
        assertEquals(user.uid, session.userId)
        assertEquals(setOf(Role.Trainer, Role.Client), session.roles)
        assertEquals(session, repo.session.value)
        assertNotNull(world.store.read())
    }

    @Test
    fun `wrong password and unknown e-mail give the same result`() = runTest {
        val world = World()
        world.existingAccount("anna@example.com", "haslo1234", RoleDto.CLIENT)
        val (repo) = world.build()
        assertEquals(AuthResult.InvalidCredentials, repo.logIn("anna@example.com", "zle-haslo"))
        assertEquals(AuthResult.InvalidCredentials, repo.logIn("nikt@example.com", "haslo1234"))
        assertNull(repo.session.value)
        assertNull(world.store.read())
    }

    @Test
    fun `firebase errors map to offline and too many attempts`() = runTest {
        val world = World()
        val (repo) = world.build()
        world.firebaseOffline = true
        assertEquals(AuthResult.Offline, repo.logIn("anna@example.com", "haslo1234"))
        world.firebaseOffline = false
        world.throttled = true
        assertEquals(AuthResult.TooManyAttempts, repo.logIn("anna@example.com", "haslo1234"))
    }

    @Test
    fun `register creates the Firebase user and one server account with the consent`() = runTest {
        val world = World()
        val (repo) = world.build()
        val session = assertIs<AuthResult.Success>(repo.register(registration(role = StartingRole.Trainer))).session
        assertEquals(Role.Trainer, session.activeRole)
        val created = world.createdAccounts.single()
        assertEquals("Nowa Osoba", created.name)
        assertEquals(RoleDto.TRAINER, created.startingRole)
        assertEquals("terms-1", created.acceptedTermsVersion)
    }

    @Test
    fun `register with a taken e-mail is account exists`() = runTest {
        val world = World()
        world.existingAccount("anna@example.com", "haslo1234", RoleDto.CLIENT)
        val (repo) = world.build()
        assertEquals(AuthResult.AccountExists, repo.register(registration(email = "anna@example.com")))
        assertEquals(AuthResult.AccountExists, repo.register(registration(email = "anna@example.com", password = "inne-haslo")))
        assertTrue(world.createdAccounts.isEmpty())
        assertNull(repo.session.value)
    }

    @Test
    fun `a registration interrupted after Firebase is finished by retrying, with one account`() = runTest {
        val world = World()
        val (repo, tokens) = world.build()
        world.serverOffline = true
        assertEquals(AuthResult.Offline, repo.register(registration()))
        assertNull(repo.session.value)
        assertNull(world.store.read(), "a half-finished registration is never persisted")
        assertEquals(1, world.users.size)

        world.serverOffline = false
        tokens.clear() // as after a restart
        assertIs<AuthResult.Success>(repo.register(registration()))
        assertEquals(1, world.users.size)
        assertEquals(1, world.accounts.size)
    }

    @Test
    fun `log in without a server account asks to finish it, and completion creates it`() = runTest {
        val world = World()
        world.users += User("uid-9", "pol@example.com", "haslo1234")
        val (repo) = world.build()

        val needs = assertIs<AuthResult.NeedsAccount>(repo.logIn("pol@example.com", "haslo1234"))
        assertEquals("pol@example.com", needs.email)
        assertNull(repo.session.value)
        assertNull(world.store.read())

        val session = assertIs<AuthResult.Success>(repo.completeAccount("Paweł Pol", StartingRole.Client, "terms-1")).session
        assertEquals("uid-9", session.userId)
        assertEquals("Paweł Pol", world.createdAccounts.single().name)
        assertNotNull(world.store.read())
    }

    @Test
    fun `cancelling the completion forgets the Firebase sign-in`() = runTest {
        val world = World()
        world.users += User("uid-9", "pol@example.com", "haslo1234")
        val (repo, tokens) = world.build()
        assertIs<AuthResult.NeedsAccount>(repo.logIn("pol@example.com", "haslo1234"))
        repo.cancelAccountCompletion()
        assertNull(tokens.uid)
        assertEquals(AuthResult.Unavailable, repo.completeAccount("X", StartingRole.Client, "t"))
    }

    @Test
    fun `google sign-in, cancel and missing configuration`() = runTest {
        val world = World()
        // Not configured: no web client id.
        assertEquals(AuthResult.NotSupported, world.build(google = { GoogleSignInResult.Token("google-ok") }).first.continueWithGoogle())

        val api = FirebaseAuthApi(world.config.copy(googleWebClientId = "web-client"), world.engine, nowMillis = { world.now })
        val tokens = FirebaseTokenProvider(api, world.store, nowMillis = { world.now })
        val client = ApiClient("http://server.test", world.engine, tokens = tokens)
        val cancelled = FirebaseAuthRepository(api, tokens, client, google = { GoogleSignInResult.Cancelled })
        assertEquals(AuthResult.Cancelled, cancelled.continueWithGoogle())

        val repo = FirebaseAuthRepository(api, tokens, client, google = { clientId ->
            assertEquals("web-client", clientId)
            GoogleSignInResult.Token("google-ok")
        })
        val needs = assertIs<AuthResult.NeedsAccount>(repo.continueWithGoogle())
        assertEquals("Ola Google", needs.suggestedName)
        assertIs<AuthResult.Success>(repo.completeAccount("Ola Google", StartingRole.Client, "terms-1"))
        repo.logOut()
        assertIs<AuthResult.Success>(repo.continueWithGoogle())
    }

    @Test
    fun `an expiring token is refreshed once for parallel calls`() = runTest {
        val world = World()
        world.existingAccount("anna@example.com", "haslo1234", RoleDto.CLIENT)
        val (repo, _, client) = world.build()
        assertIs<AuthResult.Success>(repo.logIn("anna@example.com", "haslo1234"))

        world.now += 3600_000L - 60_000L // one minute before expiry
        (1..5).map { async { client.get("/v1/me", MeDto.serializer()) } }.awaitAll()
        assertEquals(1, world.refreshes)
        // The new tokens are kept for the next start.
        assertTrue(world.store.read()!!.idToken.endsWith("-2"))
    }

    @Test
    fun `a rejected token is refreshed and the call repeated once`() = runTest {
        val world = World()
        val user = world.existingAccount("anna@example.com", "haslo1234", RoleDto.CLIENT)
        val (repo, _, client) = world.build()
        assertIs<AuthResult.Success>(repo.logIn("anna@example.com", "haslo1234"))
        user.version++ // the server no longer accepts the app's token (e.g. revoked on another device)
        world.serverCalls.clear()

        client.get("/v1/me", MeDto.serializer())
        assertEquals(listOf("GET /v1/me", "GET /v1/me"), world.serverCalls)
        assertEquals(1, world.refreshes)
    }

    @Test
    fun `a revoked refresh token ends the session`() = runTest {
        val world = World()
        val user = world.existingAccount("anna@example.com", "haslo1234", RoleDto.CLIENT)
        val (repo, tokens) = world.build()
        assertIs<AuthResult.Success>(repo.logIn("anna@example.com", "haslo1234"))
        user.revoked = true
        assertNull(tokens.token(forceRefresh = true))
        assertNull(repo.session.value)
        assertNull(world.store.read())
    }

    @Test
    fun `a stored session is restored on start and revalidated with the server`() = runTest {
        val world = World()
        world.existingAccount("anna@example.com", "haslo1234", RoleDto.CLIENT, name = "Anna Test")
        val (first) = world.build()
        assertIs<AuthResult.Success>(first.logIn("anna@example.com", "haslo1234"))

        // A new process: the session is there before any network call.
        val (restored) = world.build()
        assertEquals("Anna Test", restored.session.value?.displayName)

        // The trainer role was added meanwhile; revalidation picks it up.
        val uid = restored.session.value!!.userId
        world.accounts[uid] = world.accounts[uid]!!.copy(roles = listOf(RoleDto.CLIENT, RoleDto.TRAINER))
        restored.revalidate()
        assertEquals(setOf(Role.Client, Role.Trainer), restored.session.value?.roles)

        // Offline keeps the session; a deleted account signs out.
        world.serverOffline = true
        restored.revalidate()
        assertNotNull(restored.session.value)
        world.serverOffline = false
        world.accounts.remove(uid)
        restored.revalidate()
        assertNull(restored.session.value)
        assertNull(world.store.read())
    }

    @Test
    fun `log out clears the stored session`() = runTest {
        val world = World()
        world.existingAccount("anna@example.com", "haslo1234", RoleDto.CLIENT)
        val (repo) = world.build()
        assertIs<AuthResult.Success>(repo.logIn("anna@example.com", "haslo1234"))
        repo.logOut()
        assertNull(repo.session.value)
        assertNull(world.store.read())
        assertNull(world.build().first.session.value)
    }

    @Test
    fun `secrets never appear in toString`() {
        val stored = StoredAuthCodec.decode(StoredAuthCodec.encode(com.trainingapp.data.auth.StoredAuth("uid-secret", "id-secret", "rt-secret", 5, "{}")))
        assertNotNull(stored)
        assertFalse(stored.toString().contains("secret"))
        assertFalse(registration(password = "tajne-haslo").toString().contains("tajne"))
        assertNull(StoredAuthCodec.decode("garbage"))
    }

    @Test
    fun `identity config falls back to dev when Firebase is not configured`() {
        assertEquals(IdentityConfig.Dev, IdentityConfig.parse(null, null, null))
        assertEquals(IdentityConfig.Dev, IdentityConfig.parse("firebase", "", null))
        assertEquals(IdentityConfig.Dev, IdentityConfig.parse("emulator", "key", null))
        val real = assertIs<IdentityConfig.Firebase>(IdentityConfig.parse("firebase", "key", "client")).firebase
        assertEquals("https://securetoken.googleapis.com/v1", real.secureTokenUrl)
        assertEquals("client", real.googleWebClientId)
    }
}
