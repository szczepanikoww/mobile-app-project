package com.trainingapp.feature.guest

import com.trainingapp.contract.account.MeDto
import com.trainingapp.contract.account.RoleDto
import com.trainingapp.contract.api.ApiErrorDto
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.feature.guest.data.DemoAccounts
import com.trainingapp.feature.guest.data.RemoteAuthRepository
import com.trainingapp.feature.guest.model.AuthResult
import com.trainingapp.feature.guest.model.Registration
import com.trainingapp.feature.guest.model.Role
import com.trainingapp.feature.guest.model.StartingRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteAuthRepositoryTest {
    private val json = Json { encodeDefaults = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun me(name: String, vararg roles: RoleDto) =
        json.encodeToString(MeDto.serializer(), MeDto("00000000-0000-0000-0000-000000000001", name, roles.toList(), "pl", "Europe/Warsaw"))

    private fun error(code: String) = json.encodeToString(ApiErrorDto.serializer(), ApiErrorDto(code, "synthetic"))

    private fun repo(engine: MockEngine) = RemoteAuthRepository(apiFor = { token -> ApiClient("http://test", engine, token = token) })

    @Test
    fun logInMapsRolesAndSendsDevToken() = runTest {
        val auths = mutableListOf<String?>()
        val engine = MockEngine { request ->
            auths += request.headers[HttpHeaders.Authorization]
            respond(me("Katarzyna Nowak", RoleDto.CLIENT), HttpStatusCode.OK, jsonHeaders)
        }
        val repo = repo(engine)
        val session = assertIs<AuthResult.Success>(repo.logIn(DemoAccounts.CLIENT_EMAIL, "dowolne-haslo")).session
        assertEquals("demo-client", session.userId)
        assertEquals("Katarzyna Nowak", session.displayName)
        assertEquals(setOf(Role.Client), session.roles)
        assertEquals(Role.Client, session.activeRole)
        assertEquals(listOf<String?>("Bearer dev.demo-client"), auths)
        assertEquals(session, repo.session.value)
    }

    @Test
    fun bothRolesAccountStartsAsClient() = runTest {
        val engine = MockEngine { respond(me("Michał Kowalczyk", RoleDto.TRAINER, RoleDto.CLIENT), HttpStatusCode.OK, jsonHeaders) }
        val session = assertIs<AuthResult.Success>(repo(engine).logIn(DemoAccounts.BOTH_ROLES_EMAIL, "x")).session
        assertEquals("demo-trainer", session.userId)
        assertEquals(Role.Client, session.activeRole)
        assertEquals(setOf(Role.Trainer, Role.Client), session.roles)
    }

    @Test
    fun noAccountIsInvalidCredentials() = runTest {
        val engine = MockEngine { respond(error("NO_ACCOUNT"), HttpStatusCode.NotFound, jsonHeaders) }
        val repo = repo(engine)
        assertEquals(AuthResult.InvalidCredentials, repo.logIn("nikt@example.org", "x"))
        assertNull(repo.session.value)
    }

    @Test
    fun unreachableServerIsOffline() = runTest {
        val engine = MockEngine { throw RuntimeException("down") }
        assertEquals(AuthResult.Offline, repo(engine).logIn(DemoAccounts.CLIENT_EMAIL, "x"))
    }

    @Test
    fun serverErrorIsUnavailable() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError, jsonHeaders) }
        assertEquals(AuthResult.Unavailable, repo(engine).logIn(DemoAccounts.CLIENT_EMAIL, "x"))
    }

    @Test
    fun registerPostsOnceThenSignsIn() = runTest {
        var posts = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posts++
                respond(me("Nowa Osoba", RoleDto.TRAINER), HttpStatusCode.Created, jsonHeaders)
            } else respond(error("NO_ACCOUNT"), HttpStatusCode.NotFound, jsonHeaders)
        }
        val repo = repo(engine)
        val result = repo.register(Registration("Nowa Osoba", "nowa@example.org", "haslo1234", StartingRole.Trainer, "2026-01"))
        val session = assertIs<AuthResult.Success>(result).session
        assertEquals(1, posts)
        assertEquals(RemoteAuthRepository.subjectFor("nowa@example.org"), session.userId)
        assertTrue(session.userId.startsWith("u-") && session.userId.length <= 128)
        assertEquals(Role.Trainer, session.activeRole)
    }

    @Test
    fun registerOfExistingAccountIsAccountExists() = runTest {
        var posts = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) posts++
            respond(me("Katarzyna Nowak", RoleDto.CLIENT), HttpStatusCode.OK, jsonHeaders)
        }
        val repo = repo(engine)
        assertEquals(AuthResult.AccountExists, repo.register(Registration("K", "klient@example.com", "haslo1234", StartingRole.Client, "2026-01")))
        assertEquals(0, posts)
        assertNull(repo.session.value)
    }

    @Test
    fun conflictOnCreateIsAccountExists() = runTest {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) respond(error("CONFLICT"), HttpStatusCode.Conflict, jsonHeaders)
            else respond(error("NO_ACCOUNT"), HttpStatusCode.NotFound, jsonHeaders)
        }
        val result = repo(engine).register(Registration("K", "x@example.org", "haslo1234", StartingRole.Client, "2026-01"))
        assertEquals(AuthResult.AccountExists, result)
    }

    @Test
    fun logOutClearsSessionAndGoogleIsNotSupported() = runTest {
        val engine = MockEngine { respond(me("Katarzyna Nowak", RoleDto.CLIENT), HttpStatusCode.OK, jsonHeaders) }
        val repo = repo(engine)
        repo.logIn(DemoAccounts.CLIENT_EMAIL, "x")
        repo.logOut()
        assertNull(repo.session.value)
        assertEquals(AuthResult.NotSupported, repo.continueWithGoogle())
    }
}
