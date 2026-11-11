package pl.trainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import pl.trainer.data.remote.RemoteTrainerRepository
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.Failure
import pl.trainer.domain.PaymentKind
import pl.trainer.domain.ProposalAuthor
import pl.trainer.domain.SessionStatus
import pl.trainer.domain.TrainerException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trainer API client against a scripted server: mapping, auth renewal and error mapping. Synthetic data only. */
class RemoteTrainerRepositoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val dashboard = """
        {"clients":[{"id":"rel-1","userId":"u-1","name":"Test Klient","initials":"TK","status":"ACTIVE","hasOverduePayment":true},
                    {"id":"rel-2","userId":"u-2","name":"Inny Klient","initials":"IK","status":"ENDED","hasOverduePayment":false}],
         "sessions":[{"id":"s-1","clientId":"rel-1","trainerId":"t-1","start":"2026-11-12T07:00:00Z","end":"2026-11-12T08:00:00Z",
                      "timeZone":"Europe/Warsaw","location":"Studio","status":"CONFIRMED","revision":3,
                      "proposal":{"id":"p-1","start":"2026-11-13T07:00:00Z","end":"2026-11-13T08:00:00Z","author":"CLIENT",
                                  "status":"PENDING","requestedAt":"2026-11-11T10:00:00Z"},
                      "payment":{"kind":"PACKAGE","packageUsed":2,"packageTotal":8},"policyVersionId":"pol-1",
                      "price":{"minor":15000,"currency":"PLN"},"requestedAt":"2026-11-10T10:00:00Z","futureField":1}],
         "blocks":[{"id":"b-1","kind":"UNAVAILABLE","start":"2026-11-12T11:00:00Z","end":"2026-11-12T12:00:00Z"}],
         "notes":{"rel-1":"Notatka"},"serverNow":"2026-11-11T23:30:00Z"}
    """.trimIndent()

    private class Server(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request -> requests += request; handler(request) }
    }

    private fun repository(server: Server) =
        RemoteTrainerRepository(TrainerApiClient("http://test", subject = { "demo-trainer" }, engine = HttpClient(server.engine)))

    private fun MockRequestHandleScope.token(n: Int = 1) = respond("""{"token":"tok-$n","userId":"t-1"}""", HttpStatusCode.OK, json)

    @Test
    fun dashboardMapsServerDtosToTrainerModels() = runTest {
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("dev/token")) token() else respond(dashboard, HttpStatusCode.OK, json)
        }
        val data = repository(server).load()

        val session = data.sessions.single()
        assertEquals(SessionStatus.Confirmed, session.status)
        assertEquals(3, session.revision)
        assertEquals(ProposalAuthor.Client, session.proposal?.author)
        assertEquals(PaymentKind.Package, session.payment?.kind)
        assertEquals(15000L, session.price?.minor)
        assertNull(session.trainerId)
        assertTrue(data.clients.first().hasOverduePayment)
        assertFalse(data.clients.last().active)
        assertEquals("Notatka", data.notes["rel-1"])
        // 23:30 UTC is already the next day in Warsaw.
        assertEquals("2026-11-12", data.today.toString())
        assertEquals("Bearer tok-1", server.requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun rejectedTokenIsRenewedOnce() = runTest {
        var tokens = 0
        var calls = 0
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("dev/token")) token(++tokens)
            else if (++calls == 1) respond("""{"code":"UNAUTHENTICATED","message":"x"}""", HttpStatusCode.Unauthorized, json)
            else respond(dashboard, HttpStatusCode.OK, json)
        }
        repository(server).load()
        assertEquals(2, tokens)
        assertEquals("Bearer tok-2", server.requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun staleRevisionBecomesConflictAndSendsTheRevision() = runTest {
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("dev/token")) token()
            else respond("""{"code":"CONFLICT","message":"stale"}""", HttpStatusCode.Conflict, json)
        }
        val error = assertFailsWith<TrainerException> { repository(server).cancelSession("s-1", revision = 3) }
        assertEquals(Failure.Conflict, error.failure)
        val body = (server.requests.last().body as TextContent).text
        assertTrue(body.contains("\"revision\":3"), body)
        assertTrue(server.requests.last().url.encodedPath.endsWith("trainer/sessions/s-1/cancel"))
    }

    @Test
    fun errorCodesMapToAppFailures() = runTest {
        suspend fun failureFor(status: HttpStatusCode, code: String): Failure {
            val server = Server { request ->
                if (request.url.encodedPath.endsWith("dev/token")) token() else respond("""{"code":"$code","message":"x"}""", status, json)
            }
            return assertFailsWith<TrainerException> { repository(server).completeSession("s-1", 1) }.failure
        }
        assertEquals(Failure.Denied, failureFor(HttpStatusCode.Forbidden, "DENIED"))
        assertEquals(Failure.InvalidInput, failureFor(HttpStatusCode.UnprocessableEntity, "INVALID_INPUT"))
        assertEquals(Failure.QuoteExpired, failureFor(HttpStatusCode.Conflict, "QUOTE_EXPIRED"))
        assertEquals(Failure.Conflict, failureFor(HttpStatusCode.NotFound, "NOT_FOUND"))
        assertEquals(Failure.Save, failureFor(HttpStatusCode.InternalServerError, "INTERNAL"))
    }

    @Test
    fun noConnectionIsOfflineAndClientInformationIsReadOnly() = runTest {
        val server = Server { throw IllegalStateException("no route") }
        val repository = repository(server)
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repository.load() }.failure)
        assertFalse(repository.canEditClientInformation)
        assertEquals(Failure.Denied, assertFailsWith<TrainerException> {
            repository.saveClientInformation("rel-1", pl.trainer.domain.ClientInformation())
        }.failure)
    }
}
